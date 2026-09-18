[CmdletBinding()]
param()
Set-StrictMode -Version Latest
$ErrorActionPreference='Stop'
$repo=Split-Path -Parent $PSScriptRoot
$validator=Join-Path $repo 'scripts/check-production-promotion.ps1'
$workflow=Get-Content (Join-Path $repo '.github/workflows/ci.yml') -Raw
$parts=$workflow -split '(?m)^  production-contract:\s*$',2
if($parts.Count -ne 2){throw 'Production contract job is missing.'}
$production=$parts[1]
$staging=($parts[0] -split '(?m)^  deploy-staging:\s*$',2)[1]
$gate="if: github.event_name == 'push' && github.ref == 'refs/heads/main' && vars.FUNCTIONS_PRODUCTION_DEPLOYMENT_ENABLED == 'true'"
foreach($text in @($gate,'needs: verify','name: production','./scripts/check-production-promotion.ps1','uses: actions/download-artifact@v4','repository: ${{ github.repository }}','artifact-ids: ${{ vars.FUNCTIONS_PRODUCTION_INPUTS_ARTIFACT_ID }}')) {
    if(-not $production.Contains($text)){throw "Missing production contract: $text"}
}
foreach($name in @('FUNCTIONS_PRODUCTION_DEPLOYMENT_ENABLED','FUNCTIONS_PRODUCTION_LAUNCHER_ENABLED','FUNCTIONS_PRODUCTION_ROLE_ARN','FUNCTIONS_PRODUCTION_INPUTS_RUN_ID','FUNCTIONS_PRODUCTION_INPUTS_ARTIFACT_ID','FUNCTIONS_PRODUCTION_INPUTS_SHA256')) {
    if(-not $production.Contains($name+': ${{ vars.'+$name+' }}')){throw "Missing protected variable: $name"}
}
if($production -match 'id-token:|configure-aws|start-deploy|secrets\.|refs/heads/develop'){throw 'Production validation must not acquire cloud identity or launch.'}
if(-not $production.Contains("if: vars.FUNCTIONS_PRODUCTION_LAUNCHER_ENABLED == 'true'") -or
    -not $production.Contains('./scripts/deploy-production.ps1') -or $production -match '-ApplyReviewedPlan|LauncherEnabled\s+true') {
    throw 'Production adapter preflight must remain explicitly gated without apply.'
}
if(-not $staging.Contains("github.ref == 'refs/heads/develop'") -or $staging -match 'production|refs/heads/main'){throw 'Existing develop staging mapping changed.'}
$script:calls=0
function aws {$script:calls++;throw 'Forbidden AWS call'}
function terraform {$script:calls++;throw 'Forbidden Terraform call'}
$script:count=0
$temp=Join-Path ([IO.Path]::GetTempPath()) ('oficina-functions-production-'+[guid]::NewGuid())
New-Item -ItemType Directory -Path $temp | Out-Null
function Save($v,$name){[IO.File]::WriteAllText((Join-Path $temp $name),($v|ConvertTo-Json -Depth 20),[Text.UTF8Encoding]::new($false))}
function Hash($name){(Get-FileHash (Join-Path $temp $name) -Algorithm SHA256).Hash.ToLowerInvariant()}
function Ref($name){@{path=$name;sha256=(Hash $name)}}
function Reset-Fixture {
    $script:commit='a'*40;$script:role='arn:aws:iam::123456789012:role/oficina-functions-production-launcher'
    Save @{syntheticSource=$commit} 'source.zip'
    Save @{windowStartUtc=[datetimeoffset]::UtcNow.AddMinutes(-5).ToString('o');windowEndUtc=[datetimeoffset]::UtcNow.AddMinutes(30).ToString('o');recordedAtUtc=[datetimeoffset]::UtcNow.ToString('o');accountEvidenceReference='synthetic-fixture';projectAllowanceUsd=10;reserveUsd=1;currentEstimatedSpendUsd=0} 'window.json'
    $script:config=@{environment='production';account_id='123456789012';aws_region='us-east-1';lambda_artifact=@{s3_bucket='artifact-fixture';s3_key='reviewed-functions.jar';s3_object_version='jar-v1';sha256_hex=('b'*64);sha256_base64=[Convert]::ToBase64String([Convert]::FromHexString(('b'*64)))}}
    $script:release=@{schemaVersion=1;environment='production';sourceCommit=$commit;artifactSha256=(Hash 'source.zip');stagingArtifactSha256=(Hash 'source.zip');promotedFromStaging=$true;deployerImageDigest=('sha256:'+('c'*64));contractVersion='phase3-v2';migrationVersion='V8';cloudWindowEvidenceSha256=(Hash 'window.json')}
    $script:receipt=@{schemaVersion=1;environment='staging';sourceCommit=$commit;artifactSha256=(Hash 'source.zip');status='SUCCEEDED';buildId='oficina-phase3-oficina-functions-staging-deploy:00000000-0000-0000-0000-000000000001';recordedAtUtc=[datetimeoffset]::UtcNow.AddMinutes(-1).ToString('o')}
    $script:inputs=@{schemaVersion=1;environment='production';sourceCommit=$commit;accountId='123456789012';roleArn=$role;artifactBucket='artifact-fixture';stateBucket='state-fixture';projectName='oficina-phase3-oficina-functions-production-deploy';sourcePrefix='releases/functions/production';deployerImageDigest=$release.deployerImageDigest;sourceArchive=(Ref 'source.zip');cloudWindowEvidence=(Ref 'window.json')}
}
function Seal {
    Save $config 'config.json';$release.terraformVariablesSha256=Hash 'config.json'
    Save $release 'release.json';Save $receipt 'receipt.json'
    $inputs.terraformVariables=Ref 'config.json';$inputs.releaseManifest=Ref 'release.json';$inputs.stagingPromotion=Ref 'receipt.json'
    Save $inputs 'production-inputs.json'
    $script:argsMap=@{Enabled='true';RoleArn=$role;InputsFile=(Join-Path $temp 'production-inputs.json');ExpectedInputsSha256=(Hash 'production-inputs.json');SourceCommit=$commit;EventName='push';BranchRef='refs/heads/main'}
}
function Reject([scriptblock]$action,[string]$message){
    try{& $action|Out-Null}catch{if($_.Exception.Message -notlike "*$message*"){throw "Expected $message; got $($_.Exception.Message)"};$script:count++;return}
    throw "Expected rejection: $message"
}
try {
    Reset-Fixture;Seal
    if((& $validator @argsMap) -cne 'PRODUCTION_CONTRACT_VALIDATED_DEPLOYMENT_DISABLED'){throw 'Valid receipt must not enable deployment.'};$script:count++
    foreach($gateValue in @('','false','TRUE')){Reset-Fixture;Seal;$argsMap.Enabled=$gateValue;$argsMap.InputsFile='missing';Reject {& $validator @argsMap} 'FUNCTIONS_PRODUCTION_GATE_DISABLED'}
    foreach($branch in @('refs/heads/develop','refs/heads/feature','refs/tags/main')){Reset-Fixture;Seal;$argsMap.BranchRef=$branch;Reject {& $validator @argsMap} 'Deployment context'}
    foreach($event in @('pull_request','workflow_dispatch','schedule')){Reset-Fixture;Seal;$argsMap.EventName=$event;Reject {& $validator @argsMap} 'Deployment context'}
    foreach($roleValue in @('','arn:aws:iam::123456789012:role/functions-staging-launcher','arn:aws:iam::999999999999:role/functions-production-launcher')){Reset-Fixture;Seal;$argsMap.RoleArn=$roleValue;Reject {& $validator @argsMap} 'FUNCTIONS_PRODUCTION_ROLE_MISMATCH'}
    Reset-Fixture;Seal;$argsMap.ExpectedInputsSha256='';Reject {& $validator @argsMap} 'FUNCTIONS_PRODUCTION_INPUT_HASH_MISMATCH'
    Reset-Fixture;Seal;Remove-Item -LiteralPath (Join-Path $temp 'receipt.json');Reject {& $validator @argsMap} 'FUNCTIONS_PRODUCTION_FILE_HASH_MISMATCH'
    Reset-Fixture;Seal;[IO.File]::AppendAllText((Join-Path $temp 'receipt.json'),' ');Reject {& $validator @argsMap} 'FUNCTIONS_PRODUCTION_FILE_HASH_MISMATCH'
    Reset-Fixture;Seal;$inputs.Remove('stagingPromotion');Save $inputs 'production-inputs.json';$argsMap.ExpectedInputsSha256=Hash 'production-inputs.json';Reject {& $validator @argsMap} 'FUNCTIONS_PRODUCTION_OBJECT_REQUIRED'
    foreach($field in @('environment','sourceCommit','artifactSha256','status','buildId')){Reset-Fixture;$receipt[$field]='unreviewed';Seal;Reject {& $validator @argsMap} 'FUNCTIONS_STAGING_PROMOTION_MISMATCH'}
    foreach($field in @('sourceCommit','status','buildId')) {
        foreach($invalid in @(@(),@('value'),$null)){Reset-Fixture;$receipt[$field]=$invalid;Seal;Reject {& $validator @argsMap} 'FUNCTIONS_PRODUCTION_STRING_REQUIRED'}
        Reset-Fixture;$receipt.Remove($field);Seal;Reject {& $validator @argsMap} 'FUNCTIONS_PRODUCTION_STRING_REQUIRED'
    }
    Reset-Fixture;$receipt.status='DRY_RUN_VALIDATED';Seal;Reject {& $validator @argsMap} 'FUNCTIONS_STAGING_PROMOTION_MISMATCH'
    Reset-Fixture;$receipt.recordedAtUtc=[datetimeoffset]::UtcNow.AddHours(1).ToString('o');Seal;Reject {& $validator @argsMap} 'FUNCTIONS_STAGING_PROMOTION_TIME_INVALID'
    Reset-Fixture;$release.promotedFromStaging='true';Seal;Reject {& $validator @argsMap} 'FUNCTIONS_PRODUCTION_PROMOTION_REQUIRED'
    Reset-Fixture;$release.sourceCommit='d'*40;Seal;Reject {& $validator @argsMap} 'FUNCTIONS_PRODUCTION_BINDING_MISMATCH'
    Reset-Fixture;$config.environment='staging';Seal;Reject {& $validator @argsMap} 'FUNCTIONS_PRODUCTION_CONFIG_MISMATCH'
    Reset-Fixture;$config.lambda_artifact.s3_object_version='null';Seal;Reject {& $validator @argsMap} 'FUNCTIONS_PRODUCTION_STRING_REQUIRED'
    Reset-Fixture;$config.lambda_artifact.sha256_base64='different';Seal;Reject {& $validator @argsMap} 'FUNCTIONS_PRODUCTION_LAMBDA_DIGEST_INVALID'
    Reset-Fixture;Seal;[IO.File]::AppendAllText((Join-Path $temp 'config.json'),' ');Reject {& $validator @argsMap} 'FUNCTIONS_PRODUCTION_FILE_HASH_MISMATCH'
    Reset-Fixture;Seal;$argsMap.LauncherEnabled='true'
    if((& $validator @argsMap) -cne 'PRODUCTION_CONTRACT_VALIDATED_DEPLOYMENT_DISABLED'){throw 'Launcher gate alone cannot execute.'};$script:count++
    $argsMap.LauncherEnabled='TRUE';Reject {& $validator @argsMap} 'FUNCTIONS_PRODUCTION_LAUNCHER_GATE_INVALID'
    Reset-Fixture;Save @{windowStartUtc=[datetimeoffset]::UtcNow.AddHours(-2).ToString('o');windowEndUtc=[datetimeoffset]::UtcNow.AddHours(-1).ToString('o');recordedAtUtc=[datetimeoffset]::UtcNow.AddHours(-2).ToString('o');accountEvidenceReference='fixture';projectAllowanceUsd=10;reserveUsd=1;currentEstimatedSpendUsd=0} 'window.json';$inputs.cloudWindowEvidence=Ref 'window.json';$release.cloudWindowEvidenceSha256=Hash 'window.json';Seal;Reject {& $validator @argsMap} 'window is closed'
    $code=Get-Content $validator -Raw
    if($calls -ne 0 -or $code -match 'start-deploy\.ps1|deploy\.ps1|Invoke-Expression|Start-Process|&\s+(aws|terraform)'){throw 'Production validator must never invoke cloud operations or a launcher.'}
    $global:functionsRuntimeFixture=@{Calls=[Collections.Generic.List[string]]::new();Locked=$false;Owner='';Failure='';Temp=$temp}
    function aws {
        $f=$global:functionsRuntimeFixture;$a=@($args);$f.Calls.Add('aws '+($a -join ' '));$global:LASTEXITCODE=0
        switch ($a[1]) {
            'put-object' {
                $key=$a[$a.IndexOf('--key')+1]
                if ($key -ceq 'deployment-locks/shared-foundation.json') {
                    if($f.Failure -ceq 'lock'){$global:LASTEXITCODE=1;return 'restricted-canary'}
                    $f.Owner=(Get-Content $a[$a.IndexOf('--body')+1] -Raw|ConvertFrom-Json).ownerToken;$f.Locked=$true
                }
                return '{"VersionId":"fixture-version"}'
            }
            'head-object' {return (@{Metadata=@{owner=$f.Owner};ETag='"owner-etag"'}|ConvertTo-Json)}
            'delete-object' {if($a[$a.IndexOf('--if-match')+1] -cne '"owner-etag"'){throw 'Missing owner ETag'};$f.Locked=$false;return '{}'}
            'start-build' {return '{"build":{"id":"oficina-phase3-oficina-functions-staging-deploy:00000000-0000-0000-0000-000000000001"}}'}
            'batch-get-builds' {return '{"builds":[{"buildStatus":"SUCCEEDED"}]}'}
            default {throw 'Unexpected mock AWS command'}
        }
    }
    function Start-Sleep {param([int]$Seconds)}
    function terraform {
        $f=$global:functionsRuntimeFixture;$a=@($args);$f.Calls.Add('terraform '+($a -join ' '));$global:LASTEXITCODE=0
        if(-not $f.Locked -or $a[0] -notlike '*infra*environments*production'){throw 'Production must lock its real root'}
        $root=$a[0].Substring('-chdir='.Length)
        if(-not (Test-Path (Join-Path $root 'main.tf'))){throw 'Missing actual root'}
        if($f.Failure -ceq $a[1]){$global:LASTEXITCODE=1;return 'restricted-canary'}
        if($a[1] -ceq 'plan') {
            if($f.Failure -cne 'missing-plan') {[IO.File]::WriteAllText(($a|Where-Object {$_ -like '-out=*'}).Substring(5),'reviewed plan')}
            if($f.Failure -ceq 'window'){[IO.File]::AppendAllText((Join-Path $f.Temp 'window.json'),' ')}
        }
        return 'restricted-canary'
    }
    function Reset-Runtime {
        Reset-Fixture
        $config.ownership_handoff_reviewed=$true
        $receipt.runtimeBinding=@{lambda_artifact=$config.lambda_artifact.Clone();deployerImageDigest=$release.deployerImageDigest;sourceVersionId='source-v1';releaseManifestVersionId='manifest-v1';terraformVariablesVersionId='tfvars-v1';releaseManifestSha256=('d'*64);terraformVariablesSha256=('e'*64)}
        $global:functionsRuntimeFixture.Calls.Clear();$global:functionsRuntimeFixture.Failure='';$global:functionsRuntimeFixture.Locked=$false
    }
    function Invoke-Runtime([string]$Launcher='true',[switch]$Apply,[switch]$Dry) {
        & "$repo/scripts/deploy-production.ps1" @argsMap -ProtectedEnvironment production -LauncherEnabled $Launcher -ApplyReviewedPlan:$Apply -DryRun:$Dry
    }
    function Assert-NoCalls {
        if($global:functionsRuntimeFixture.Calls.Count -ne 0){throw 'Invalid or disabled runtime reached an external command'}
        $script:count++
    }
    foreach($gateValue in @('','false','true')) {
        Reset-Runtime;Seal
        if((Invoke-Runtime -Launcher $gateValue) -cne 'PRODUCTION_RUNTIME_VALIDATED_DEPLOYMENT_DISABLED'){throw 'Missing apply must remain disabled'}
        Assert-NoCalls
        if($gateValue -cne 'true') {Invoke-Runtime -Launcher $gateValue -Apply|Out-Null;Assert-NoCalls}
    }
    Reset-Runtime;Seal;Invoke-Runtime -Apply -Dry|Out-Null;Assert-NoCalls
    foreach($change in @(
        {$argsMap.Enabled=''},{$argsMap.Enabled='false'},{$argsMap.BranchRef='refs/heads/develop'},{$argsMap.EventName='pull_request'},
        {$argsMap.ExpectedInputsSha256='0'*64},{Remove-Item (Join-Path $temp 'receipt.json')},{[IO.File]::AppendAllText((Join-Path $temp 'source.zip'),'changed')}
    )) {Reset-Runtime;Seal;& $change;Reject {Invoke-Runtime -Apply} '';Assert-NoCalls}
    Reset-Runtime;Seal;Reject {Invoke-Runtime -Launcher TRUE -Apply} 'FUNCTIONS_PRODUCTION_LAUNCHER_GATE_INVALID';Assert-NoCalls
    Reset-Runtime;Seal;Reject {& "$repo/scripts/deploy-production.ps1" @argsMap -ProtectedEnvironment staging -LauncherEnabled true -ApplyReviewedPlan} 'FUNCTIONS_PRODUCTION_ENVIRONMENT_REQUIRED';Assert-NoCalls
    foreach($change in @(
        {$receipt.Remove('runtimeBinding')},{$receipt.status='FAILED'},{$receipt.sourceCommit='f'*40},{$receipt.artifactSha256='0'*64},
        {$receipt.runtimeBinding.deployerImageDigest='sha256:'+('f'*64)},{$receipt.runtimeBinding.lambda_artifact.s3_object_version='unreviewed'},
        {$receipt.runtimeBinding.lambda_artifact.sha256_hex='f'*64},{$config.ownership_handoff_reviewed=$false},{$config.ownership_handoff_reviewed='true'}
    )) {Reset-Runtime;& $change;Seal;Reject {Invoke-Runtime -Apply} '';Assert-NoCalls}
    foreach($invalid in @(@(),@('singleton'),$null)) {
        Reset-Runtime;$receipt.runtimeBinding.sourceVersionId=$invalid;Seal;Reject {Invoke-Runtime -Apply} 'FUNCTIONS_STAGING_RUNTIME_BINDING_INVALID';Assert-NoCalls
        Reset-Runtime;$receipt.runtimeBinding.lambda_artifact.s3_object_version=$invalid;Seal;Reject {Invoke-Runtime -Apply} 'FUNCTIONS_LAMBDA_BINDING_INVALID';Assert-NoCalls
    }
    Reset-Runtime;Seal
    if((Invoke-Runtime -Apply) -notlike 'PRODUCTION_TERRAFORM_APPLIED:*'){throw 'Explicit valid apply did not execute real adapter'}
    $history=$global:functionsRuntimeFixture.Calls -join "`n"
    if($history -notmatch '(?s)put-object.*terraform .* init .*functions/production.tfstate.*terraform .* validate.*terraform .* plan .*terraform .* apply .*delete-object' -or $global:functionsRuntimeFixture.Locked){throw 'Production executor lock/root/plan/apply/unlock sequence is invalid'};$script:count++
    foreach($failure in @('lock','init','validate','plan','missing-plan','window','apply')) {
        Reset-Runtime;Seal;$global:functionsRuntimeFixture.Failure=$failure
        Reject {Invoke-Runtime -Apply} ''
        $history=$global:functionsRuntimeFixture.Calls -join "`n"
        if(($failure -cne 'apply' -and $history -match 'terraform .* apply ') -or $global:functionsRuntimeFixture.Locked){throw 'Failed production preflight/plan wrote resources or retained its lock'};$script:count++
    }
    # Exercise staging receipt generation, then consume those exact runtime
    # bindings in the production executor. AWS transport is mocked throughout.
    Reset-Runtime;Seal
    $stageConfig=$config.Clone();$stageConfig.environment='staging';Save $stageConfig 'staging-config.json'
    $stageRelease=$release.Clone();$stageRelease.environment='staging';Save $stageRelease 'staging-release.json'
    & "$repo/scripts/start-deploy.ps1" -Environment staging -SourceZip (Join-Path $temp 'source.zip') -ExpectedSha256 (Hash 'source.zip') `
        -ReleaseManifest (Join-Path $temp 'staging-release.json') -ExpectedManifestSha256 (Hash 'staging-release.json') -Bucket artifact-fixture `
        -SourcePrefix releases/functions/staging -ProjectName oficina-phase3-oficina-functions-staging-deploy -DeployerImageDigest ('c'*64) `
        -SourceCommit $commit -CloudWindowEvidenceFile (Join-Path $temp 'window.json') -TerraformVariablesFile (Join-Path $temp 'staging-config.json') `
        -StateBucket state-fixture -EventName push -BranchRef refs/heads/develop -ResultOutputFile (Join-Path $temp 'emitted-receipt.json')|Out-Null
    $script:receipt=Get-Content (Join-Path $temp 'emitted-receipt.json') -Raw|ConvertFrom-Json -AsHashtable
    if($receipt.runtimeBinding.terraformVariablesSha256 -cne (Hash 'staging-config.json') -or $receipt.runtimeBinding.releaseManifestSha256 -cne (Hash 'staging-release.json')){throw 'Staging receipt omitted actual uploaded artifact bindings'};$script:count++
    Seal;Invoke-Runtime -Apply|Out-Null
    $trusted='/tmp/oficina/functions_production.tfvars.json'
    if(Test-Path -LiteralPath $trusted){throw 'Production fixture path already exists; isolate this test rather than overwrite it.'}
    New-Item -ItemType Directory -Path (Split-Path -Parent $trusted) -Force|Out-Null
    try {
        Reset-Runtime;Seal;Copy-Item (Join-Path $temp 'config.json') $trusted
        $executor=@{Environment='production';ReleaseManifest=(Join-Path $temp 'release.json');ExpectedSourceSha256=(Hash 'source.zip');ExpectedManifestSha256=(Hash 'release.json');ExpectedTerraformVariablesSha256=(Hash 'config.json');SourceCommit=$commit;ExpectedDeployerImageDigest=$release.deployerImageDigest;TerraformVariablesFile=$trusted;TerraformBackendBucket='state-fixture';TerraformBackendKey='functions/production.tfstate';TerraformBackendLockKey='functions/production.tfstate.tflock';TerraformBackendRegion='us-east-1';StateBucket='state-fixture';SharedFoundationMutation=$true;ProductionEnabled='true';ProductionLauncherEnabled='true';ProtectedEnvironment='production';ProductionRoleArn=$role;ProductionInputsFile=$argsMap.InputsFile;ExpectedProductionInputsSha256=$argsMap.ExpectedInputsSha256;EventName='push';BranchRef='refs/heads/main'}
        if((& "$repo/scripts/deploy.ps1" @executor) -cne 'PRODUCTION_RUNTIME_VALIDATED_DEPLOYMENT_DISABLED'){throw 'Executor default must remain validation-only'};Assert-NoCalls
        & "$repo/scripts/deploy.ps1" @executor -ApplyReviewedPlan -DryRun|Out-Null;Assert-NoCalls
        $executor.TerraformBackendKey='functions/staging.tfstate';Reject {& "$repo/scripts/deploy.ps1" @executor -ApplyReviewedPlan} 'unreviewed production executor';Assert-NoCalls
        $executor.TerraformBackendKey='functions/production.tfstate'
        & "$repo/scripts/deploy.ps1" @executor -ApplyReviewedPlan|Out-Null
        if($global:functionsRuntimeFixture.Locked -or ($global:functionsRuntimeFixture.Calls -join "`n") -notmatch 'terraform .* apply '){throw 'Real production entrypoint did not invoke its guarded adapter'};$script:count++
    } finally {Remove-Item -LiteralPath $trusted -Force -ErrorAction SilentlyContinue}
    Remove-Variable functionsRuntimeFixture -Scope Global
    Write-Output "PASS: $count FUNCTIONS production contracts; defaults closed, staging/runtime bindings required, explicit runtime mocked, no real AWS/Terraform apply or OIDC."
}
finally {
    $resolved=[IO.Path]::GetFullPath($temp)
    if(-not $resolved.StartsWith([IO.Path]::GetFullPath([IO.Path]::GetTempPath()),[StringComparison]::OrdinalIgnoreCase) -or -not [IO.Path]::GetFileName($resolved).StartsWith('oficina-functions-production-')){throw 'Unsafe cleanup target.'}
    Remove-Item -LiteralPath $resolved -Recurse -Force
}
