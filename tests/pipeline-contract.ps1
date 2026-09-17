[CmdletBinding()]
param()
Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
$repo = Split-Path -Parent $PSScriptRoot
$temp = Join-Path ([IO.Path]::GetTempPath()) ('oficina-functions-contract-' + [guid]::NewGuid())
New-Item -ItemType Directory -Path $temp | Out-Null
$script:checks = 0
function Assert($condition, $message) { $script:checks++; if (-not $condition) { throw $message } }
function Reject([scriptblock]$action, $message) { $script:checks++; try { & $action | Out-Null } catch { return }; throw "Expected rejection: $message" }
function aws { throw 'Offline test forbids AWS.' }
function terraform { throw 'Script contract must never invoke Terraform.' }
function Sha($path) { (Get-FileHash -LiteralPath $path -Algorithm SHA256).Hash.ToLowerInvariant() }
try {
    $source = (Get-ChildItem "$repo/infra/modules/functions/runtime" -Filter '*.tf' | ForEach-Object { Get-Content $_.FullName -Raw }) -join "`n"
    foreach ($forbidden in @('resource "aws_lambda_function_url"', 'resource "aws_lambda_provisioned_concurrency_config"', 'reserved_concurrent_executions', 'secret_string', 'secret_binary', 'data "terraform_remote_state"', 'resource "aws_apigatewayv2_api"', 'resource "aws_apigatewayv2_stage"')) { Assert (-not $source.Contains($forbidden)) "Unreviewed runtime feature: $forbidden" }
    $workflow = Get-Content "$repo/.github/workflows/ci.yml" -Raw
    foreach ($required in @('contents: read', 'cancel-in-progress: false', './mvnw -B verify', 'terraform_version: 1.15.8', './tests/verify-infrastructure.ps1')) { Assert ($workflow.Contains($required)) "Missing CI contract: $required" }
    Assert (-not $workflow.Contains('id-token: write') -and -not $workflow.Contains('configure-aws-credentials') -and -not $workflow.Contains('deploy-staging:')) 'Inert deployment adapters must not enable a live deployment job.'
    $bundle = Join-Path $temp 'bundle.zip'; 'offline artifact' | Set-Content $bundle -NoNewline
    $commit = 'a' * 40; $digest = Sha $bundle
    $window = Join-Path $temp 'window.json'
    $now = [datetime]::UtcNow
    @{ windowStartUtc = $now.AddMinutes(-2).ToString('o'); windowEndUtc = $now.AddMinutes(30).ToString('o'); recordedAtUtc = $now.ToString('o'); accountEvidenceReference = 'offline-test'; projectAllowanceUsd = 80; reserveUsd = 20; currentEstimatedSpendUsd = 0 } | ConvertTo-Json | Set-Content $window
    foreach ($environment in @('staging','production')) {
        $manifestPath = Join-Path $temp "$environment.json"
        @{ schemaVersion = 1; environment = $environment; sourceCommit = $commit; artifactSha256 = $digest; deployerImageDigest = ('sha256:' + ('b' * 64)); contractVersion = 'phase3-v2'; migrationVersion = 'V8'; runtimeArtifactDigest = ('sha256:' + ('c' * 64)); promotedFromStaging = ($environment -eq 'production'); stagingArtifactSha256 = $digest } | ConvertTo-Json | Set-Content $manifestPath -NoNewline
        $launch = @{ Environment = $environment; SourceZip = $bundle; ExpectedSha256 = $digest; ReleaseManifest = $manifestPath; ExpectedManifestSha256 = (Sha $manifestPath); SourceCommit = $commit; ProjectName = "oficina-phase3-oficina-functions-$environment-deploy"; SourcePrefix = "releases/functions/$environment"; CloudWindowEvidenceFile = $window; EventName = 'push'; BranchRef = $(if ($environment -eq 'staging') {'refs/heads/develop'}else{'refs/heads/main'}) }
        Assert ((& "$repo/scripts/start-deploy.ps1" @launch -DryRun) -ceq 'INPUTS_VALIDATED_DEPLOYMENT_DISABLED') 'Dry-run must report disabled deployment explicitly.'
        Reject { & "$repo/scripts/start-deploy.ps1" @launch } 'valid launcher cannot start live'
        $bad = $launch.Clone(); $bad.SourcePrefix = 'releases/app/staging'
        Reject { & "$repo/scripts/start-deploy.ps1" @bad -DryRun } 'wrong source prefix'
        $bad = $launch.Clone(); $bad.ProjectName = 'arbitrary-project'
        Reject { & "$repo/scripts/start-deploy.ps1" @bad -DryRun } 'wrong executor'
        $bad = $launch.Clone(); $bad.ExpectedSha256 = 'c' * 64
        Reject { & "$repo/scripts/start-deploy.ps1" @bad -DryRun } 'tampered artifact'
        $deploy = @{ Environment = $environment; ReleaseManifest = $manifestPath; ExpectedSourceSha256 = $digest; ExpectedManifestSha256 = (Sha $manifestPath); SourceCommit = $commit; ExpectedDeployerImageDigest = ('sha256:' + ('b' * 64)); TerraformVariablesFile = "/tmp/oficina/functions_$environment.tfvars.json"; TerraformBackendBucket = 'oficina-state-example'; TerraformBackendKey = "functions/$environment.tfstate"; TerraformBackendLockKey = "functions/$environment.tfstate.tflock"; TerraformBackendRegion = 'us-east-1' }
        Assert ((& "$repo/scripts/deploy.ps1" @deploy -DryRun) -ceq 'INPUTS_VALIDATED_DEPLOYMENT_DISABLED') 'Executor dry-run must validate exact path/state contract.'
        Reject { & "$repo/scripts/deploy.ps1" @deploy -ApplyReviewedPlan } 'apply remains disabled'
        $bad = $deploy.Clone(); $bad.TerraformBackendKey = 'database/staging.tfstate'
        Reject { & "$repo/scripts/deploy.ps1" @bad -DryRun } 'another owner state'
        $bad = $deploy.Clone(); $bad.TerraformVariablesFile = '/tmp/override.tfvars.json'
        Reject { & "$repo/scripts/deploy.ps1" @bad -DryRun } 'unreviewed tfvars'
    }
    $allowlist = Get-Content "$repo/contracts/outputs-allowlist.json" -Raw | ConvertFrom-Json
    $raw = @{}; foreach ($field in $allowlist.outputs.PSObject.Properties) { $raw[$field.Value] = @{ sensitive = $false; value = 'reference-fixture' } }
    $raw.customer_public_keys.value = @{ 'customer-fixture' = @{ kty = 'RSA'; alg = 'RS256'; use = 'sig'; e = 'AQAB'; n = ('A' * 342) } }
    $raw.password = @{ sensitive = $true; value = 'must-not-export' }
    $rawPath = Join-Path $temp 'outputs.json'; $outPath = Join-Path $temp 'filtered.json'
    $raw | ConvertTo-Json -Depth 6 | Set-Content $rawPath
    & "$repo/scripts/export-outputs.ps1" -Environment staging -SourceCommit $commit -TerraformOutputsJsonFile $rawPath -OutputFile $outPath
    Assert (-not (Get-Content $outPath -Raw).Contains('must-not-export')) 'Exporter must omit unknown/sensitive outputs.'
    $raw.customer_public_keys.value.'customer-fixture'.d = 'private-field-must-not-export'; $raw | ConvertTo-Json -Depth 8 | Set-Content $rawPath
    Reject { & "$repo/scripts/export-outputs.ps1" -Environment staging -SourceCommit $commit -TerraformOutputsJsonFile $rawPath -OutputFile $outPath } 'private JWK material'
    $raw.customer_public_keys.value.'customer-fixture'.Remove('d')
    $raw.authorizer_id.sensitive = $true; $raw | ConvertTo-Json -Depth 6 | Set-Content $rawPath
    Reject { & "$repo/scripts/export-outputs.ps1" -Environment staging -SourceCommit $commit -TerraformOutputsJsonFile $rawPath -OutputFile $outPath } 'sensitive allowlisted field'
    Write-Output "PASS: $script:checks source-only pipeline contracts; no AWS calls."
} finally {
    $resolved = [IO.Path]::GetFullPath($temp)
    if (-not $resolved.StartsWith([IO.Path]::GetFullPath([IO.Path]::GetTempPath()), [StringComparison]::OrdinalIgnoreCase) -or -not [IO.Path]::GetFileName($resolved).StartsWith('oficina-functions-contract-')) { throw 'Unsafe cleanup target.' }
    Remove-Item -LiteralPath $resolved -Recurse -Force
}
