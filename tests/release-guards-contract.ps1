[CmdletBinding()]
param()
Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
$repo = Split-Path -Parent $PSScriptRoot
$owner = 'functions'
$temp = Join-Path ([IO.Path]::GetTempPath()) ('oficina-release-guards-' + [guid]::NewGuid())
New-Item -ItemType Directory -Path $temp | Out-Null
function aws { throw 'AWS is forbidden in this offline contract.' }
function Reject([scriptblock]$Action) { try { & $Action | Out-Null } catch { return }; throw 'Expected release input rejection.' }
function Save($object, $path) { $object | ConvertTo-Json -Depth 10 | Set-Content -LiteralPath $path -NoNewline }
function Sha($path) { (Get-FileHash -LiteralPath $path -Algorithm SHA256).Hash.ToLowerInvariant() }
try {
    $now = [datetime]::UtcNow
    $evidence = @{ windowStartUtc=$now.AddMinutes(-2).ToString('o'); windowEndUtc=$now.AddMinutes(30).ToString('o'); recordedAtUtc=$now.ToString('o'); accountEvidenceReference='offline-fixture'; projectAllowanceUsd=80; reserveUsd=20; currentEstimatedSpendUsd=0 }
    $window = Join-Path $temp 'window.json'; Save $evidence $window
    $bundle = Join-Path $temp 'bundle.zip'; 'offline' | Set-Content -LiteralPath $bundle
    $manifestPath = Join-Path $temp 'manifest.json'
    foreach ($environment in @('staging','production')) {
        $manifest = @{ schemaVersion=1; environment=$environment; sourceCommit=('a'*40); artifactSha256=(Sha $bundle); deployerImageDigest=('sha256:' + ('b'*64)); contractVersion='phase3-v2'; migrationVersion='V8'; runtimeArtifactDigest=('sha256:' + ('c'*64)); promotedFromStaging=($environment -eq 'production'); stagingArtifactSha256=(Sha $bundle) }
        Save $manifest $manifestPath
        $launch = @{Environment=$environment; SourceZip=$bundle; ExpectedSha256=(Sha $bundle); ReleaseManifest=$manifestPath; ExpectedManifestSha256=(Sha $manifestPath); SourceCommit=('a'*40); ProjectName="oficina-phase3-oficina-$owner-$environment-deploy"; SourcePrefix="releases/$owner/$environment"; CloudWindowEvidenceFile=$window; EventName='push'; BranchRef=$(if($environment -eq 'staging'){'refs/heads/develop'}else{'refs/heads/main'})}
        if ((& "$repo/scripts/start-deploy.ps1" @launch -DryRun) -cne 'INPUTS_VALIDATED_DEPLOYMENT_DISABLED') { throw 'Dry run must remain explicitly disabled.' }
        Reject { & "$repo/scripts/start-deploy.ps1" @launch }
        $deploy=@{Environment=$environment; ReleaseManifest=$manifestPath; ExpectedSourceSha256=(Sha $bundle); ExpectedManifestSha256=(Sha $manifestPath); SourceCommit=('a'*40); ExpectedDeployerImageDigest=('sha256:' + ('b'*64)); TerraformVariablesFile="/tmp/oficina/${owner}_$environment.tfvars.json"; TerraformBackendBucket='oficina-state-fixture'; TerraformBackendKey="$owner/$environment.tfstate"; TerraformBackendLockKey="$owner/$environment.tfstate.tflock"; TerraformBackendRegion='us-east-1'}
        if ((& "$repo/scripts/deploy.ps1" @deploy -DryRun) -cne 'INPUTS_VALIDATED_DEPLOYMENT_DISABLED') { throw 'Executor dry-run must not report deployment success.' }
        Reject { & "$repo/scripts/deploy.ps1" @deploy -ApplyReviewedPlan }
        foreach ($entry in @(@('EventName','pull_request'),@('BranchRef','refs/heads/master'),@('ExpectedSha256',('d'*64)),@('ProjectName','wrong-project'),@('SourcePrefix','releases/other/staging'))) {
            $bad=$launch.Clone(); $bad[$entry[0]]=$entry[1]; Reject { & "$repo/scripts/start-deploy.ps1" @bad -DryRun }
        }
        $evidence.windowEndUtc=$now.AddMinutes(-1).ToString('o'); Save $evidence $window
        Reject { & "$repo/scripts/start-deploy.ps1" @launch -DryRun }
        $evidence.windowEndUtc=$now.AddMinutes(30).ToString('o'); Save $evidence $window
        $manifest.runtimeArtifactDigest='latest'; Save $manifest $manifestPath; $launch.ExpectedManifestSha256=Sha $manifestPath
        Reject { & "$repo/scripts/start-deploy.ps1" @launch -DryRun }
        $manifest.runtimeArtifactDigest='sha256:' + ('c'*64)
        if($environment -eq 'production') {
            $manifest.stagingArtifactSha256='d'*64; Save $manifest $manifestPath; $launch.ExpectedManifestSha256=Sha $manifestPath
            Reject { & "$repo/scripts/start-deploy.ps1" @launch -DryRun }
        }
    }
    $lock = "$repo/scripts/deployment-lock.ps1"
    $first=[guid]::NewGuid().ToString(); $second=[guid]::NewGuid().ToString()
    $lockInputs=@{StateBucket='oficina-state-fixture'; Offline=$true; OfflineDirectory="$temp/locks"}
    & $lock -Action Acquire -OwnerToken $first @lockInputs | Out-Null
    Reject { & $lock -Action Acquire -OwnerToken $second @lockInputs }
    Reject { & $lock -Action Release -OwnerToken $second @lockInputs }
    & $lock -Action Release -OwnerToken $first @lockInputs | Out-Null
    Write-Output 'PASS: release branch, immutable source/runtime digest, closed window, staging promotion and non-stealable lock contracts; live deployment disabled.'
}
finally {
    $resolved=[IO.Path]::GetFullPath($temp)
    if(-not $resolved.StartsWith([IO.Path]::GetFullPath([IO.Path]::GetTempPath()),[StringComparison]::OrdinalIgnoreCase) -or -not [IO.Path]::GetFileName($resolved).StartsWith('oficina-release-guards-')) { throw 'Unsafe test cleanup.' }
    Remove-Item -LiteralPath $resolved -Recurse -Force
}
