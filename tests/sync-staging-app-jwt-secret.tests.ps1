[CmdletBinding()]
param()

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
$repo = Split-Path -Parent $PSScriptRoot
$target = Join-Path $repo 'scripts/sync-staging-app-jwt-secret.ps1'
$temp = Join-Path ([IO.Path]::GetTempPath()) ('oficina-app-jwt-contract-' + [guid]::NewGuid().ToString('N'))
$mockBin = Join-Path $temp 'bin'
$authorizerPath = Join-Path $temp 'authorizer.json'
$appPath = Join-Path $temp 'app.json'
$putPath = Join-Path $temp 'put.json'
$oldPath = $env:PATH
$appArn = 'arn:aws:secretsmanager:us-east-1:638612472889:secret:oficina/staging/app-AAAAAA'
$authorizerArn = 'arn:aws:secretsmanager:us-east-1:638612472889:secret:oficina/staging/authorizer-trust-BBBBBB'
$staffSecret = 'fixture-staff-hmac-secret-with-at-least-32-bytes'
$script:Checks = 0

function Assert-Contract {
    param([Parameter(Mandatory)][bool]$Condition, [Parameter(Mandatory)][string]$Message)
    $script:Checks++
    if (-not $Condition) { throw $Message }
}

function Invoke-ExpectedFailure {
    param([Parameter(Mandatory)][scriptblock]$Action, [Parameter(Mandatory)][string]$ExpectedText)
    $script:Checks++
    try { & $Action | Out-Null } catch {
        if ($_.Exception.Message -match [regex]::Escape($ExpectedText)) { return }
        throw "Expected '$ExpectedText', got a different failure."
    }
    throw "Expected failure containing '$ExpectedText'."
}

try {
    New-Item -ItemType Directory -Path $mockBin -Force | Out-Null
    [ordered]@{ CUSTOMER_PUBLIC_KEY_B64 = 'fixture-public-key'; STAFF_HMAC_SECRET = $staffSecret } | ConvertTo-Json -Compress | Set-Content -LiteralPath $authorizerPath -Encoding utf8
    [ordered]@{ username = 'fixture-user'; password = 'fixture-password' } | ConvertTo-Json -Compress | Set-Content -LiteralPath $appPath -Encoding utf8
    $mockAws = @'
param([Parameter(ValueFromRemainingArguments = $true)][string[]]$Arguments)
$ErrorActionPreference = 'Stop'
$joined = $Arguments -join ' '
if ($joined -match '(^|\s)sts(\s|$)') { '638612472889'; exit 0 }
if ($joined -notmatch 'secretsmanager') { exit 2 }
if ($joined -match 'get-secret-value') {
    if ($joined -match 'authorizer-trust') { Get-Content -LiteralPath $env:MOCK_AUTHORIZER_PATH -Raw } else { Get-Content -LiteralPath $env:MOCK_APP_PATH -Raw }
    exit 0
}
if ($joined -match 'put-secret-value') {
    $file = $Arguments[$Arguments.IndexOf('--secret-string') + 1].Substring(7)
    Copy-Item -LiteralPath $file -Destination $env:MOCK_PUT_PATH -Force
    @{ ARN = 'arn:aws:secretsmanager:us-east-1:638612472889:secret:oficina/staging/app-AAAAAA' } | ConvertTo-Json -Compress
    exit 0
}
exit 2
'@
    $mockAwsPath = Join-Path $mockBin 'mock-aws.ps1'
    $awsCmdPath = Join-Path $mockBin 'aws.cmd'
    $mockAws | Set-Content -LiteralPath $mockAwsPath -Encoding utf8
    "@echo off`r`npwsh -NoProfile -File `"%~dp0mock-aws.ps1`" %*`r`nexit /b %ERRORLEVEL%`r`n" | Set-Content -LiteralPath $awsCmdPath -Encoding ascii
    if ($IsLinux -or $IsMacOS) {
        $awsUnixPath = Join-Path $mockBin 'aws'
        $unixShim = @'
#!/usr/bin/env pwsh
pwsh -NoProfile -File "__MOCK_AWS__" "$@"
exit $?
'@.Replace('__MOCK_AWS__', $mockAwsPath)
        $unixShim | Set-Content -LiteralPath $awsUnixPath -Encoding utf8
        & chmod +x $awsUnixPath
    }
    $env:PATH = $mockBin + [IO.Path]::PathSeparator + $oldPath
    $env:MOCK_AUTHORIZER_PATH = $authorizerPath
    $env:MOCK_APP_PATH = $appPath
    $env:MOCK_PUT_PATH = $putPath

    $common = @{ AppStagingSecretId = $appArn; AuthorizerTrustSecretId = $authorizerArn }
    $dryArgs = $common.Clone(); $dry = (& $target @dryArgs | Out-String)
    $dryJson = $dry | ConvertFrom-Json
    Assert-Contract ($dryJson.jwt_secret_status -eq 'would_add') 'Dry-run must report a missing JWT_SECRET as would_add.'
    Assert-Contract ($dry -notmatch [regex]::Escape($staffSecret)) 'Dry-run output must not expose the HMAC value.'

    $applyArgs = $common.Clone(); $applyArgs.Apply = $true
    $applied = (& $target @applyArgs | Out-String | ConvertFrom-Json)
    $stored = Get-Content -LiteralPath $putPath -Raw | ConvertFrom-Json
    Assert-Contract ($applied.jwt_secret_status -eq 'added') 'Apply must report JWT_SECRET as added.'
    Assert-Contract ($stored.username -eq 'fixture-user' -and $stored.password -eq 'fixture-password' -and $stored.JWT_SECRET -eq $staffSecret) 'Apply must preserve APP fields and add JWT_SECRET.'

    [ordered]@{ username = 'fixture-user'; password = 'fixture-password'; feature_flag = $true; JWT_SECRET = 'old-secret-that-is-long-enough-for-a-fixture' } | ConvertTo-Json -Compress | Set-Content -LiteralPath $appPath -Encoding utf8
    $updateArgs = $common.Clone(); $updateArgs.Apply = $true
    $updated = (& $target @updateArgs | Out-String | ConvertFrom-Json)
    $updatedStored = Get-Content -LiteralPath $putPath -Raw | ConvertFrom-Json
    Assert-Contract ($updated.jwt_secret_status -eq 'updated' -and $updatedStored.feature_flag -eq $true -and $updatedStored.JWT_SECRET -eq $staffSecret) 'Apply must update a mismatched JWT_SECRET while preserving fields.'

    [ordered]@{ CUSTOMER_PUBLIC_KEY_B64 = 'fixture-public-key'; STAFF_HMAC_SECRET = 'short' } | ConvertTo-Json -Compress | Set-Content -LiteralPath $authorizerPath -Encoding utf8
    Invoke-ExpectedFailure { & $target @dryArgs } 'shorter than the required 32 UTF-8 bytes'
    [IO.File]::WriteAllText($authorizerPath, '{ malformed')
    Invoke-ExpectedFailure { & $target @dryArgs } 'is not valid JSON'
    Invoke-ExpectedFailure { & $target @common -Apply -DryRun } 'either -DryRun or -Apply'

    Write-Output "PASS: $script:Checks app JWT synchronization contract assertions."
} finally {
    $env:PATH = $oldPath
    foreach ($name in @('MOCK_AUTHORIZER_PATH', 'MOCK_APP_PATH', 'MOCK_PUT_PATH')) { Remove-Item "Env:$name" -ErrorAction SilentlyContinue }
    if (Test-Path -LiteralPath $temp) { Remove-Item -LiteralPath $temp -Recurse -Force -ErrorAction SilentlyContinue }
}
