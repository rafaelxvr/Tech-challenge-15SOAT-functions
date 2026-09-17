[CmdletBinding()]
param(
    [Parameter(Mandatory)][ValidatePattern('^arn:aws:secretsmanager:us-east-1:638612472889:secret:oficina/staging/app-[A-Za-z0-9]{6}$')][string]$AppStagingSecretId,
    [ValidateSet('study-process')][string]$AwsProfile = 'study-process',
    [ValidateSet('us-east-1')][string]$AwsRegion = 'us-east-1',
    [switch]$DryRun,
    [switch]$Apply
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

$expectedAccountId = '638612472889'
$rdsCaBundleUri = 'https://truststore.pki.rds.amazonaws.com/global/global-bundle.pem'
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
    $secret = [string]$jwtProperty.Value
    if ([Text.Encoding]::UTF8.GetByteCount($secret) -lt 32) {
        throw 'APP JWT_SECRET is shorter than the required 32 UTF-8 bytes.'
    }
    return $secret
}

function Convert-ToBase64Url {
    param([Parameter(Mandatory)][byte[]]$Bytes)
    $first = 0
    while ($first -lt ($Bytes.Length - 1) -and $Bytes[$first] -eq 0) { $first++ }
    $trimmed = if ($first -eq 0) { $Bytes } else { $Bytes[$first..($Bytes.Length - 1)] }
    return ([Convert]::ToBase64String($trimmed)).TrimEnd('=').Replace('+', '-').Replace('/', '_')
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
    param([Parameter(Mandatory)][string]$LayerArn)
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
    $caPath = Join-Path $script:TempRoot 'global-bundle.pem'
    try {
        $null = Invoke-WebRequest -Uri $rdsCaBundleUri -OutFile $caPath -UseBasicParsing -ErrorAction Stop
        $pem = [IO.File]::ReadAllText($caPath)
        if ($pem -notmatch '(?m)^-----BEGIN CERTIFICATE-----$' -or $pem -notmatch '(?m)^-----END CERTIFICATE-----$') {
            throw 'RDS CA bundle did not contain PEM certificates.'
        }
        return [ordered]@{ value = $pem; sha256 = (Get-FileHash -LiteralPath $caPath -Algorithm SHA256).Hash.ToLowerInvariant() }
    } catch {
        if ($_.Exception.Message -eq 'RDS CA bundle did not contain PEM certificates.') { throw }
        throw 'RDS global CA bundle could not be downloaded or validated.'
    } finally {
        Remove-TemporaryFile $caPath
    }
}

if ($Apply -and $DryRun) { throw 'Choose either -DryRun or -Apply, not both.' }
$isApply = $Apply.IsPresent
if (-not $isApply) { $DryRun = $true }

New-Item -ItemType Directory -Path $script:TempRoot -Force | Out-Null
try {
    $accountId = Get-AwsAccountId
    $javaLayerArn = Get-ReviewedLayerArn -LayerName 'NewRelicJava17' -DocumentationLabel 'newrelic_java_slim_layer_arn'
    $extensionLayerArn = Get-ReviewedLayerArn -LayerName 'NewRelicExtension' -DocumentationLabel 'newrelic_extension_layer_arn'
    $javaLayer = Get-VerifiedLayerMetadata -LayerArn $javaLayerArn
    $extensionLayer = Get-VerifiedLayerMetadata -LayerArn $extensionLayerArn
    $staffHmacSecret = Get-AppStaffHmacSecret

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
    $ca = Get-RdsCaBundle

    $secretNames = [ordered]@{
        customer_signing_key = 'oficina/staging/customer-signing-key'
        authorizer_trust     = 'oficina/staging/authorizer-trust'
        rds_ca_certificate   = 'oficina/staging/rds-ca-certificate'
    }
    $existing = [ordered]@{}
    foreach ($slot in $secretNames.Keys) { $existing[$slot] = Get-ExistingSecretArn -SecretName $secretNames[$slot] }
    $secretArns = [ordered]@{
        customer_signing_key = $existing.customer_signing_key
        authorizer_trust     = $existing.authorizer_trust
        rds_ca_certificate   = $existing.rds_ca_certificate
    }
    if ($isApply) {
        $secretArns.customer_signing_key = Write-SecretFile -SecretName $secretNames.customer_signing_key -SecretString $customerSecretString -ExistingArn $existing.customer_signing_key
        $secretArns.authorizer_trust = Write-SecretFile -SecretName $secretNames.authorizer_trust -SecretString $authorizerSecretString -ExistingArn $existing.authorizer_trust
        $secretArns.rds_ca_certificate = Write-SecretFile -SecretName $secretNames.rds_ca_certificate -SecretString $ca.value -ExistingArn $existing.rds_ca_certificate
    }

    [ordered]@{
        mode                 = if ($isApply) { 'APPLIED' } else { 'DRY_RUN' }
        account_id           = $accountId
        region               = $AwsRegion
        secret_arns          = $secretArns
        customer_key_id      = $customerKeyId
        staff_key_id         = $staffKeyId
        customer_public_jwk  = $publicJwk
        rds_ca_bundle_sha256 = $ca.sha256
        newrelic_layers      = @($javaLayer, $extensionLayer)
    } | ConvertTo-Json -Depth 8
} finally {
    if ($null -ne $rsa) { $rsa.Dispose() }
    if (Test-Path -LiteralPath $script:TempRoot) { Remove-Item -LiteralPath $script:TempRoot -Recurse -Force -ErrorAction SilentlyContinue }
}
