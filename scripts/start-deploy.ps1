[CmdletBinding()]
param(
    [Parameter(Mandatory)][ValidateSet('staging','production')][string]$Environment,
    [Parameter(Mandatory)][string]$SourceZip,
    [Parameter(Mandatory)][ValidatePattern('^[a-f0-9]{64}$')][string]$ExpectedSha256,
    [Parameter(Mandatory)][string]$ReleaseManifest,
    [Parameter(Mandatory)][ValidatePattern('^[a-f0-9]{64}$')][string]$ExpectedManifestSha256,
    [Parameter(Mandatory)][ValidatePattern('^[a-f0-9]{40}$')][string]$SourceCommit,
    [Parameter(Mandatory)][string]$ProjectName,
    [Parameter(Mandatory)][string]$SourcePrefix,
    [switch]$DryRun
)
Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
if ($SourcePrefix -cne "releases/functions/$Environment" -or $ProjectName -cne "oficina-phase3-oficina-functions-$Environment-deploy") { throw 'Unreviewed function launcher target.' }
if ((Get-FileHash -LiteralPath $SourceZip -Algorithm SHA256).Hash.ToLowerInvariant() -cne $ExpectedSha256 -or (Get-FileHash -LiteralPath $ReleaseManifest -Algorithm SHA256).Hash.ToLowerInvariant() -cne $ExpectedManifestSha256) { throw 'Artifact or manifest digest mismatch.' }
$manifest = Get-Content -LiteralPath $ReleaseManifest -Raw | ConvertFrom-Json
if ($manifest.schemaVersion -ne 1 -or $manifest.environment -cne $Environment -or $manifest.sourceCommit -cne $SourceCommit -or $manifest.artifactSha256 -cne $ExpectedSha256) { throw 'Manifest does not bind the reviewed source/environment.' }
if ($DryRun) { Write-Output 'INPUTS_VALIDATED_DEPLOYMENT_DISABLED'; return }
throw 'FUNCTION_DEPLOYMENT_DISABLED: reviewed window, versioned artifact promotion, ownership handoff and platform executor wiring are not installed. No upload or CodeBuild start was attempted.'
