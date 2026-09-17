[CmdletBinding()]
param([string]$ScriptPath = (Join-Path $PSScriptRoot '..\scripts\prepare-staging-runtime-secrets.ps1'))

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
$source = Get-Content -LiteralPath $ScriptPath -Raw

function Assert-Contains([string]$Needle, [string]$Message) {
    if (-not $source.Contains($Needle)) { throw "Secret-preparation contract failed: $Message" }
}

Assert-Contains "https://truststore.pki.rds.amazonaws.com/us-east-1/us-east-1-bundle.pem" 'regional RDS CA bundle is not pinned.'
Assert-Contains 'if ($size -ge 65536)' 'Secrets Manager size guard is missing.'
Assert-Contains '^[A-Za-z0-9_-]{1,64}$' 'reviewed key IDs are not bounded.'
Assert-Contains '$AllowReviewedRotation.IsPresent' 'explicit rotation guard is missing.'
Assert-Contains 'if ($keyMaterialWillBeWritten)' 'existing signing/trust material is not preserved.'
Assert-Contains 'Get-ExistingCustomerPublicJwk' 'existing RSA material is not revalidated.'
Assert-Contains 'Assert-PublicJwkMatches -Expected $reviewedMetadata.customer_public_jwk' 'reviewed JWK matching is missing.'
Assert-Contains 'if ($caWillBeWritten)' 'missing-only CA write path is missing.'
Assert-Contains 'ConfirmKeySynchronization' 'key synchronization acknowledgement is missing.'

Write-Output 'prepare-staging-runtime-secrets contract tests passed.'
