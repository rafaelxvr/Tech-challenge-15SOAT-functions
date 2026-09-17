[CmdletBinding()]
param(
    [Parameter(Mandatory)][ValidatePattern('^arn:aws:secretsmanager:us-east-1:638612472889:secret:oficina/staging/app-[A-Za-z0-9]{6}$')][string]$AppStagingSecretId,
    [ValidateSet('study-process')][string]$AwsProfile = 'study-process',
    [ValidateSet('us-east-1')][string]$AwsRegion = 'us-east-1',
    [string]$ReviewedKeyMetadataFile,
    [string]$StaffHmacSecretFile,
    [switch]$AllowExternalStaffHmacSecret,
    [switch]$AllowReviewedRotation,
    [switch]$ConfirmKeySynchronization,
    [switch]$DryRun,
    [switch]$Apply
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

$expectedAccountId = '638612472889'
$rdsCaBundleUri = 'https://truststore.pki.rds.amazonaws.com/us-east-1/us-east-1-bundle.pem'
$docPath = Join-Path $PSScriptRoot '..\docs\evidence\staging-runtime-handoff-contract.md'
$script:TempRoot = Join-Path ([IO.Path]::GetTempPath()) ('oficina-staging-secret-prep-' + [guid]::NewGuid().ToString('N'))
$rsa = $null

function Remove-TemporaryFile {
    param([AllowNull()][string]$Path)
    if (-not [string]::IsNullOrWhiteSpace($Path) -and (Test-Path -LiteralPath $Path -PathType Leaf)) {
        Remove-Item -LiteralPath $Path -Force -ErrorAction SilentlyContinue
    }
}

function Invoke-AwsCapture {
    param(
        [Parameter(Mandatory)][string[]]$Arguments,
        [Parameter(Mandatory)][string]$Operation
    )
    $stderrPath = Join-Path $script:TempRoot ([guid]::NewGuid().ToString('N') + '.stderr')
    try {
        $captured = & aws @Arguments 2> $stderrPath
        if ($LASTEXITCODE -ne 0) {
            throw "AWS $Operation failed."
        }
        return (($captured -join [Environment]::NewLine).Trim())
    } catch {
        if ($_.Exception.Message -eq "AWS $Operation failed.") { throw }
        throw "AWS $Operation could not be executed."
    } finally {
        Remove-TemporaryFile $stderrPath
    }
}

function Get-AwsAccountId {
    $account = Invoke-AwsCapture -Operation 'caller identity' -Arguments @(
        'sts', 'get-caller-identity', '--profile', $AwsProfile, '--region', $AwsRegion,
        '--query', 'Account', '--output', 'text', '--no-cli-pager'
    )
    if ($account -cne $expectedAccountId) {
        throw 'AWS identity is not the approved study account.'
    }
    return $account
}

function Assert-StaffHmacSecret {
    param([Parameter(Mandatory)][string]$Secret)
    if ([string]::IsNullOrWhiteSpace($Secret) -or [Text.Encoding]::UTF8.GetByteCount($Secret) -lt 32) {
        throw 'Staff HMAC secret is shorter than the required 32 UTF-8 bytes.'
    }
    return $Secret
}

function Get-AppStaffHmacSecret {
    $raw = Invoke-AwsCapture -Operation 'APP staging secret retrieval' -Arguments @(
        'secretsmanager', 'get-secret-value', '--secret-id', $AppStagingSecretId,
        '--profile', $AwsProfile, '--region', $AwsRegion,
        '--query', 'SecretString', '--output', 'text', '--no-cli-pager'
    )
    if ([string]::IsNullOrWhiteSpace($raw) -or $raw -eq 'None') {
        throw 'APP staging SecretString is missing.'
    }
    try {
        $document = $raw | ConvertFrom-Json
    } catch {
        throw 'APP staging SecretString is not valid JSON.'
    }
    $jwtProperty = $document.PSObject.Properties['JWT_SECRET']
    if ($null -eq $jwtProperty -or $jwtProperty.Value -isnot [string] -or [string]::IsNullOrWhiteSpace([string]$jwtProperty.Value)) {
        throw 'APP staging SecretString has no approved JWT_SECRET.'
    }
    return Assert-StaffHmacSecret ([string]$jwtProperty.Value)
}

function Get-StaffHmacSecret {
    try {
        return Get-AppStaffHmacSecret
    } catch {
        if ($_.Exception.Message -notin @('APP staging SecretString has no approved JWT_SECRET.', 'APP staging SecretString is missing.')) { throw }
        if (-not $AllowExternalStaffHmacSecret -or [string]::IsNullOrWhiteSpace($StaffHmacSecretFile)) {
            throw 'APP staging SecretString has no approved JWT_SECRET; fail-closed. A reviewed -StaffHmacSecretFile with -AllowExternalStaffHmacSecret is required for an explicit fallback.'
        }
        if (-not (Test-Path -LiteralPath $StaffHmacSecretFile -PathType Leaf)) { throw 'The reviewed staff HMAC secret file does not exist.' }
        try {
            return Assert-StaffHmacSecret ([IO.File]::ReadAllText((Resolve-Path -LiteralPath $StaffHmacSecretFile)))
        } catch {
            if ($_.Exception.Message -eq 'Staff HMAC secret is shorter than the required 32 UTF-8 bytes.') { throw }
            throw 'The reviewed staff HMAC secret file could not be read.'
        }
    }
}

function Convert-ToBase64Url {
    param([Parameter(Mandatory)][byte[]]$Bytes)
    $first = 0
    while ($first -lt ($Bytes.Length - 1) -and $Bytes[$first] -eq 0) { $first++ }
    $trimmed = if ($first -eq 0) { $Bytes } else { $Bytes[$first..($Bytes.Length - 1)] }
    return ([Convert]::ToBase64String($trimmed)).TrimEnd('=').Replace('+', '-').Replace('/', '_')
}

function Get-PublicJwkFromDer {
    param([Parameter(Mandatory)][byte[]]$Der)
    $existingRsa = [Security.Cryptography.RSA]::Create()
    try {
        $bytesRead = 0
        $existingRsa.ImportSubjectPublicKeyInfo($Der, [ref]$bytesRead)
        if ($bytesRead -ne $Der.Length) { throw 'Public key DER has trailing bytes.' }
        $parameters = $existingRsa.ExportParameters($false)
        return [ordered]@{ kty = 'RSA'; alg = 'RS256'; use = 'sig'; n = Convert-ToBase64Url $parameters.Modulus; e = Convert-ToBase64Url $parameters.Exponent }
    } catch {
        if ($_.Exception.Message -eq 'Public key DER has trailing bytes.') { throw }
        throw 'Stored customer public key is not valid X.509 RSA material.'
    } finally {
        $existingRsa.Dispose()
    }
}

function Get-ReviewedKeyMetadata {
    if ([string]::IsNullOrWhiteSpace($ReviewedKeyMetadataFile)) {
        throw 'Existing customer/authorizer secrets require a reviewed non-secret key metadata file.'
    }
    if (-not (Test-Path -LiteralPath $ReviewedKeyMetadataFile -PathType Leaf)) {
        throw 'The reviewed key metadata file does not exist.'
    }
    try {
        $metadata = Get-Content -LiteralPath $ReviewedKeyMetadataFile -Raw | ConvertFrom-Json
    } catch {
        throw 'The reviewed key metadata file is not valid JSON.'
    }
    foreach ($property in @('customer_key_id', 'staff_key_id', 'customer_public_jwk')) {
        if ($null -eq $metadata.PSObject.Properties[$property]) { throw "Reviewed key metadata is missing $property." }
    }
    $jwk = $metadata.customer_public_jwk
    $required = @('kty', 'alg', 'use', 'n', 'e')
    if (@($jwk.PSObject.Properties.Name | Sort-Object) -join ',' -cne (@($required | Sort-Object) -join ',')) {
        throw 'Reviewed customer public JWK must contain exactly kty, alg, use, n and e.'
    }
    if ([string]$jwk.kty -cne 'RSA' -or [string]$jwk.alg -cne 'RS256' -or [string]$jwk.use -cne 'sig' -or [string]$jwk.e -cne 'AQAB' -or [string]::IsNullOrWhiteSpace([string]$jwk.n)) {
        throw 'Reviewed customer public JWK is not the approved RSA/RS256 shape.'
    }
    [ordered]@{
        customer_key_id     = [string]$metadata.customer_key_id
        staff_key_id        = [string]$metadata.staff_key_id
        customer_public_jwk = [ordered]@{ kty = [string]$jwk.kty; alg = [string]$jwk.alg; use = [string]$jwk.use; n = [string]$jwk.n; e = [string]$jwk.e }
    }
}

function Assert-PublicJwkMatches {
    param(
        [Parameter(Mandatory)]$Expected,
        [Parameter(Mandatory)]$Actual
    )
    foreach ($property in @('kty', 'alg', 'use', 'n', 'e')) {
        if ([string]$Expected.$property -cne [string]$Actual.$property) { throw 'Stored customer public key does not match reviewed JWK metadata.' }
    }
}

function Get-ReviewedLayerArn {
    param(
        [Parameter(Mandatory)][string]$LayerName,
        [Parameter(Mandatory)][string]$DocumentationLabel
    )
    if (-not (Test-Path -LiteralPath $docPath -PathType Leaf)) {
        throw "Reviewed New Relic layer documentation is missing: $DocumentationLabel."
    }
    $documentation = Get-Content -LiteralPath $docPath -Raw
    $pattern = '(?m)^\s*' + [regex]::Escape($DocumentationLabel) + '\s*=\s*(arn:aws:lambda:us-east-1:451483290750:layer:' + [regex]::Escape($LayerName) + ':[1-9][0-9]*)\s*$'
    $match = [regex]::Match($documentation, $pattern)
    if (-not $match.Success) {
        throw "Reviewed New Relic $LayerName layer version is unresolved in the handoff documentation."
    }
    return $match.Groups[1].Value
}

function Get-VerifiedLayerMetadata {
    param(
        [Parameter(Mandatory)][string]$LayerArn,
        [Parameter(Mandatory)][string]$LayerName
    )
    try {
        $raw = Invoke-AwsCapture -Operation 'New Relic layer verification' -Arguments @(
            'lambda', 'get-layer-version-by-arn', '--arn', $LayerArn,
            '--profile', $AwsProfile, '--region', $AwsRegion,
            '--output', 'json', '--no-cli-pager'
        )
        $layer = $raw | ConvertFrom-Json
    } catch {
        throw 'New Relic layer verification failed; no layer version is trusted.'
    }
    if ([string]$layer.LayerVersionArn -cne $LayerArn -or [int]$layer.Version -le 0) {
        throw 'New Relic layer verification returned an unexpected version.'
    }
    $compatibleRuntimes = @()
    if ($null -ne $layer.CompatibleRuntimes) { $compatibleRuntimes = @($layer.CompatibleRuntimes) }
    if ([string]::IsNullOrWhiteSpace([string]$layer.Content.CodeSha256)) {
        throw 'New Relic layer verification returned no immutable code checksum.'
    }
    if ($LayerName -eq 'NewRelicJava17' -and -not ($compatibleRuntimes -contains 'java17')) {
        throw 'The reviewed New Relic Java layer is not compatible with Java 17.'
    }
    [ordered]@{
        arn                = [string]$layer.LayerVersionArn
        version            = [int]$layer.Version
        code_sha256        = [string]$layer.Content.CodeSha256
        compatible_runtimes = $compatibleRuntimes
        description        = [string]$layer.Description
    }
}

function Get-ExistingSecretArn {
    param([Parameter(Mandatory)][string]$SecretName)
    $stderrPath = Join-Path $script:TempRoot ([guid]::NewGuid().ToString('N') + '.stderr')
    try {
        $captured = & aws secretsmanager describe-secret --secret-id $SecretName --profile $AwsProfile --region $AwsRegion --query ARN --output text --no-cli-pager 2> $stderrPath
        if ($LASTEXITCODE -eq 0) { return (($captured -join [Environment]::NewLine).Trim()) }
        $errorText = [IO.File]::ReadAllText($stderrPath)
        if ($errorText -match 'ResourceNotFoundException|ResourceNotFound') { return $null }
        throw 'AWS secret inspection failed.'
    } catch {
        if ($_.Exception.Message -eq 'AWS secret inspection failed.') { throw }
        throw 'AWS secret inspection could not be executed.'
    } finally {
        Remove-TemporaryFile $stderrPath
    }
}

function Get-ExistingSecretField {
    param(
        [Parameter(Mandatory)][string]$SecretArn,
        [Parameter(Mandatory)][string]$FieldName
    )
    $raw = Invoke-AwsCapture -Operation 'existing runtime secret verification' -Arguments @(
        'secretsmanager', 'get-secret-value', '--secret-id', $SecretArn,
        '--profile', $AwsProfile, '--region', $AwsRegion,
        '--query', 'SecretString', '--output', 'text', '--no-cli-pager'
    )
    try {
        $document = $raw | ConvertFrom-Json
    } catch {
        throw 'Existing runtime secret is not valid JSON.'
    }
    $property = $document.PSObject.Properties[$FieldName]
    if ($null -eq $property -or $property.Value -isnot [string] -or [string]::IsNullOrWhiteSpace([string]$property.Value)) {
        throw "Existing runtime secret is missing $FieldName."
    }
    return [string]$property.Value
}

function Get-ExistingCustomerPublicJwk {
    param(
        [Parameter(Mandatory)][string]$CustomerSecretArn,
        [Parameter(Mandatory)][string]$AuthorizerSecretArn
    )
    $authorizerPublicB64 = Get-ExistingSecretField -SecretArn $AuthorizerSecretArn -FieldName 'CUSTOMER_PUBLIC_KEY_B64'
    $customerPrivateB64 = Get-ExistingSecretField -SecretArn $CustomerSecretArn -FieldName 'CUSTOMER_PRIVATE_KEY_B64'
    try {
        $authorizerJwk = Get-PublicJwkFromDer -Der ([Convert]::FromBase64String($authorizerPublicB64))
        $customerRsa = [Security.Cryptography.RSA]::Create()
        try {
            $bytesRead = 0
            $customerRsa.ImportPkcs8PrivateKey([Convert]::FromBase64String($customerPrivateB64), [ref]$bytesRead)
            $customerPublicDer = $customerRsa.ExportSubjectPublicKeyInfo()
            $customerJwk = Get-PublicJwkFromDer -Der $customerPublicDer
        } finally {
            $customerRsa.Dispose()
        }
    } catch {
        throw 'Existing customer signing and authorizer trust material is not valid RSA key material.'
    }
    Assert-PublicJwkMatches -Expected $authorizerJwk -Actual $customerJwk
    return $authorizerJwk
}

function Write-SecretFile {
    param(
        [Parameter(Mandatory)][string]$SecretName,
        [Parameter(Mandatory)][string]$SecretString,
        [AllowNull()][string]$ExistingArn
    )
    $secretPath = Join-Path $script:TempRoot ([guid]::NewGuid().ToString('N') + '.secret')
    try {
        [IO.File]::WriteAllText($secretPath, $SecretString, [Text.UTF8Encoding]::new($false))
        if ([string]::IsNullOrWhiteSpace($ExistingArn)) {
            $raw = Invoke-AwsCapture -Operation "creating $SecretName" -Arguments @(
                'secretsmanager', 'create-secret', '--name', $SecretName,
                '--secret-string', "file://$secretPath", '--profile', $AwsProfile,
                '--region', $AwsRegion, '--output', 'json', '--no-cli-pager'
            )
            $created = $raw | ConvertFrom-Json
            if ([string]::IsNullOrWhiteSpace([string]$created.ARN)) { throw "AWS did not return the ARN for $SecretName." }
            return [string]$created.ARN
        }
        $null = Invoke-AwsCapture -Operation "updating $SecretName" -Arguments @(
            'secretsmanager', 'put-secret-value', '--secret-id', $ExistingArn,
            '--secret-string', "file://$secretPath", '--profile', $AwsProfile,
            '--region', $AwsRegion, '--output', 'json', '--no-cli-pager'
        )
        return $ExistingArn
    } finally {
        Remove-TemporaryFile $secretPath
    }
}

function Get-RdsCaBundle {
    $caPath = Join-Path $script:TempRoot 'us-east-1-bundle.pem'
    try {
        $null = Invoke-WebRequest -Uri $rdsCaBundleUri -OutFile $caPath -UseBasicParsing -ErrorAction Stop
        $size = (Get-Item -LiteralPath $caPath).Length
        if ($size -ge 65536) { throw 'RDS regional CA bundle is 64 KiB or larger.' }
        $pem = [IO.File]::ReadAllText($caPath)
        if ($pem -notmatch '(?m)^-----BEGIN CERTIFICATE-----$' -or $pem -notmatch '(?m)^-----END CERTIFICATE-----$') {
            throw 'RDS CA bundle did not contain PEM certificates.'
        }
        return [ordered]@{ value = $pem; sha256 = (Get-FileHash -LiteralPath $caPath -Algorithm SHA256).Hash.ToLowerInvariant() }
    } catch {
        if ($_.Exception.Message -in @('RDS regional CA bundle is 64 KiB or larger.', 'RDS CA bundle did not contain PEM certificates.')) { throw }
        throw 'RDS us-east-1 CA bundle could not be downloaded or validated.'
    } finally {
        Remove-TemporaryFile $caPath
    }
}

if ($Apply -and $DryRun) { throw 'Choose either -DryRun or -Apply, not both.' }
$externalHmacArgumentsValid = -not $AllowExternalStaffHmacSecret -or -not [string]::IsNullOrWhiteSpace($StaffHmacSecretFile)
if (-not $externalHmacArgumentsValid) { throw '-AllowExternalStaffHmacSecret requires -StaffHmacSecretFile.' }
$fileWithoutGuard = -not [string]::IsNullOrWhiteSpace($StaffHmacSecretFile) -and -not $AllowExternalStaffHmacSecret
if ($fileWithoutGuard) { throw '-StaffHmacSecretFile requires -AllowExternalStaffHmacSecret.' }
$isApply = $Apply.IsPresent
if (-not $isApply) { $DryRun = $true }

New-Item -ItemType Directory -Path $script:TempRoot -Force | Out-Null
try {
    $accountId = Get-AwsAccountId
    $javaLayerArn = Get-ReviewedLayerArn -LayerName 'NewRelicJava17' -DocumentationLabel 'newrelic_java_slim_layer_arn'
    $extensionLayerArn = Get-ReviewedLayerArn -LayerName 'NewRelicLambdaExtension' -DocumentationLabel 'newrelic_extension_layer_arn'
    $javaLayer = Get-VerifiedLayerMetadata -LayerArn $javaLayerArn -LayerName 'NewRelicJava17'
    $extensionLayer = Get-VerifiedLayerMetadata -LayerArn $extensionLayerArn -LayerName 'NewRelicLambdaExtension'

    $secretNames = [ordered]@{
        customer_signing_key = 'oficina/staging/customer-signing-key'
        authorizer_trust     = 'oficina/staging/authorizer-trust'
        rds_ca_certificate   = 'oficina/staging/rds-ca-certificate'
    }
    $existing = [ordered]@{}
    foreach ($slot in $secretNames.Keys) { $existing[$slot] = Get-ExistingSecretArn -SecretName $secretNames[$slot] }
    $existingArns = @($existing.Values | Where-Object { -not [string]::IsNullOrWhiteSpace([string]$_) })
    if ($existingArns.Count -ne (@($existingArns | Select-Object -Unique).Count)) {
        throw 'Target secret inspection returned duplicate ARNs; refusing to continue.'
    }

    $customerExists = -not [string]::IsNullOrWhiteSpace([string]$existing.customer_signing_key)
    $authorizerExists = -not [string]::IsNullOrWhiteSpace([string]$existing.authorizer_trust)
    $rotationRequested = $AllowReviewedRotation.IsPresent
    if (($customerExists -xor $authorizerExists) -and -not $rotationRequested) {
        throw 'Customer signing and authorizer trust targets must exist together; use explicit full rotation for a partial state.'
    }

    $customerSecretString = $null
    $authorizerSecretString = $null
    $customerKeyId = $null
    $staffKeyId = $null
    $publicJwk = $null
    $ca = $null
    $keyMaterialWillBeWritten = $rotationRequested -or -not $customerExists
    if (-not $keyMaterialWillBeWritten) {
        $storedJwk = Get-ExistingCustomerPublicJwk -CustomerSecretArn $existing.customer_signing_key -AuthorizerSecretArn $existing.authorizer_trust
        $reviewedMetadata = Get-ReviewedKeyMetadata
        Assert-PublicJwkMatches -Expected $reviewedMetadata.customer_public_jwk -Actual $storedJwk
        $customerKeyId = $reviewedMetadata.customer_key_id
        $staffKeyId = $reviewedMetadata.staff_key_id
        $publicJwk = $storedJwk
    } else {
        if ($isApply -and -not $ConfirmKeySynchronization) {
            throw 'Apply requires -ConfirmKeySynchronization after reviewing the emitted public JWK and key IDs.'
        }
        $staffHmacSecret = Get-StaffHmacSecret
        $rsa = [Security.Cryptography.RSA]::Create()
        $rsa.KeySize = 2048
        if ($rsa.KeySize -ne 2048) { throw 'The generated customer RSA key is not 2048 bits.' }
        $privateKeyB64 = [Convert]::ToBase64String($rsa.ExportPkcs8PrivateKey())
        $publicKeyB64 = [Convert]::ToBase64String($rsa.ExportSubjectPublicKeyInfo())
        $parameters = $rsa.ExportParameters($false)
        $customerKeyId = 'customer-' + ([datetime]::UtcNow.ToString('yyyy-MM'))
        $staffKeyId = 'staff-' + ([datetime]::UtcNow.ToString('yyyy-MM'))
        $publicJwk = [ordered]@{ kty = 'RSA'; alg = 'RS256'; use = 'sig'; n = Convert-ToBase64Url $parameters.Modulus; e = Convert-ToBase64Url $parameters.Exponent }
        $customerSecretString = ([ordered]@{ CUSTOMER_PRIVATE_KEY_B64 = $privateKeyB64 } | ConvertTo-Json -Compress)
        $authorizerSecretString = ([ordered]@{ CUSTOMER_PUBLIC_KEY_B64 = $publicKeyB64; STAFF_HMAC_SECRET = $staffHmacSecret } | ConvertTo-Json -Compress)
    }

    $caWillBeWritten = $rotationRequested -or [string]::IsNullOrWhiteSpace([string]$existing.rds_ca_certificate)
    if ($caWillBeWritten) { $ca = Get-RdsCaBundle }

    $secretArns = [ordered]@{
        customer_signing_key = $existing.customer_signing_key
        authorizer_trust     = $existing.authorizer_trust
        rds_ca_certificate   = $existing.rds_ca_certificate
    }
    $secretStatus = [ordered]@{}
    foreach ($slot in $secretNames.Keys) {
        $exists = -not [string]::IsNullOrWhiteSpace([string]$existing[$slot])
        $willWrite = $keyMaterialWillBeWritten
        if ($slot -eq 'rds_ca_certificate') { $willWrite = $caWillBeWritten }
        $secretStatus[$slot] = if ($exists -and -not $willWrite) { 'existing' } elseif ($isApply) { if ($exists) { 'rotated' } else { 'created' } } else { if ($exists) { 'would_rotate' } else { 'would_create' } }
    }
    if ($isApply) {
        if ($keyMaterialWillBeWritten) {
            $secretArns.customer_signing_key = Write-SecretFile -SecretName $secretNames.customer_signing_key -SecretString $customerSecretString -ExistingArn $existing.customer_signing_key
            $secretArns.authorizer_trust = Write-SecretFile -SecretName $secretNames.authorizer_trust -SecretString $authorizerSecretString -ExistingArn $existing.authorizer_trust
        }
        if ($caWillBeWritten) {
            $secretArns.rds_ca_certificate = Write-SecretFile -SecretName $secretNames.rds_ca_certificate -SecretString $ca.value -ExistingArn $existing.rds_ca_certificate
        }
    }

    [ordered]@{
        mode                 = if ($isApply) { 'APPLIED' } else { 'DRY_RUN' }
        account_id           = $accountId
        region               = $AwsRegion
        secret_arns          = $secretArns
        secret_status        = $secretStatus
        customer_key_id      = $customerKeyId
        staff_key_id         = $staffKeyId
        customer_public_jwk  = $publicJwk
        rds_ca_bundle_sha256 = if ($null -eq $ca) { $null } else { $ca.sha256 }
        newrelic_layers      = @($javaLayer, $extensionLayer)
    } | ConvertTo-Json -Depth 8
} finally {
    if ($null -ne $rsa) { $rsa.Dispose() }
    if (Test-Path -LiteralPath $script:TempRoot) { Remove-Item -LiteralPath $script:TempRoot -Recurse -Force -ErrorAction SilentlyContinue }
}
