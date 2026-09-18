[CmdletBinding()]
param(
    [string]$Enabled = '',
    [string]$LauncherEnabled = '',
    [string]$RoleArn = '',
    [string]$InputsFile = '',
    [string]$ExpectedInputsSha256 = '',
    [string]$SourceCommit = '',
    [string]$EventName = $env:GITHUB_EVENT_NAME,
    [string]$BranchRef = $env:GITHUB_REF
)
Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

# Deliberately offline: this contract never invokes a launcher or obtains an
# identity. Valid review inputs cannot enable the production runtime adapter.
if ($Enabled -cne 'true') { throw 'FUNCTIONS_PRODUCTION_GATE_DISABLED' }
& (Join-Path $PSScriptRoot 'check-workflow-context.ps1') -Environment production -EventName $EventName -BranchRef $BranchRef | Out-Null
if ($SourceCommit -cnotmatch '\A[a-f0-9]{40}\z') { throw 'FUNCTIONS_PRODUCTION_SOURCE_INVALID' }

function Read-PinnedJson([string]$Path, [string]$Digest) {
    if ($Digest -cnotmatch '\A[a-f0-9]{64}\z' -or [string]::IsNullOrWhiteSpace($Path) -or
        -not (Test-Path -LiteralPath $Path -PathType Leaf) -or
        (Get-FileHash -LiteralPath $Path -Algorithm SHA256).Hash.ToLowerInvariant() -cne $Digest) {
        throw 'FUNCTIONS_PRODUCTION_INPUT_HASH_MISMATCH'
    }
    $options = @{InputObject=(Get-Content -LiteralPath $Path -Raw); NoEnumerate=$true}
    if ((Get-Command ConvertFrom-Json).Parameters.ContainsKey('DateKind')) { $options.DateKind='String' }
    $value = ConvertFrom-Json @options
    if ($value -isnot [pscustomobject]) { throw 'FUNCTIONS_PRODUCTION_INPUT_OBJECT_REQUIRED' }
    return $value
}
function String-Field([object]$Object, [string]$Name) {
    $property = $Object.PSObject.Properties[$Name]
    if ($null -eq $property -or $property.Value -isnot [string] -or
        [string]::IsNullOrWhiteSpace($property.Value) -or $property.Value -ceq 'null') {
        throw "FUNCTIONS_PRODUCTION_STRING_REQUIRED: $Name"
    }
    return $property.Value
}
function Object-Field([object]$Object, [string]$Name) {
    $property = $Object.PSObject.Properties[$Name]
    if ($null -eq $property -or $property.Value -isnot [pscustomobject]) { throw "FUNCTIONS_PRODUCTION_OBJECT_REQUIRED: $Name" }
    return $property.Value
}
function Schema-One([object]$Object) {
    $property = $Object.PSObject.Properties['schemaVersion']
    if ($null -eq $property -or ($property.Value -isnot [int] -and $property.Value -isnot [long]) -or $property.Value -ne 1) {
        throw 'FUNCTIONS_PRODUCTION_SCHEMA_INVALID'
    }
}
function Pinned-File([object]$Object, [string]$Name) {
    $reference = Object-Field $Object $Name
    $path = String-Field $reference 'path'
    if ([IO.Path]::IsPathRooted($path)) { throw 'FUNCTIONS_PRODUCTION_RELATIVE_PATH_REQUIRED' }
    $resolved = [IO.Path]::GetFullPath((Join-Path $inputDirectory $path))
    if (-not $resolved.StartsWith($inputDirectory + [IO.Path]::DirectorySeparatorChar, [StringComparison]::Ordinal)) {
        throw 'FUNCTIONS_PRODUCTION_INPUT_PATH_ESCAPE'
    }
    $digest = String-Field $reference 'sha256'
    if ($digest -cnotmatch '\A[a-f0-9]{64}\z' -or -not (Test-Path -LiteralPath $resolved -PathType Leaf) -or
        (Get-FileHash -LiteralPath $resolved -Algorithm SHA256).Hash.ToLowerInvariant() -cne $digest) {
        throw "FUNCTIONS_PRODUCTION_FILE_HASH_MISMATCH: $Name"
    }
    return @{Path=$resolved; Sha256=$digest}
}

$inputs = Read-PinnedJson $InputsFile $ExpectedInputsSha256
$inputDirectory = Split-Path -Parent ([IO.Path]::GetFullPath($InputsFile))
Schema-One $inputs
$account = String-Field $inputs 'accountId'
if ($account -cnotmatch '\A[0-9]{12}\z' -or (String-Field $inputs 'environment') -cne 'production' -or
    (String-Field $inputs 'sourceCommit') -cne $SourceCommit -or
    (String-Field $inputs 'projectName') -cne 'oficina-phase3-oficina-functions-production-deploy' -or
    (String-Field $inputs 'sourcePrefix') -cne 'releases/functions/production') { throw 'FUNCTIONS_PRODUCTION_TARGET_MISMATCH' }
