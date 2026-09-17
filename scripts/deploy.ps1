[CmdletBinding()]
param(
    [Parameter(Mandatory)][ValidateSet('staging','production')][string]$Environment,
    [Parameter(Mandatory)][string]$ReleaseManifest,
    [Parameter(Mandatory)][ValidatePattern('^[a-f0-9]{64}$')][string]$ExpectedSourceSha256,
    [Parameter(Mandatory)][ValidatePattern('^[a-f0-9]{64}$')][string]$ExpectedManifestSha256,
    [Parameter(Mandatory)][ValidatePattern('^[a-f0-9]{40}$')][string]$SourceCommit,
    [Parameter(Mandatory)][ValidatePattern('^sha256:[a-f0-9]{64}$')][string]$ExpectedDeployerImageDigest,
    [Parameter(Mandatory)][string]$TerraformVariablesFile,
    [Parameter(Mandatory)][ValidatePattern('^[a-z0-9][a-z0-9.-]{1,61}[a-z0-9]$')][string]$TerraformBackendBucket,
    [Parameter(Mandatory)][string]$TerraformBackendKey,
    [Parameter(Mandatory)][string]$TerraformBackendLockKey,
    [Parameter(Mandatory)][string]$TerraformBackendRegion,
    [switch]$ApplyReviewedPlan,
    [switch]$DryRun
)
Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
if ($TerraformBackendRegion -cne 'us-east-1' -or $TerraformBackendKey -cne "functions/$Environment.tfstate" -or $TerraformBackendLockKey -cne "$TerraformBackendKey.tflock" -or $TerraformVariablesFile -cne "/tmp/oficina/functions_$Environment.tfvars.json") { throw 'Unreviewed function state, lock, region or trusted tfvars path.' }
if ((Get-FileHash -LiteralPath $ReleaseManifest -Algorithm SHA256).Hash.ToLowerInvariant() -cne $ExpectedManifestSha256) { throw 'Manifest digest mismatch.' }
$manifest = Get-Content -LiteralPath $ReleaseManifest -Raw | ConvertFrom-Json
if ($manifest.schemaVersion -ne 1 -or $manifest.environment -cne $Environment -or $manifest.sourceCommit -cne $SourceCommit -or $manifest.artifactSha256 -cne $ExpectedSourceSha256 -or $manifest.deployerImageDigest -cne $ExpectedDeployerImageDigest) { throw 'Manifest does not bind the reviewed executor/source/environment.' }
if ($DryRun -and -not $ApplyReviewedPlan) { Write-Output 'INPUTS_VALIDATED_DEPLOYMENT_DISABLED'; return }
throw 'FUNCTION_DEPLOYMENT_DISABLED: ownership transfer, release promotion, shared lock and cloud-window guards require separate reviewed activation. No Terraform operation was attempted.'
