[CmdletBinding()]
param()
Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
$repo = Split-Path -Parent $PSScriptRoot
$temp = Join-Path ([IO.Path]::GetTempPath()) ('oficina-release-guards-' + [guid]::NewGuid())
New-Item -ItemType Directory -Path $temp | Out-Null
function Save($value, $path) { $value | ConvertTo-Json -Depth 10 | Set-Content -LiteralPath $path -NoNewline }
function Hash($path) { (Get-FileHash -LiteralPath $path -Algorithm SHA256).Hash.ToLowerInvariant() }
function Reject([scriptblock]$action) { try { & $action | Out-Null } catch { return }; throw 'Expected release input rejection.' }
try {
    $bundle = Join-Path $temp 'bundle.zip'; 'release' | Set-Content $bundle -NoNewline
    $tfvars = Join-Path $temp 'functions_staging.tfvars.json'; '{}' | Set-Content $tfvars -NoNewline
    $commit = 'a'*40; $digest = Hash $bundle; $manifestPath = Join-Path $temp 'manifest.json'
    Save @{schemaVersion=1;environment='staging';sourceCommit=$commit;artifactSha256=$digest;deployerImageDigest=('sha256:'+('b'*64));contractVersion='phase3-v2';migrationVersion='V8'} $manifestPath
    $window = Join-Path $temp 'window.json'; Save @{windowStartUtc=[datetime]::UtcNow.AddMinutes(-2).ToString('o');windowEndUtc=[datetime]::UtcNow.AddMinutes(30).ToString('o');recordedAtUtc=[datetime]::UtcNow.ToString('o');accountEvidenceReference='offline';projectAllowanceUsd=80;reserveUsd=20;currentEstimatedSpendUsd=0} $window
    $launch=@{Environment='staging';SourceZip=$bundle;ExpectedSha256=$digest;ReleaseManifest=$manifestPath;ExpectedManifestSha256=(Hash $manifestPath);Bucket='oficina-state-fixture';SourcePrefix='releases/functions/staging';ProjectName='oficina-phase3-oficina-functions-staging-deploy';DeployerImageDigest=('b'*64);SourceCommit=$commit;CloudWindowEvidenceFile=$window;TerraformVariablesFile=$tfvars;StateBucket='oficina-state-fixture';EventName='push';BranchRef='refs/heads/develop'}
    $evidence = Get-Content $window -Raw | ConvertFrom-Json; $evidence.windowEndUtc=[datetime]::UtcNow.AddMinutes(-1).ToString('o'); Save $evidence $window
    Reject { & "$repo/scripts/start-deploy.ps1" @launch -DryRun }
    Write-Output 'PASS: release branch, immutable digest, closed window, exact staging project and production-disabled contracts.'
}
finally { Remove-Item -LiteralPath $temp -Recurse -Force -ErrorAction SilentlyContinue }