if ($RoleArn -cnotmatch ('\Aarn:aws:iam::' + $account + ':role/[A-Za-z0-9+=,.@_/-]*production[A-Za-z0-9+=,.@_/-]*\z') -or
    (String-Field $inputs 'roleArn') -cne $RoleArn) { throw 'FUNCTIONS_PRODUCTION_ROLE_MISMATCH' }
foreach ($bucket in @('artifactBucket','stateBucket')) {
    if ((String-Field $inputs $bucket) -cnotmatch '\A[a-z0-9][a-z0-9.-]{1,61}[a-z0-9]\z') { throw 'FUNCTIONS_PRODUCTION_BUCKET_INVALID' }
}
$deployer = String-Field $inputs 'deployerImageDigest'
if ($deployer -cnotmatch '\Asha256:[a-f0-9]{64}\z') { throw 'FUNCTIONS_PRODUCTION_DEPLOYER_INVALID' }
$source = Pinned-File $inputs 'sourceArchive'
$releaseFile = Pinned-File $inputs 'releaseManifest'
$tfvarsFile = Pinned-File $inputs 'terraformVariables'
$window = Pinned-File $inputs 'cloudWindowEvidence'
$receiptFile = Pinned-File $inputs 'stagingPromotion'
$release = Read-PinnedJson $releaseFile.Path $releaseFile.Sha256
$receipt = Read-PinnedJson $receiptFile.Path $receiptFile.Sha256
$tfvars = Read-PinnedJson $tfvarsFile.Path $tfvarsFile.Sha256
Schema-One $release
Schema-One $receipt
# Consume the actual non-secret terminal result shape produced by the current
# staging launcher. A dry-run result is never a staging promotion receipt.
if ((String-Field $receipt 'environment') -cne 'staging' -or
    (String-Field $receipt 'sourceCommit') -cne $SourceCommit -or
    (String-Field $receipt 'artifactSha256') -cne $source.Sha256 -or
    (String-Field $receipt 'status') -cne 'SUCCEEDED' -or
    (String-Field $receipt 'buildId') -cnotmatch '\Aoficina-phase3-oficina-functions-staging-deploy:[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}\z') { throw 'FUNCTIONS_STAGING_PROMOTION_MISMATCH' }
$recorded = [datetimeoffset]::Parse((String-Field $receipt 'recordedAtUtc'), [Globalization.CultureInfo]::InvariantCulture)
if ($recorded.Offset -ne [timespan]::Zero -or $recorded -gt [datetimeoffset]::UtcNow) { throw 'FUNCTIONS_STAGING_PROMOTION_TIME_INVALID' }
if ((String-Field $release 'environment') -cne 'production' -or
    (String-Field $release 'sourceCommit') -cne $SourceCommit -or
    (String-Field $release 'artifactSha256') -cne $source.Sha256 -or
    (String-Field $release 'stagingArtifactSha256') -cne $source.Sha256 -or
    (String-Field $release 'deployerImageDigest') -cne $deployer -or
    (String-Field $release 'terraformVariablesSha256') -cne $tfvarsFile.Sha256 -or
    (String-Field $release 'cloudWindowEvidenceSha256') -cne $window.Sha256 -or
    (String-Field $release 'contractVersion') -cne 'phase3-v2' -or
    (String-Field $release 'migrationVersion') -cne 'V8') { throw 'FUNCTIONS_PRODUCTION_BINDING_MISMATCH' }
$promoted = $release.PSObject.Properties['promotedFromStaging']
if ($null -eq $promoted -or $promoted.Value -isnot [bool] -or -not $promoted.Value) { throw 'FUNCTIONS_PRODUCTION_PROMOTION_REQUIRED' }
if ((String-Field $tfvars 'environment') -cne 'production' -or
    (String-Field $tfvars 'account_id') -cne $account -or
    (String-Field $tfvars 'aws_region') -cne 'us-east-1') { throw 'FUNCTIONS_PRODUCTION_CONFIG_MISMATCH' }
$lambda = Object-Field $tfvars 'lambda_artifact'
foreach ($name in @('s3_bucket','s3_key','s3_object_version')) { $null = String-Field $lambda $name }
$lambdaHash = String-Field $lambda 'sha256_hex'
if ($lambdaHash -cnotmatch '\A[a-f0-9]{64}\z' -or
    (String-Field $lambda 'sha256_base64') -cne [Convert]::ToBase64String([Convert]::FromHexString($lambdaHash))) { throw 'FUNCTIONS_PRODUCTION_LAMBDA_DIGEST_INVALID' }
& (Join-Path $PSScriptRoot 'check-cloud-window.ps1') -EvidenceFile $window.Path -Environment production | Out-Null
if ($LauncherEnabled -cnotin @('','false')) {
    throw 'FUNCTIONS_PRODUCTION_LAUNCHER_NOT_IMPLEMENTED: a separate reviewed production adapter is required.'
}
Write-Output 'PRODUCTION_CONTRACT_VALIDATED_DEPLOYMENT_DISABLED'

