[CmdletBinding()]
param(
    [Parameter(Mandatory)][ValidateSet('staging','production')][string]$Environment,
    [Parameter(Mandatory)][ValidateNotNullOrEmpty()][string]$SourceZip,
    [Parameter(Mandatory)][ValidatePattern('^[a-f0-9]{64}$')][string]$ExpectedSha256,
    [Parameter(Mandatory)][ValidateNotNullOrEmpty()][string]$ReleaseManifest,
    [Parameter(Mandatory)][ValidatePattern('^[a-f0-9]{64}$')][string]$ExpectedManifestSha256,
    [Parameter(Mandatory)][ValidatePattern('^[a-z0-9][a-z0-9.-]{1,61}[a-z0-9]$')][string]$Bucket,
    [Parameter(Mandatory)][ValidatePattern('^[a-z0-9][a-z0-9/_-]*$')][string]$SourcePrefix,
    [Parameter(Mandatory)][ValidatePattern('^[A-Za-z0-9_.-]+$')][string]$ProjectName,
    [Parameter(Mandatory)][ValidatePattern('^[a-f0-9]{64}$')][string]$DeployerImageDigest,
    [Parameter(Mandatory)][ValidatePattern('^[a-f0-9]{40}$')][string]$SourceCommit,
    [Parameter(Mandatory)][string]$CloudWindowEvidenceFile,
    [Parameter(Mandatory)][string]$TerraformVariablesFile,
    [Parameter(Mandatory)][ValidatePattern('^[a-z0-9][a-z0-9.-]{1,61}[a-z0-9]$')][string]$StateBucket,
    [string]$ResultOutputFile,
    [ValidateRange(60, 2400)][int]$TimeoutSeconds = 2100,
    [string]$EventName = $env:GITHUB_EVENT_NAME,
    [string]$BranchRef = $env:GITHUB_REF,
    [switch]$DryRun
)
Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
function Fail([string]$Message) { throw "Deployment launch failed: $Message" }
function Require([object]$Object, [string]$Name) { $p = $Object.PSObject.Properties[$Name]; if ($null -eq $p -or $null -eq $p.Value -or ([string]$p.Value).Trim().Length -eq 0) { Fail "release manifest is missing '$Name'." }; return $p.Value }
function Hash([string]$Path) { (Get-FileHash -LiteralPath $Path -Algorithm SHA256).Hash.ToLowerInvariant() }
function Write-Result([string]$Status, [string]$BuildId = '') {
    if ([string]::IsNullOrWhiteSpace($ResultOutputFile)) { return }
    $parent = Split-Path -Parent $ResultOutputFile
    if ($parent -and -not (Test-Path -LiteralPath $parent)) { New-Item -ItemType Directory -Path $parent -Force | Out-Null }
    [ordered]@{ schemaVersion = 1; environment = $Environment; sourceCommit = $SourceCommit; artifactSha256 = $ExpectedSha256; status = $Status; buildId = $BuildId; recordedAtUtc = [datetime]::UtcNow.ToString('o') } | ConvertTo-Json | Set-Content -LiteralPath $ResultOutputFile -NoNewline
}
if ($Environment -cne 'staging') { Fail 'production deployment is disabled; this launcher is staging-only.' }
if ($SourcePrefix -cne 'releases/functions/staging' -or $ProjectName -cne 'oficina-phase3-oficina-functions-staging-deploy') { Fail 'source prefix or CodeBuild project is not the reviewed staging executor.' }
if (-not (Test-Path -LiteralPath $SourceZip -PathType Leaf) -or -not (Test-Path -LiteralPath $ReleaseManifest -PathType Leaf) -or -not (Test-Path -LiteralPath $TerraformVariablesFile -PathType Leaf)) { Fail 'source, manifest, and Terraform variables files are required.' }
if ((Hash $SourceZip) -cne $ExpectedSha256 -or (Hash $ReleaseManifest) -cne $ExpectedManifestSha256) { Fail 'source or manifest digest mismatch.' }
$tfvarsSha = Hash $TerraformVariablesFile
try { $manifest = Get-Content -LiteralPath $ReleaseManifest -Raw | ConvertFrom-Json } catch { Fail 'release manifest is not valid JSON.' }
if ($manifest.schemaVersion -ne 1 -or [string](Require $manifest 'environment') -cne 'staging' -or [string](Require $manifest 'sourceCommit') -cne $SourceCommit -or [string](Require $manifest 'artifactSha256') -cne $ExpectedSha256 -or [string](Require $manifest 'deployerImageDigest') -cne "sha256:$DeployerImageDigest") { Fail 'release manifest does not bind the reviewed source, executor, or staging environment.' }
if ([string](Require $manifest 'contractVersion') -cne 'phase3-v2' -or [string]::IsNullOrWhiteSpace([string](Require $manifest 'migrationVersion'))) { Fail 'release manifest is missing the reviewed contract or migration version.' }
& (Join-Path $PSScriptRoot 'check-workflow-context.ps1') -Environment staging -EventName $EventName -BranchRef $BranchRef | Out-Null
& (Join-Path $PSScriptRoot 'check-cloud-window.ps1') -EvidenceFile $CloudWindowEvidenceFile -Environment staging | Out-Null
if ($DryRun) { Write-Result 'DRY_RUN_VALIDATED'; Write-Output 'Deployment launch request validated; dry run did not call AWS.'; exit 0 }
$sourceKey = "$SourcePrefix/bundle.zip"
$manifestKey = "$SourcePrefix/manifests/$SourceCommit.json"
$tfvarsKey = "$SourcePrefix/config/$SourceCommit.tfvars.json"
$sourceResult = & aws s3api put-object --bucket $Bucket --key $sourceKey --body $SourceZip --metadata "sha256=$ExpectedSha256" --output json 2>$null
if ($LASTEXITCODE -ne 0) { Fail 'versioned source upload failed.' }
$sourceUpload = $sourceResult | ConvertFrom-Json
if ([string]::IsNullOrWhiteSpace([string]$sourceUpload.VersionId)) { Fail 'source upload returned no S3 VersionId.' }
$manifestResult = & aws s3api put-object --bucket $Bucket --key $manifestKey --body $ReleaseManifest --metadata "sha256=$ExpectedManifestSha256" --output json 2>$null
if ($LASTEXITCODE -ne 0) { Fail 'versioned release manifest upload failed.' }
$manifestUpload = $manifestResult | ConvertFrom-Json
if ([string]::IsNullOrWhiteSpace([string]$manifestUpload.VersionId)) { Fail 'release manifest upload returned no S3 VersionId.' }
$tfvarsResult = & aws s3api put-object --bucket $Bucket --key $tfvarsKey --body $TerraformVariablesFile --metadata "sha256=$tfvarsSha" --output json 2>$null
if ($LASTEXITCODE -ne 0) { Fail 'versioned Terraform variables upload failed.' }
$tfvarsUpload = $tfvarsResult | ConvertFrom-Json
if ([string]::IsNullOrWhiteSpace([string]$tfvarsUpload.VersionId)) { Fail 'Terraform variables upload returned no S3 VersionId.' }
$overrides = @(
    "name=DEPLOY_ENVIRONMENT,value=staging,type=PLAINTEXT",
    "name=SOURCE_BUCKET,value=$Bucket,type=PLAINTEXT",
    "name=SOURCE_KEY,value=$sourceKey,type=PLAINTEXT",
    "name=SOURCE_VERSION_ID,value=$($sourceUpload.VersionId),type=PLAINTEXT",
    "name=EXPECTED_SHA256,value=$ExpectedSha256,type=PLAINTEXT",
    "name=RELEASE_MANIFEST_KEY,value=$manifestKey,type=PLAINTEXT",
    "name=RELEASE_MANIFEST_VERSION_ID,value=$($manifestUpload.VersionId),type=PLAINTEXT",
    "name=EXPECTED_MANIFEST_SHA256,value=$ExpectedManifestSha256,type=PLAINTEXT",
    "name=SOURCE_COMMIT,value=$SourceCommit,type=PLAINTEXT",
    "name=DEPLOYER_IMAGE_DIGEST,value=sha256:$DeployerImageDigest,type=PLAINTEXT",
    "name=TFVARS_OBJECT_KEY,value=$tfvarsKey,type=PLAINTEXT",
    "name=TFVARS_VERSION_ID,value=$($tfvarsUpload.VersionId),type=PLAINTEXT",
    "name=EXPECTED_TFVARS_SHA256,value=$tfvarsSha,type=PLAINTEXT"
)
$started = & aws codebuild start-build --project-name $ProjectName --source-version $sourceUpload.VersionId --environment-variables-override $overrides --output json 2>$null
if ($LASTEXITCODE -ne 0) { Fail 'CodeBuild launch failed.' }
$buildId = [string](($started | ConvertFrom-Json).build.id)
if ([string]::IsNullOrWhiteSpace($buildId)) { Fail 'CodeBuild launch returned no build ID.' }
$deadline = [datetime]::UtcNow.AddSeconds($TimeoutSeconds)
do {
    Start-Sleep -Seconds 10
    $current = & aws codebuild batch-get-builds --ids $buildId --output json 2>$null
    if ($LASTEXITCODE -ne 0) { Fail 'unable to retrieve CodeBuild status.' }
    $status = [string](($current | ConvertFrom-Json).builds[0].buildStatus)
    if ($status -in @('SUCCEEDED','FAILED','FAULT','STOPPED','TIMED_OUT')) {
        Write-Result $status $buildId
        if ($status -eq 'SUCCEEDED') { Write-Output 'Staging deployment build completed successfully.'; exit 0 }
        Fail "CodeBuild finished with status '$status'."
    }
} while ([datetime]::UtcNow -lt $deadline)
Write-Result 'TIMED_OUT' $buildId
Fail 'CodeBuild did not reach a terminal status before its approved timeout.'
