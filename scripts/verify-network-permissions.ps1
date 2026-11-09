$ErrorActionPreference = 'Stop'
$taskRoot = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$taskOutput = Join-Path $taskRoot 'build/network-sharing'
$taskLocks = Join-Path $taskRoot 'build/coordination-locks'
[IO.Directory]::CreateDirectory($taskOutput) | Out-Null
[IO.Directory]::CreateDirectory($taskLocks) | Out-Null
$taskLock = Join-Path $taskLocks 'build.lock'
$taskOwner = 'network-sharing/01a1bba3-0cc5-7be3-9aad-37330bfc511f/' + [Guid]::NewGuid().ToString() + '/' + [DateTimeOffset]::UtcNow.ToString('o')
$taskHeld = $false
try {
    try { $taskStream = [IO.File]::Open($taskLock, [IO.FileMode]::CreateNew, [IO.FileAccess]::Write, [IO.FileShare]::Read) }
    catch [IO.IOException] { Write-Output 'Build lock busy; no build started and no lock removed.'; exit 75 }
    try {
        $taskBytes = [Text.Encoding]::UTF8.GetBytes($taskOwner)
        $taskStream.Write($taskBytes, 0, $taskBytes.Length)
        $taskHeld = $true
    } finally { $taskStream.Dispose() }
    $taskInit = Join-Path $taskOutput 'lint-permissions.init.gradle'
    @'
gradle.beforeProject { p ->
    p.afterEvaluate {
        if (p.path == ':android:module') {
            def lint = p.extensions.getByName('android').lint
            lint.checkOnly.clear()
            lint.checkOnly.add('MissingPermission')
            lint.textReport = true
            lint.textOutput = new File(p.rootProject.projectDir, 'build/network-sharing/lint-permissions.txt')
            lint.xmlReport = true
            lint.xmlOutput = new File(p.rootProject.projectDir, 'build/network-sharing/lint-permissions.xml')
            lint.htmlReport = false
        }
    }
}
'@ | Set-Content -LiteralPath $taskInit -Encoding ascii
    $taskSource = Join-Path $taskRoot 'android/module/src/main/java/io/github/yuejie/connect/NetworkShareEndpoint.java'
    $taskBefore = (Get-FileHash -LiteralPath $taskSource -Algorithm SHA256).Hash
    $taskLog = Join-Path $taskOutput 'lint-permissions.log'
    $env:JAVA_HOME = 'C:\Program Files\Android\Android Studio\jbr'
    $env:ANDROID_HOME = Join-Path $env:LOCALAPPDATA 'Android/Sdk'
    $env:ANDROID_SDK_ROOT = $env:ANDROID_HOME
    Push-Location $taskRoot
    try {
        # Windows PowerShell 5 wraps stderr as ErrorRecord even for non-failing Java warnings.
        $ErrorActionPreference = 'Continue'
        & .\gradlew.bat :android:module:lintDebug -x :android:module:buildRust --offline --console=plain --no-configuration-cache -I $taskInit *> $taskLog
        $taskExit = $LASTEXITCODE
    } finally { $ErrorActionPreference = 'Stop'; Pop-Location }
    $taskAfter = (Get-FileHash -LiteralPath $taskSource -Algorithm SHA256).Hash
    $taskHistory = Join-Path $taskOutput ('lint-permissions-history/' + [DateTimeOffset]::UtcNow.ToString('yyyyMMddTHHmmssfffZ'))
    [IO.Directory]::CreateDirectory($taskHistory) | Out-Null
    $taskRecord = [ordered]@{
        utc = [DateTimeOffset]::UtcNow.ToString('o')
        scope = 'MissingPermission only; no APK assembly, Rust build, device install or network mutation'
        command = 'gradlew.bat :android:module:lintDebug -x :android:module:buildRust --offline --console=plain --no-configuration-cache -I build/network-sharing/lint-permissions.init.gradle'
        exit_code = $taskExit
        endpoint_sha256 = $taskAfter
        unchanged_during_check = ($taskBefore -eq $taskAfter)
        script_sha256 = (Get-FileHash -LiteralPath $PSCommandPath -Algorithm SHA256).Hash
        log = 'build/network-sharing/lint-permissions.log'
        report = 'build/network-sharing/lint-permissions.xml'
        report_current = ($taskExit -eq 0 -and [IO.File]::Exists((Join-Path $taskOutput 'lint-permissions.xml')))
        history = $taskHistory
    }
    $taskRecord | ConvertTo-Json -Depth 4 | Set-Content -LiteralPath (Join-Path $taskOutput 'lint-permissions-evidence.json') -Encoding utf8
    foreach ($taskName in @('lint-permissions-evidence.json', 'lint-permissions.log', 'lint-permissions.xml', 'lint-permissions.txt')) {
        $taskArtifact = Join-Path $taskOutput $taskName
        if ([IO.File]::Exists($taskArtifact)) { Copy-Item -LiteralPath $taskArtifact -Destination (Join-Path $taskHistory $taskName) }
    }
    Write-Output ('MissingPermission lint exit=' + $taskExit + '; endpoint unchanged=' + ($taskBefore -eq $taskAfter))
    Get-Content -LiteralPath $taskLog -Tail 18
    if ($taskBefore -ne $taskAfter) { exit 76 }
    exit $taskExit
} finally {
    if ($taskHeld -and [IO.File]::Exists($taskLock) -and [IO.File]::ReadAllText($taskLock) -eq $taskOwner) {
        Remove-Item -LiteralPath $taskLock
    }
}
