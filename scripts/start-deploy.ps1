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
    [Parameter(Mandatory)][string]$CloudWindowEvidenceFile,
    [string]$EventName = $env:GITHUB_EVENT_NAME,
    [string]$BranchRef = $env:GITHUB_REF,
    [switch]$DryRun
)
Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
if ($SourcePrefix -cne "releases/functions/$Environment" -or $ProjectName -cne "oficina-phase3-oficina-functions-$Environment-deploy") { throw 'Unreviewed function launcher target.' }
if ((Get-FileHash -LiteralPath $SourceZip -Algorithm SHA256).Hash.ToLowerInvariant() -cne $ExpectedSha256 -or (Get-FileHash -LiteralPath $ReleaseManifest -Algorithm SHA256).Hash.ToLowerInvariant() -cne $ExpectedManifestSha256) { throw 'Artifact or manifest digest mismatch.' }
$manifest = Get-Content -LiteralPath $ReleaseManifest -Raw | ConvertFrom-Json
if ($manifest.schemaVersion -ne 1 -or $manifest.environment -cne $Environment -or $manifest.sourceCommit -cne $SourceCommit -or $manifest.artifactSha256 -cne $ExpectedSha256) { throw 'Manifest does not bind the reviewed source/environment.' }
& (Join-Path $PSScriptRoot 'check-workflow-context.ps1') -Environment $Environment -EventName $EventName -BranchRef $BranchRef | Out-Null
& (Join-Path $PSScriptRoot 'check-cloud-window.ps1') -EvidenceFile $CloudWindowEvidenceFile -Environment $Environment | Out-Null
if ($manifest.contractVersion -cne 'phase3-v2' -or [string]::IsNullOrWhiteSpace($manifest.migrationVersion) -or $manifest.runtimeArtifactDigest -cnotmatch '\Asha256:[a-f0-9]{64}\z') { throw 'Release must bind contract, migration and runtime artifact digest.' }
if ($Environment -ceq 'production' -and ($manifest.promotedFromStaging -ne $true -or $manifest.stagingArtifactSha256 -cne $ExpectedSha256)) { throw 'Production requires the exact staging-tested source artifact.' }
if ($DryRun) { Write-Output 'INPUTS_VALIDATED_DEPLOYMENT_DISABLED'; return }
throw 'FUNCTION_DEPLOYMENT_DISABLED: cloud activation, versioned artifact promotion, ownership handoff and platform executor wiring are not installed. No upload or CodeBuild start was attempted.'
