[CmdletBinding()]
param(
    [Parameter(Mandatory)][ValidatePattern('^arn:aws:secretsmanager:us-east-1:638612472889:secret:oficina/staging/app-[A-Za-z0-9]{6}$')][string]$AppStagingSecretId,
    [Parameter(Mandatory)][ValidatePattern('^arn:aws:secretsmanager:us-east-1:638612472889:secret:oficina/staging/authorizer-trust-[A-Za-z0-9]{6}$')][string]$AuthorizerTrustSecretId,
    [ValidateSet('study-process')][string]$AwsProfile = 'study-process',
    [ValidateSet('us-east-1')][string]$AwsRegion = 'us-east-1',
    [switch]$DryRun,
    [switch]$Apply
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

$expectedAccountId = '638612472889'
$script:TempRoot = Join-Path ([IO.Path]::GetTempPath()) ('oficina-staging-app-jwt-' + [guid]::NewGuid().ToString('N'))

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
        if ($LASTEXITCODE -ne 0) { throw "AWS $Operation failed." }
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
    if ($account -cne $expectedAccountId) { throw 'AWS identity is not the approved study account.' }
    return $account
}

function Get-SecretDocument {
    param([Parameter(Mandatory)][string]$SecretId, [Parameter(Mandatory)][string]$Label)
    $raw = Invoke-AwsCapture -Operation "$Label retrieval" -Arguments @(
        'secretsmanager', 'get-secret-value', '--secret-id', $SecretId,
        '--profile', $AwsProfile, '--region', $AwsRegion,
        '--query', 'SecretString', '--output', 'text', '--no-cli-pager'
    )
    if ([string]::IsNullOrWhiteSpace($raw) -or $raw -eq 'None') { throw "$Label SecretString is missing." }
    try { return ($raw | ConvertFrom-Json) } catch { throw "$Label SecretString is not valid JSON." }
}

function Assert-StaffHmacSecret {
    param([Parameter(Mandatory)][string]$Secret)
    if ([string]::IsNullOrWhiteSpace($Secret) -or [Text.Encoding]::UTF8.GetByteCount($Secret) -lt 32) {
        throw 'Authorizer STAFF_HMAC_SECRET is shorter than the required 32 UTF-8 bytes.'
    }
    return $Secret
}

function Get-RequiredStringProperty {
    param([Parameter(Mandatory)]$Document, [Parameter(Mandatory)][string]$Property, [Parameter(Mandatory)][string]$Label)
    $entry = $Document.PSObject.Properties[$Property]
    if ($null -eq $entry -or $entry.Value -isnot [string] -or [string]::IsNullOrWhiteSpace([string]$entry.Value)) {
        throw "$Label is missing a nonempty string $Property."
    }
    return [string]$entry.Value
}

function Write-AppSecret {
    param([Parameter(Mandatory)][string]$SecretId, [Parameter(Mandatory)][string]$SecretString)
    $secretPath = Join-Path $script:TempRoot ([guid]::NewGuid().ToString('N') + '.secret')
    try {
        [IO.File]::WriteAllText($secretPath, $SecretString, [Text.UTF8Encoding]::new($false))
        $null = Invoke-AwsCapture -Operation 'updating APP staging secret' -Arguments @(
            'secretsmanager', 'put-secret-value', '--secret-id', $SecretId,
            '--secret-string', "file://$secretPath", '--profile', $AwsProfile,
            '--region', $AwsRegion, '--output', 'json', '--no-cli-pager'
        )
    } finally {
        Remove-TemporaryFile $secretPath
    }
}

if ($Apply -and $DryRun) { throw 'Choose either -DryRun or -Apply, not both.' }
$isApply = $Apply.IsPresent
if (-not $isApply) { $DryRun = $true }

New-Item -ItemType Directory -Path $script:TempRoot -Force | Out-Null
try {
    $accountId = Get-AwsAccountId
    $authorizer = Get-SecretDocument -SecretId $AuthorizerTrustSecretId -Label 'Authorizer trust'
    $staffHmacSecret = Assert-StaffHmacSecret (Get-RequiredStringProperty -Document $authorizer -Property 'STAFF_HMAC_SECRET' -Label 'Authorizer trust')
    $app = Get-SecretDocument -SecretId $AppStagingSecretId -Label 'APP staging'

    $jwtProperty = $app.PSObject.Properties['JWT_SECRET']
    $jwtState = if ($null -eq $jwtProperty) { 'would_add' } elseif ($jwtProperty.Value -isnot [string]) { throw 'APP staging JWT_SECRET must be a string.' } elseif ([string]$jwtProperty.Value -ceq $staffHmacSecret) { 'existing' } else { 'would_update' }
    $app.PSObject.Properties.Remove('JWT_SECRET')
    $app | Add-Member -NotePropertyName JWT_SECRET -NotePropertyValue $staffHmacSecret

    if ($isApply -and $jwtState -ne 'existing') {
        $json = $app | ConvertTo-Json -Depth 20 -Compress
        Write-AppSecret -SecretId $AppStagingSecretId -SecretString $json
        $jwtState = if ($jwtState -eq 'would_add') { 'added' } else { 'updated' }
    }

    [ordered]@{
        mode                 = if ($isApply) { 'APPLIED' } else { 'DRY_RUN' }
        account_id           = $accountId
        region               = $AwsRegion
        app_secret_arn       = $AppStagingSecretId
        authorizer_secret_arn = $AuthorizerTrustSecretId
        jwt_secret_status    = $jwtState
    } | ConvertTo-Json -Depth 4
} finally {
    if (Test-Path -LiteralPath $script:TempRoot) { Remove-Item -LiteralPath $script:TempRoot -Recurse -Force -ErrorAction SilentlyContinue }
}
