[CmdletBinding()]
param(
    [string]$Enabled='', [string]$LauncherEnabled='', [string]$ProtectedEnvironment='',
    [string]$RoleArn='', [string]$InputsFile='', [string]$ExpectedInputsSha256='', [string]$SourceCommit='',
    [string]$EventName=$env:GITHUB_EVENT_NAME, [string]$BranchRef=$env:GITHUB_REF,
    [switch]$ApplyReviewedPlan, [switch]$DryRun
)
Set-StrictMode -Version Latest
$ErrorActionPreference='Stop'
. (Join-Path $PSScriptRoot 'production-runtime-contract.ps1')
$review=Read-FunctionsProductionRuntime -Enabled $Enabled -LauncherEnabled $LauncherEnabled -ProtectedEnvironment $ProtectedEnvironment `
    -RoleArn $RoleArn -InputsFile $InputsFile -ExpectedInputsSha256 $ExpectedInputsSha256 `
    -SourceCommit $SourceCommit -EventName $EventName -BranchRef $BranchRef
$root=Join-Path (Split-Path -Parent $PSScriptRoot) 'infra/environments/production'
foreach($file in @('main.tf','variables.tf','versions.tf','backend.tf')) {
    if (-not (Test-Path -LiteralPath (Join-Path $root $file) -PathType Leaf)) { throw 'FUNCTIONS_PRODUCTION_TERRAFORM_ROOT_MISSING' }
}
if ($DryRun -or -not $ApplyReviewedPlan -or $LauncherEnabled -cne 'true') {
    Write-Output 'PRODUCTION_RUNTIME_VALIDATED_DEPLOYMENT_DISABLED'
    return
}
function Assert-CurrentReview {
    # Revalidate exact reviewed bytes, source/receipt bindings and window before
    # every external step; a successful plan does not extend cost authorization.
    $null=Read-FunctionsProductionRuntime -Enabled $Enabled -LauncherEnabled $LauncherEnabled -ProtectedEnvironment $ProtectedEnvironment `
        -RoleArn $RoleArn -InputsFile $InputsFile -ExpectedInputsSha256 $ExpectedInputsSha256 `
        -SourceCommit $SourceCommit -EventName $EventName -BranchRef $BranchRef
}
function Invoke-ProductionTerraform([string[]]$Arguments) {
    Assert-CurrentReview
    $global:LASTEXITCODE=0
    & terraform "-chdir=$root" @Arguments *> $null
    if ($LASTEXITCODE -ne 0) { throw "FUNCTIONS_PRODUCTION_TERRAFORM_FAILED: $($Arguments[0]); inspect restricted executor evidence." }
}
$owner=[guid]::NewGuid().ToString(); $locked=$false
$plan=Join-Path ([IO.Path]::GetTempPath()) "oficina-functions-production-$owner.tfplan"
try {
    Assert-CurrentReview
    & (Join-Path $PSScriptRoot 'deployment-lock.ps1') -Action Acquire -StateBucket $review.Inputs.stateBucket -OwnerToken $owner | Out-Null
    $locked=$true
    Invoke-ProductionTerraform @('init','-input=false',"-backend-config=bucket=$($review.Inputs.stateBucket)",'-backend-config=key=functions/production.tfstate','-backend-config=region=us-east-1','-backend-config=use_lockfile=true','-lockfile=readonly')
    Invoke-ProductionTerraform @('validate')
    Invoke-ProductionTerraform @('plan','-input=false','-lock-timeout=5m',"-var-file=$($review.TerraformVariables.Path)","-out=$plan")
    if (-not (Test-Path -LiteralPath $plan -PathType Leaf)) { throw 'FUNCTIONS_PRODUCTION_PLAN_MISSING' }
    Invoke-ProductionTerraform @('apply','-input=false',$plan)
    Write-Output 'PRODUCTION_TERRAFORM_APPLIED: runtime health and promotion acceptance remain separate.'
}
finally {
    Remove-Item -LiteralPath $plan -Force -ErrorAction SilentlyContinue
    if ($locked) { & (Join-Path $PSScriptRoot 'deployment-lock.ps1') -Action Release -StateBucket $review.Inputs.stateBucket -OwnerToken $owner | Out-Null }
}
