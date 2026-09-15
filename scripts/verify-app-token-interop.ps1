[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)][string]$AppRoot,
    [Parameter(Mandatory = $true)][string]$JavaHome
)

$ErrorActionPreference = 'Stop'
$PSNativeCommandUseErrorActionPreference = $false
$funRoot = (Resolve-Path -LiteralPath (Join-Path $PSScriptRoot '..')).Path
$appPath = (Resolve-Path -LiteralPath $AppRoot).Path
$jdkPath = (Resolve-Path -LiteralPath $JavaHome).Path
$java = Join-Path $jdkPath 'bin/java.exe'
$javac = Join-Path $jdkPath 'bin/javac.exe'
$funClasses = (Resolve-Path -LiteralPath (Join-Path $funRoot 'target/classes')).Path
$appClasses = (Resolve-Path -LiteralPath (Join-Path $appPath 'target/classes')).Path
$source = Join-Path $funRoot 'src/interop/java/com/oficina/functions/interop/AppTokenInterop.java'
$classpathSource = Join-Path $appPath 'target/surefire-reports/TEST-com.oficina.security.TokenTrustTest.xml'

if (!(Test-Path -LiteralPath (Join-Path $funClasses 'com/oficina/functions/auth/RsaTokenSigner.class'))) {
    throw 'Build FUN with Java 17 mvnw verify before running this script.'
}
if (!(Test-Path -LiteralPath (Join-Path $appClasses 'com/oficina/security/CustomerTokenValidator.class'))) {
    throw 'APP A3 compiled CustomerTokenValidator classes are required; this script does not build or modify APP.'
}
$javaVersion = & $java -version 2>&1 | Out-String
if ($LASTEXITCODE -ne 0 -or $javaVersion -notmatch 'version "17\.') {
    throw 'JavaHome must select a working Java 17 JDK.'
}

[xml]$testReport = Get-Content -Raw -LiteralPath $classpathSource
$originalClasspath = ($testReport.testsuite.properties.property | Where-Object name -eq 'java.class.path').value
if (!$originalClasspath) { throw 'APP TokenTrustTest report must contain its java.class.path.' }
# Use APP classes and its recorded dependency jars, never APP test fixture classes.
$dependencies = @($originalClasspath -split ';' | Where-Object { $_ -and $_.EndsWith('.jar') })
foreach ($dependency in $dependencies) {
    if (!(Test-Path -LiteralPath $dependency -PathType Leaf)) { throw "Missing APP dependency: $dependency" }
}

$runId = [DateTime]::UtcNow.ToString('yyyyMMddTHHmmssfffffffZ') + '-' + [Guid]::NewGuid().ToString('N').Substring(0, 8)
$evidence = Join-Path $funRoot ('target/app-token-interop/' + $runId)
$harnessClasses = Join-Path $evidence 'classes'
New-Item -ItemType Directory -Path $harnessClasses -Force | Out-Null
$log = Join-Path $evidence 'run.log'
$actualClasspath = (@($harnessClasses, $funClasses, $appClasses) + $dependencies) -join [IO.Path]::PathSeparator

function Write-Evidence([string]$Message) {
    $Message | Tee-Object -FilePath $log -Append
}

Write-Evidence "RUN_UTC=$([DateTime]::UtcNow.ToString('o'))"
Write-Evidence "JAVA_EXECUTABLE=$java"
Write-Evidence $javaVersion.Trim()
Write-Evidence "APP_ROOT=$appPath"
$appHead = & git -C $appPath rev-parse HEAD
if ($LASTEXITCODE -ne 0) { throw 'Cannot record APP Git HEAD.' }
Write-Evidence "APP_HEAD=$appHead"
$appStatus = & git -C $appPath status --short
if ($LASTEXITCODE -ne 0) { throw 'Cannot record APP Git state.' }
Write-Evidence ('APP_GIT_STATUS=' + ($appStatus -join ' | '))
Write-Evidence "APP_CLASSPATH_SOURCE=$classpathSource"
Write-Evidence ('APP_CLASSPATH_SOURCE_SHA256=' + (Get-FileHash -LiteralPath $classpathSource -Algorithm SHA256).Hash.ToLowerInvariant())
Write-Evidence "APP_ORIGINAL_CLASSPATH=$originalClasspath"
Write-Evidence "ACTUAL_CLASSPATH=$actualClasspath"
Write-Evidence "HARNESS_SOURCE=$source"
Write-Evidence ('HARNESS_SOURCE_SHA256=' + (Get-FileHash -LiteralPath $source -Algorithm SHA256).Hash.ToLowerInvariant())
Write-Evidence ('SCRIPT_SHA256=' + (Get-FileHash -LiteralPath $PSCommandPath -Algorithm SHA256).Hash.ToLowerInvariant())
[IO.File]::WriteAllText((Join-Path $evidence 'app-original-classpath.txt'), $originalClasspath)
[IO.File]::WriteAllText((Join-Path $evidence 'runtime-classpath.txt'), $actualClasspath)

Write-Evidence "COMPILE: & '$javac' --release 17 -cp '<ACTUAL_CLASSPATH above>' -d '$harnessClasses' '$source'"
& $javac --release 17 -cp $actualClasspath -d $harnessClasses $source 2>&1 | ForEach-Object { Write-Evidence "$_" }
if ($LASTEXITCODE -ne 0) { throw "Harness compilation failed; evidence: $log" }
Write-Evidence "RUN: & '$java' -cp '<ACTUAL_CLASSPATH above>' com.oficina.functions.interop.AppTokenInterop '$appClasses' '$funClasses' '$evidence'"
& $java -cp $actualClasspath com.oficina.functions.interop.AppTokenInterop $appClasses $funClasses $evidence 2>&1 | ForEach-Object { Write-Evidence "$_" }
if ($LASTEXITCODE -ne 0) { throw "APP interoperability failed; evidence: $log" }
Write-Evidence "EVIDENCE_DIRECTORY=$evidence"
Write-Output "Retained successful interoperability evidence: $log"
