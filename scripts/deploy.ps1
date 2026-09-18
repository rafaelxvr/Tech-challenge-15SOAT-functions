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
    [string]$ProductionEnabled='', [string]$ProductionLauncherEnabled='', [string]$ProtectedEnvironment='',
    [string]$ProductionRoleArn='', [string]$ProductionInputsFile='', [string]$ExpectedProductionInputsSha256='',
    [string]$EventName=$env:GITHUB_EVENT_NAME, [string]$BranchRef=$env:GITHUB_REF,
    [switch]$ApplyReviewedPlan,
    [switch]$DryRun
)
Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
function Fail([string]$Message) { throw "Deployment execution failed: $Message" }
function Require([object]$Object, [string]$Name) { $p = $Object.PSObject.Properties[$Name]; if ($null -eq $p -or $null -eq $p.Value -or ([string]$p.Value).Trim().Length -eq 0) { Fail "release manifest is missing '$Name'." }; return $p.Value }
function Hash([string]$Path) { (Get-FileHash -LiteralPath $Path -Algorithm SHA256).Hash.ToLowerInvariant() }
if ($Environment -ceq 'production') {
    . (Join-Path $PSScriptRoot 'production-runtime-contract.ps1')
    $review=Read-FunctionsProductionRuntime -Enabled $ProductionEnabled -LauncherEnabled $ProductionLauncherEnabled -ProtectedEnvironment $ProtectedEnvironment `
        -RoleArn $ProductionRoleArn -InputsFile $ProductionInputsFile -ExpectedInputsSha256 $ExpectedProductionInputsSha256 `
        -SourceCommit $SourceCommit -EventName $EventName -BranchRef $BranchRef
    if ($TerraformBackendRegion -cne 'us-east-1' -or $TerraformBackendKey -cne 'functions/production.tfstate' -or
        $TerraformBackendLockKey -cne 'functions/production.tfstate.tflock' -or $TerraformVariablesFile -cne '/tmp/oficina/functions_production.tfvars.json' -or
        -not $SharedFoundationMutation -or $StateBucket -cne $review.Inputs.stateBucket -or $TerraformBackendBucket -cne $review.Inputs.stateBucket -or
        $ExpectedManifestSha256 -cne $review.ReleaseFile.Sha256 -or (Hash $ReleaseManifest) -cne $review.ReleaseFile.Sha256 -or
        $ExpectedSourceSha256 -cne $review.Source.Sha256 -or $ExpectedDeployerImageDigest -cne $review.Inputs.deployerImageDigest -or
        $ExpectedTerraformVariablesSha256 -cne $review.TerraformVariables.Sha256 -or (Hash $TerraformVariablesFile) -cne $review.TerraformVariables.Sha256) {
        Fail 'unreviewed production executor input binding.'
    }
    & (Join-Path $PSScriptRoot 'deploy-production.ps1') -Enabled $ProductionEnabled -LauncherEnabled $ProductionLauncherEnabled `
        -ProtectedEnvironment $ProtectedEnvironment -RoleArn $ProductionRoleArn -InputsFile $ProductionInputsFile -ExpectedInputsSha256 $ExpectedProductionInputsSha256 `
        -SourceCommit $SourceCommit -EventName $EventName -BranchRef $BranchRef -ApplyReviewedPlan:$ApplyReviewedPlan -DryRun:$DryRun
    return
}
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
