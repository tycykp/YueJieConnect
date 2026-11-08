param([string]$Batch = 'v19-round2', [switch]$PackageOnly, [switch]$ChecksOnly, [switch]$JvmOnly,
    [switch]$FinalSchemaChecks, [switch]$SkipJvmTests, [switch]$FileChecks,
    [string[]]$TestClasses = @('CallerPolicyTest','NativeScreenProofsTest'))
$ErrorActionPreference = 'Stop'
$root = Split-Path $PSScriptRoot -Parent
$taskId = '01a1bbe4-a33d-7f83-80db-5442125d938f'
$lockDir = Join-Path $root 'build/coordination-locks'
$evidence = Join-Path $root 'build/native-pairing-validation'
New-Item -ItemType Directory -Force $lockDir, $evidence | Out-Null
$lockPath = Join-Path $lockDir 'build.lock'
$leaseId = [Guid]::NewGuid().ToString()
$leaseText = @{ task = 'native-pairing'; threadId = $taskId; leaseId = $leaseId; started = [DateTimeOffset]::Now.ToString('o') } | ConvertTo-Json -Compress
try { $lockStream = [IO.File]::Open($lockPath, [IO.FileMode]::CreateNew, [IO.FileAccess]::Write, [IO.FileShare]::Read) }
catch [IO.IOException] { Write-Output 'Build lock is busy; no build started.'; exit 75 }
try {
    $leaseBytes = [Text.Encoding]::UTF8.GetBytes($leaseText)
    $lockStream.Write($leaseBytes, 0, $leaseBytes.Length)
    $lockStream.Flush()
    Push-Location $root
    $env:JAVA_HOME = 'C:\Program Files\Android\Android Studio\jbr'
    $env:ANDROID_HOME = Join-Path $env:LOCALAPPDATA 'Android\Sdk'
    $env:ANDROID_SDK_ROOT = $env:ANDROID_HOME
    function Snapshot {
        $paths = @(git ls-files -co --exclude-standard -- core/src transport/src native/src native/tests android/module/src android/module/build.gradle.kts core/Cargo.toml transport/Cargo.toml native/Cargo.toml Cargo.toml Cargo.lock rust-toolchain.toml scripts/build-native.ps1 scripts/test-jni.ps1 scripts/verify-apk.py scripts/validate-native-pairing-batch.ps1)
        $map = [ordered]@{}
        foreach ($path in ($paths | Sort-Object -Unique)) {
            if (Test-Path -LiteralPath $path -PathType Leaf) { $map[$path] = (Get-FileHash -LiteralPath $path -Algorithm SHA256).Hash.ToLowerInvariant() }
        }
        return $map
    }
    function RunStep([string]$Name, [scriptblock]$Action) {
        # Windows PowerShell wraps native stderr as ErrorRecord even on exit 0.
        # Decide success from the process exit code, retaining stderr in the log.
        $ErrorActionPreference = 'Continue'
        & $Action 2>&1 | Tee-Object -FilePath (Join-Path $evidence "$Batch-$Name.log")
        if ($LASTEXITCODE -ne 0) { throw "$Name failed with exit $LASTEXITCODE" }
    }
    $before = Snapshot
    $before | ConvertTo-Json -Depth 4 | Set-Content -LiteralPath (Join-Path $evidence "$Batch-inputs.json") -Encoding utf8
    if($JvmOnly){
        $jvmArgs=@(':android:module:testDebugUnitTest')
        foreach($testClass in $TestClasses){$jvmArgs+=@('--tests',"io.github.qiangjian.connect.$testClass")}
        $jvmArgs+='--console=plain'
        RunStep 'jvm' { & .\gradlew.bat @jvmArgs }
        foreach($testClass in $TestClasses){Copy-Item -LiteralPath "android/module/build/test-results/testDebugUnitTest/TEST-io.github.qiangjian.connect.$testClass.xml" -Destination (Join-Path $evidence "$Batch-$testClass.xml")}
        Write-Output "Targeted JVM checks completed: $Batch";return
    }
    if($FileChecks){
        RunStep 'file-session' {cargo test --locked -p qj-transport file_session_}
        RunStep 'file-requests' {cargo test --locked -p qj-jni file_requests_}
        RunStep 'relay-wire' {cargo test --locked -p qj-transport relay::tests}
        RunStep 'clippy' {cargo clippy --locked -p qj-jni -p qj-transport --lib -- -D warnings}
        RunStep 'jni' {powershell.exe -NoProfile -ExecutionPolicy Bypass -File scripts/test-jni.ps1 -Jdk $env:JAVA_HOME}
    } elseif (!$PackageOnly) {
        if(!$FinalSchemaChecks){
            RunStep 'executor' { cargo test --locked -p qj-transport executor::tests }
            RunStep 'bridge' { cargo test --locked -p qj-transport bridge::tests }
        }
        RunStep 'notification-wire' { cargo test --locked -p qj-transport notification::tests }
        RunStep 'relay-wire' { cargo test --locked -p qj-transport relay::tests }
        RunStep 'clippy' { cargo clippy --locked -p qj-jni -p qj-transport --lib -- -D warnings }
        RunStep 'jni' { powershell.exe -NoProfile -ExecutionPolicy Bypass -File scripts/test-jni.ps1 -Jdk $env:JAVA_HOME }
    }
    if($ChecksOnly){Write-Output "Host checks completed: $Batch";return}
    $gradleArgs=@(':android:module:assembleDebug',':android:module:lintDebug')
    if(!$SkipJvmTests){
        $gradleArgs+=':android:module:testDebugUnitTest'
        foreach($testClass in $TestClasses){$gradleArgs+=@('--tests',"io.github.qiangjian.connect.$testClass")}
    }
    $gradleArgs+='--console=plain'
    RunStep 'package' { & .\gradlew.bat @gradleArgs }
    RunStep 'apk' { python scripts/verify-apk.py }
    $after = Snapshot
    $races = @()
    foreach ($path in (@($before.Keys) + @($after.Keys) | Sort-Object -Unique)) {
        if ($before[$path] -ne $after[$path]) { $races += @{ path = $path; before = $before[$path]; after = $after[$path] } }
    }
    ConvertTo-Json -InputObject $races -Depth 4 | Set-Content -LiteralPath (Join-Path $evidence "$Batch-source-races.json") -Encoding utf8
    Copy-Item -LiteralPath 'build/apk-verification.json' -Destination (Join-Path $evidence "$Batch-apk.json")
    Copy-Item -LiteralPath 'android/module/build/outputs/apk/debug/module-debug.apk' -Destination (Join-Path $evidence "module-$Batch.apk")
    if(!$SkipJvmTests){foreach($testClass in $TestClasses){Copy-Item -LiteralPath "android/module/build/test-results/testDebugUnitTest/TEST-io.github.qiangjian.connect.$testClass.xml" -Destination (Join-Path $evidence "$Batch-$testClass.xml")}}
    if ($races.Count -ne 0) { throw "Source changed during validation ($($races.Count) paths); see source-races.json. Artifact is not a stable batch." }
    Write-Output "Stable batch verified: $Batch"
} finally {
    try {
    # Retain source-race evidence even if lint/compilation aborts before APK acceptance.
    if($before){
        $after=Snapshot
        $finalRaces=@()
        foreach($path in (@($before.Keys)+@($after.Keys)|Sort-Object -Unique)){
            if($before[$path] -ne $after[$path]){$finalRaces+=@{path=$path;before=$before[$path];after=$after[$path]}}
        }
        ConvertTo-Json -InputObject $finalRaces -Depth 4 | Set-Content -LiteralPath (Join-Path $evidence "$Batch-source-races.json") -Encoding utf8
    }
    } catch { Write-Warning "Source snapshot could not finish: $($_.Exception.Message)" }
    finally {
    if ($lockStream) {
        $lockStream.Dispose()
        if ((Get-Content -LiteralPath $lockPath -Raw | ConvertFrom-Json).leaseId -eq $leaseId) { Remove-Item -LiteralPath $lockPath }
    }
    if ((Get-Location).Path -eq $root) { Pop-Location }
    }
}
