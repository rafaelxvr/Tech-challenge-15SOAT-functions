[CmdletBinding()]
param()
Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
$repo = Split-Path -Parent $PSScriptRoot
$temp = Join-Path ([IO.Path]::GetTempPath()) ('oficina-functions-pipeline-' + [guid]::NewGuid())
New-Item -ItemType Directory -Path $temp | Out-Null
function Hash($path) { (Get-FileHash -LiteralPath $path -Algorithm SHA256).Hash.ToLowerInvariant() }
function Save($value, $path) { $value | ConvertTo-Json -Depth 10 | Set-Content -LiteralPath $path -NoNewline }
function Reject([scriptblock]$action, $message) { try { & $action | Out-Null } catch { return }; throw "Expected rejection: $message" }
function aws { throw 'Offline contract forbids AWS calls.' }
try {
    $bundle = Join-Path $temp 'bundle.zip'; 'immutable fixture' | Set-Content -LiteralPath $bundle -NoNewline
    $tfvars = Join-Path $temp 'functions_staging.tfvars.json'; '{"environment":"staging"}' | Set-Content -LiteralPath $tfvars -NoNewline
    $trustedTfvars = '/tmp/oficina/functions_staging.tfvars.json'
    New-Item -ItemType Directory -Path (Split-Path -Parent $trustedTfvars) -Force | Out-Null
    Copy-Item -LiteralPath $tfvars -Destination $trustedTfvars -Force
    $commit = 'a' * 40; $digest = Hash $bundle; $tfvarsDigest = Hash $tfvars
    $manifest = Join-Path $temp 'manifest.json'
    Save @{ schemaVersion=1; environment='staging'; sourceCommit=$commit; artifactSha256=$digest; deployerImageDigest=('sha256:' + ('b'*64)); contractVersion='phase3-v2'; migrationVersion='V8' } $manifest
    $window = Join-Path $temp 'window.json'
    Save @{ windowStartUtc=[datetime]::UtcNow.AddMinutes(-2).ToString('o'); windowEndUtc=[datetime]::UtcNow.AddMinutes(30).ToString('o'); recordedAtUtc=[datetime]::UtcNow.ToString('o'); accountEvidenceReference='offline-test'; projectAllowanceUsd=80; reserveUsd=20; currentEstimatedSpendUsd=0 } $window
    $launch = @{ Environment='staging'; SourceZip=$bundle; ExpectedSha256=$digest; ReleaseManifest=$manifest; ExpectedManifestSha256=(Hash $manifest); Bucket='oficina-artifacts-fixture'; SourcePrefix='releases/functions/staging'; ProjectName='oficina-phase3-oficina-functions-staging-deploy'; DeployerImageDigest=('b'*64); SourceCommit=$commit; CloudWindowEvidenceFile=$window; TerraformVariablesFile=$tfvars; StateBucket='oficina-state-fixture'; EventName='push'; BranchRef='refs/heads/develop' }
    $dry = & "$repo/scripts/start-deploy.ps1" @launch -DryRun
    if ($dry -cne 'Deployment launch request validated; dry run did not call AWS.') { throw 'Staging launcher dry run did not validate.' }
    $deploy = @{ Environment='staging'; ReleaseManifest=$manifest; ExpectedSourceSha256=$digest; ExpectedManifestSha256=(Hash $manifest); ExpectedTerraformVariablesSha256=$tfvarsDigest; SourceCommit=$commit; ExpectedDeployerImageDigest=('sha256:' + ('b'*64)); TerraformVariablesFile=$trustedTfvars; TerraformBackendBucket='oficina-state-fixture'; TerraformBackendKey='functions/staging.tfstate'; TerraformBackendLockKey='functions/staging.tfstate.tflock'; TerraformBackendRegion='us-east-1'; StateBucket='oficina-state-fixture'; SharedFoundationMutation=$true }
    if ((& "$repo/scripts/deploy.ps1" @deploy -DryRun) -cne 'Deployment execution inputs validated; dry run did not run Terraform.') { throw 'Staging executor dry run did not validate.' }
    Reject { & "$repo/scripts/start-deploy.ps1" @launch -Environment production -DryRun } 'production launcher must remain disabled'
    Reject { & "$repo/scripts/deploy.ps1" @deploy -ApplyReviewedPlan } 'apply without shared lock must reject'
    $bad = $launch.Clone(); $bad.ExpectedSha256 = 'c'*64; Reject { & "$repo/scripts/start-deploy.ps1" @bad -DryRun } 'tampered source'
    $bad = $deploy.Clone(); $bad.ExpectedTerraformVariablesSha256 = 'd'*64; Reject { & "$repo/scripts/deploy.ps1" @bad -DryRun } 'tampered tfvars'
    Write-Output 'PASS: staging immutable upload/CodeBuild contract, cloud window, exact state/lock, terminal path and production-disabled guards.'
}
finally { Remove-Item -LiteralPath $temp -Recurse -Force -ErrorAction SilentlyContinue; Remove-Item -LiteralPath $trustedTfvars -Force -ErrorAction SilentlyContinue }
