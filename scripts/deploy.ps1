[CmdletBinding()]
param(
    [Parameter(Mandatory)][ValidateSet('staging','production')][string]$Environment,
    [Parameter(Mandatory)][string]$ReleaseManifest,
    [Parameter(Mandatory)][ValidatePattern('^[a-f0-9]{64}$')][string]$ExpectedSourceSha256,
    [Parameter(Mandatory)][ValidatePattern('^[a-f0-9]{64}$')][string]$ExpectedManifestSha256,
    [Parameter(Mandatory)][ValidatePattern('^[a-f0-9]{64}$')][string]$ExpectedTerraformVariablesSha256,
    [Parameter(Mandatory)][ValidatePattern('^[a-f0-9]{40}$')][string]$SourceCommit,
    [Parameter(Mandatory)][ValidatePattern('^sha256:[a-f0-9]{64}$')][string]$ExpectedDeployerImageDigest,
    [Parameter(Mandatory)][string]$TerraformVariablesFile,
    [Parameter(Mandatory)][ValidatePattern('^[a-z0-9][a-z0-9.-]{1,61}[a-z0-9]$')][string]$TerraformBackendBucket,
    [Parameter(Mandatory)][ValidatePattern('^[a-z0-9][a-z0-9/_-]*\.tfstate$')][string]$TerraformBackendKey,
    [Parameter(Mandatory)][ValidatePattern('^[a-z0-9][a-z0-9/_-]*\.tfstate\.tflock$')][string]$TerraformBackendLockKey,
    [Parameter(Mandatory)][ValidatePattern('^[a-z]{2}-[a-z]+-\d+$')][string]$TerraformBackendRegion,
    [ValidatePattern('^[a-z0-9][a-z0-9.-]{1,61}[a-z0-9]$')][string]$StateBucket,
    [switch]$SharedFoundationMutation,
    [switch]$ApplyReviewedPlan,
    [switch]$DryRun
)
Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
function Fail([string]$Message) { throw "Deployment execution failed: $Message" }
function Require([object]$Object, [string]$Name) { $p = $Object.PSObject.Properties[$Name]; if ($null -eq $p -or $null -eq $p.Value -or ([string]$p.Value).Trim().Length -eq 0) { Fail "release manifest is missing '$Name'." }; return $p.Value }
function Hash([string]$Path) { (Get-FileHash -LiteralPath $Path -Algorithm SHA256).Hash.ToLowerInvariant() }
if ($Environment -cne 'staging') { Fail 'production deployment is disabled; the functions executor is staging-only.' }
if ($TerraformBackendRegion -cne 'us-east-1' -or $TerraformBackendKey -cne 'functions/staging.tfstate' -or $TerraformBackendLockKey -cne 'functions/staging.tfstate.tflock' -or $TerraformVariablesFile -cne '/tmp/oficina/functions_staging.tfvars.json') { Fail 'Unreviewed function state, lock, region, or trusted tfvars path.' }
if (-not $SharedFoundationMutation -or [string]::IsNullOrWhiteSpace($StateBucket)) { Fail 'staging execution requires the shared foundation lock and its reviewed state bucket.' }
if (-not (Test-Path -LiteralPath $ReleaseManifest -PathType Leaf) -or -not (Test-Path -LiteralPath $TerraformVariablesFile -PathType Leaf)) { Fail 'release manifest and Terraform variables file are required.' }
if ((Hash $ReleaseManifest) -cne $ExpectedManifestSha256 -or (Hash $TerraformVariablesFile) -cne $ExpectedTerraformVariablesSha256) { Fail 'release manifest or Terraform variables digest mismatch.' }
try { $manifest = Get-Content -LiteralPath $ReleaseManifest -Raw | ConvertFrom-Json } catch { Fail 'release manifest is not valid JSON.' }
if ($manifest.schemaVersion -ne 1 -or [string](Require $manifest 'environment') -cne 'staging' -or [string](Require $manifest 'sourceCommit') -cne $SourceCommit -or [string](Require $manifest 'artifactSha256') -cne $ExpectedSourceSha256 -or [string](Require $manifest 'deployerImageDigest') -cne $ExpectedDeployerImageDigest) { Fail 'release manifest does not bind the reviewed executor, source, or staging environment.' }
if ($DryRun) { Write-Output 'Deployment execution inputs validated; dry run did not run Terraform.'; exit 0 }
$repoRoot = Split-Path -Parent $PSScriptRoot
$root = Join-Path $repoRoot 'infra/environments/staging'
$plan = Join-Path ([System.IO.Path]::GetTempPath()) "oficina-functions-staging-$SourceCommit.tfplan"
$ownerToken = [guid]::NewGuid().ToString(); $locked = $false
try {
    & (Join-Path $PSScriptRoot 'deployment-lock.ps1') -Action Acquire -StateBucket $StateBucket -OwnerToken $ownerToken | Out-Null; $locked = $true
    $terraformChdir = "-chdir=$root"
    & terraform $terraformChdir init -input=false "-backend-config=bucket=$TerraformBackendBucket" "-backend-config=key=$TerraformBackendKey" "-backend-config=region=$TerraformBackendRegion" '-backend-config=use_lockfile=true'
    if ($LASTEXITCODE -ne 0) { Fail 'terraform init failed.' }
    & terraform $terraformChdir validate
    if ($LASTEXITCODE -ne 0) { Fail 'terraform validate failed.' }
    & terraform $terraformChdir plan -input=false -lock-timeout=5m "-var-file=$TerraformVariablesFile" "-out=$plan"
    if ($LASTEXITCODE -ne 0) { Fail 'terraform plan failed; apply was not attempted.' }
    if ($ApplyReviewedPlan) { & terraform $terraformChdir apply -input=false $plan; if ($LASTEXITCODE -ne 0) { Fail 'terraform apply of the reviewed plan failed.' }; Write-Output 'Reviewed staging Terraform plan applied.' }
    else { Write-Output 'Staging Terraform plan completed; apply was not requested.' }
}
finally { Remove-Item -LiteralPath $plan -Force -ErrorAction SilentlyContinue; if ($locked) { & (Join-Path $PSScriptRoot 'deployment-lock.ps1') -Action Release -StateBucket $StateBucket -OwnerToken $ownerToken | Out-Null } }
