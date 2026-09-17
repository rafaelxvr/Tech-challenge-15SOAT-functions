[CmdletBinding()]
param()
Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
$repo = Split-Path -Parent $PSScriptRoot
& "$PSScriptRoot/source-package-contract.ps1"
& "$PSScriptRoot/workflow-context-contract.ps1"
& "$PSScriptRoot/cloud-window-tests.ps1"
& "$PSScriptRoot/release-guards-contract.ps1"
& "$PSScriptRoot/pipeline-contract.ps1"
& "$PSScriptRoot/prepare-staging-runtime-secrets.tests.ps1"
& "$PSScriptRoot/sync-staging-app-jwt-secret.tests.ps1"
# The pre-existing monitoring module is verified too, without changing its source.
foreach ($relative in @('infra/modules/functions', 'infra/modules/functions/runtime', 'infra/environments/staging', 'infra/environments/production')) {
    $root = Join-Path $repo $relative
    & terraform "-chdir=$root" fmt -check
    if ($LASTEXITCODE -ne 0) { throw "Formatting failed: $relative" }
    & terraform "-chdir=$root" init -backend=false -input=false -lockfile=readonly -no-color
    if ($LASTEXITCODE -ne 0) { throw "Provider initialization failed: $relative" }
    & terraform "-chdir=$root" validate -no-color
    if ($LASTEXITCODE -ne 0) { throw "Validation failed: $relative" }
    & terraform "-chdir=$root" test -no-color
    if ($LASTEXITCODE -ne 0) { throw "Mocked infrastructure tests failed: $relative" }
}
Write-Output 'PASS: source-only infrastructure verification; no real AWS plan/apply or deployment.'
