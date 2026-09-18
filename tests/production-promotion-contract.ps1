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
    Reset-Fixture;Seal;$argsMap.LauncherEnabled='true';Reject {& $validator @argsMap} 'FUNCTIONS_PRODUCTION_LAUNCHER_NOT_IMPLEMENTED'
    Reset-Fixture;Save @{windowStartUtc=[datetimeoffset]::UtcNow.AddHours(-2).ToString('o');windowEndUtc=[datetimeoffset]::UtcNow.AddHours(-1).ToString('o');recordedAtUtc=[datetimeoffset]::UtcNow.AddHours(-2).ToString('o');accountEvidenceReference='fixture';projectAllowanceUsd=10;reserveUsd=1;currentEstimatedSpendUsd=0} 'window.json';$inputs.cloudWindowEvidence=Ref 'window.json';$release.cloudWindowEvidenceSha256=Hash 'window.json';Seal;Reject {& $validator @argsMap} 'window is closed'
    $code=Get-Content $validator -Raw
    if($calls -ne 0 -or $code -match 'start-deploy\.ps1|deploy\.ps1|Invoke-Expression|Start-Process|&\s+(aws|terraform)'){throw 'Production validator must never invoke cloud operations or a launcher.'}
    Write-Output "PASS: $count FUNCTIONS production contracts; main gate closed by default, develop staging unchanged, receipts required, valid path disabled, no launcher/OIDC."
}
finally {
    $resolved=[IO.Path]::GetFullPath($temp)
    if(-not $resolved.StartsWith([IO.Path]::GetFullPath([IO.Path]::GetTempPath()),[StringComparison]::OrdinalIgnoreCase) -or -not [IO.Path]::GetFileName($resolved).StartsWith('oficina-functions-production-')){throw 'Unsafe cleanup target.'}
    Remove-Item -LiteralPath $resolved -Recurse -Force
}
