[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)][string]$AppRoot,
    [Parameter(Mandatory = $true)][string]$JavaHome,
    [string]$ReceiptPath
)

$ErrorActionPreference = 'Stop'

# Process redirection avoids PowerShell 5.1 turning native stderr (including a
# successful java -version or JVM warning) into a terminating NativeCommandError.
function Invoke-CapturedProcess([string]$Executable, [string[]]$Arguments) {
    $quotedArguments = foreach ($argument in $Arguments) {
        # Windows CommandLineToArgvW quoting: double backslashes before quotes/end.
        '"' + [regex]::Replace([regex]::Replace($argument, '(\\*)"', '$1$1\"'), '(\\+)$', '$1$1') + '"'
    }
    $start = New-Object System.Diagnostics.ProcessStartInfo
    $start.FileName = $Executable
    $start.Arguments = $quotedArguments -join ' '
    $start.UseShellExecute = $false
    $start.CreateNoWindow = $true
    $start.RedirectStandardOutput = $true
    $start.RedirectStandardError = $true
    $process = New-Object System.Diagnostics.Process
    $process.StartInfo = $start
    try {
        if (!$process.Start()) { throw "Cannot start $Executable" }
        # Drain both streams concurrently to avoid pipe deadlocks.
        $stdout = $process.StandardOutput.ReadToEndAsync()
        $stderr = $process.StandardError.ReadToEndAsync()
        $process.WaitForExit()
        [pscustomobject]@{ ExitCode = $process.ExitCode; Output = $stdout.Result + $stderr.Result }
    } finally {
        $process.Dispose()
    }
}
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
$versionResult = Invoke-CapturedProcess $java @('-version')
$javaVersion = $versionResult.Output
if ($versionResult.ExitCode -ne 0 -or $javaVersion -notmatch 'version "17\.') {
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
Write-Evidence "POWERSHELL_VERSION=$($PSVersionTable.PSVersion)"
Write-Evidence "ERROR_ACTION_PREFERENCE=$ErrorActionPreference"
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
$compileResult = Invoke-CapturedProcess $javac @('--release', '17', '-cp', $actualClasspath, '-d', $harnessClasses, $source)
if ($compileResult.Output) { Write-Evidence $compileResult.Output.TrimEnd() }
if ($compileResult.ExitCode -ne 0) { throw "Harness compilation failed; evidence: $log" }
Write-Evidence "RUN: & '$java' -cp '<ACTUAL_CLASSPATH above>' com.oficina.functions.interop.AppTokenInterop '$appClasses' '$funClasses' '$evidence'"
$runResult = Invoke-CapturedProcess $java @('-cp', $actualClasspath, 'com.oficina.functions.interop.AppTokenInterop', $appClasses, $funClasses, $evidence)
Write-Evidence $runResult.Output.TrimEnd()
if ($runResult.ExitCode -ne 0 -or $runResult.Output -notmatch '(?m)^INTEROP_RESULT=PASS\r?$') {
    throw "APP interoperability failed; evidence: $log"
}
Write-Evidence "EVIDENCE_DIRECTORY=$evidence"

if ($ReceiptPath) {
    # Allowlist only public technical provenance. Never copy raw paths, Git status,
    # fixtures, tokens, keys, subjects or arbitrary stdout into committed evidence.
    $loadedClasses = foreach ($line in ($runResult.Output -split '\r?\n')) {
        if ($line -match '^LOADED_CLASS=(\S+) SOURCE=(.+) SHA256=([a-f0-9]{64})$') {
            $className = $Matches[1]
            $origin = $Matches[2]
            $classHash = $Matches[3]
            if ($origin -eq $appClasses) { $originLabel = 'APP_TARGET_CLASSES' }
            elseif ($origin -eq $funClasses) { $originLabel = 'FUN_TARGET_CLASSES' }
            else { throw 'Unexpected receipt class origin.' }
            [ordered]@{ name = $className; verifiedCodeSource = $originLabel; sha256 = $classHash }
        }
    }
    $checks = @($runResult.Output -split '\r?\n' | Where-Object { $_ -match '^PASS APP CustomerTokenValidator (accepts fresh FUN RsaTokenSigner token and rechecks identity|rechecks changed identity version for the same token|rejects (tampered|refresh|algorithm-confused) before database lookup)$' })
    if (@($loadedClasses).Count -ne 6 -or $checks.Count -ne 5) { throw 'Incomplete receipt assertions.' }
    $receipt = [ordered]@{
        schemaVersion = 1
        result = 'PASS'
        recordedAtUtc = [DateTime]::UtcNow.ToString('o')
        powershellVersion = $PSVersionTable.PSVersion.ToString()
        errorActionPreference = $ErrorActionPreference.ToString()
        javaVersion = ([regex]::Match($runResult.Output, '(?m)^JAVA_RUNTIME=([^\r\n]+)')).Groups[1].Value
        appHead = $appHead.Trim()
        harnessSource = 'src/interop/java/com/oficina/functions/interop/AppTokenInterop.java'
        harnessSourceSha256 = (Get-FileHash -LiteralPath $source -Algorithm SHA256).Hash.ToLowerInvariant()
        invocationScript = 'scripts/verify-app-token-interop.ps1'
        invocationScriptSha256 = (Get-FileHash -LiteralPath $PSCommandPath -Algorithm SHA256).Hash.ToLowerInvariant()
        appClasspathSourceSha256 = (Get-FileHash -LiteralPath $classpathSource -Algorithm SHA256).Hash.ToLowerInvariant()
        runtimeClasspathRoots = @('HARNESS_CLASSES', 'FUN_TARGET_CLASSES', 'APP_TARGET_CLASSES')
        dependencyJarNames = @($dependencies | ForEach-Object { [IO.Path]::GetFileName($_) })
        loadedClasses = @($loadedClasses)
        checks = $checks
        localRunLogSha256 = (Get-FileHash -LiteralPath $log -Algorithm SHA256).Hash.ToLowerInvariant()
        regeneration = 'Run Java 17 FUN mvnw verify, then scripts/verify-app-token-interop.ps1 -AppRoot <APP checkout> -JavaHome <Java 17 JDK> -ReceiptPath docs/evidence/app-token-interop-receipt.json'
    }
    $receiptAbsolutePath = [IO.Path]::GetFullPath($ReceiptPath)
    [IO.Directory]::CreateDirectory([IO.Path]::GetDirectoryName($receiptAbsolutePath)) | Out-Null
    [IO.File]::WriteAllText($receiptAbsolutePath, ($receipt | ConvertTo-Json -Depth 6), (New-Object Text.UTF8Encoding $false))
    Write-Output "Public-only regeneration receipt: $receiptAbsolutePath"
}
Write-Output "Retained successful interoperability evidence: $log"
