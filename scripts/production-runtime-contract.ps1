Set-StrictMode -Version Latest

function Get-FunctionsLambdaBinding([object]$Config) {
    $property=$Config.PSObject.Properties['lambda_artifact']
    if ($null -eq $property -or $property.Value -isnot [pscustomobject]) { throw 'FUNCTIONS_LAMBDA_BINDING_REQUIRED' }
    $result=[ordered]@{}
    foreach($name in @('s3_bucket','s3_key','s3_object_version','sha256_hex','sha256_base64')) {
        $field=$property.Value.PSObject.Properties[$name]
        if ($null -eq $field -or $field.Value -isnot [string] -or [string]::IsNullOrWhiteSpace($field.Value) -or $field.Value -ceq 'null') {
            throw 'FUNCTIONS_LAMBDA_BINDING_INVALID'
        }
        $result[$name]=$field.Value
    }
    if ($result.sha256_hex -cnotmatch '\A[a-f0-9]{64}\z' -or
        $result.sha256_base64 -cne [Convert]::ToBase64String([Convert]::FromHexString($result.sha256_hex))) {
        throw 'FUNCTIONS_LAMBDA_BINDING_INVALID'
    }
    return [pscustomobject]$result
}

function Read-FunctionsProductionRuntime {
    param([string]$Enabled,[string]$LauncherEnabled,[string]$ProtectedEnvironment,
        [string]$RoleArn,[string]$InputsFile,[string]$ExpectedInputsSha256,
        [string]$SourceCommit,[string]$EventName,[string]$BranchRef)
    if ($ProtectedEnvironment -cne 'production') { throw 'FUNCTIONS_PRODUCTION_ENVIRONMENT_REQUIRED' }
    $review=& (Join-Path $PSScriptRoot 'check-production-promotion.ps1') -Enabled $Enabled -LauncherEnabled $LauncherEnabled `
        -RoleArn $RoleArn -InputsFile $InputsFile -ExpectedInputsSha256 $ExpectedInputsSha256 `
        -SourceCommit $SourceCommit -EventName $EventName -BranchRef $BranchRef -PassThru
    $proof=$review.Receipt.PSObject.Properties['runtimeBinding']
    if ($null -eq $proof -or $proof.Value -isnot [pscustomobject]) { throw 'FUNCTIONS_STAGING_RUNTIME_BINDING_REQUIRED' }
    $binding=$proof.Value
    foreach($field in @('sourceVersionId','releaseManifestVersionId','terraformVariablesVersionId','releaseManifestSha256','terraformVariablesSha256','deployerImageDigest')) {
        $value=$binding.PSObject.Properties[$field]
        if ($null -eq $value -or $value.Value -isnot [string] -or [string]::IsNullOrWhiteSpace($value.Value) -or $value.Value -ceq 'null') {
            throw 'FUNCTIONS_STAGING_RUNTIME_BINDING_INVALID'
        }
    }
    if ($binding.releaseManifestSha256 -cnotmatch '\A[a-f0-9]{64}\z' -or
        $binding.terraformVariablesSha256 -cnotmatch '\A[a-f0-9]{64}\z' -or
        $binding.deployerImageDigest -cne $review.Inputs.deployerImageDigest) { throw 'FUNCTIONS_STAGING_EXECUTOR_NOT_PROMOTED' }
    $staged=Get-FunctionsLambdaBinding $binding
    $target=Get-FunctionsLambdaBinding $review.Config
    foreach($field in @('s3_bucket','s3_key','s3_object_version','sha256_hex','sha256_base64')) {
        if ($staged.$field -cne $target.$field) { throw 'FUNCTIONS_LAMBDA_NOT_PROMOTED' }
    }
    $ownership=$review.Config.PSObject.Properties['ownership_handoff_reviewed']
    if ($null -eq $ownership -or $ownership.Value -isnot [bool] -or -not $ownership.Value) { throw 'FUNCTIONS_PRODUCTION_OWNERSHIP_REQUIRED' }
    return $review
}
