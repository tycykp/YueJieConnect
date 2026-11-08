param([string]$Jdk = 'C:\Program Files\Android\Android Studio\jbr',
      [string]$Sdk = 'C:\Users\tycykp\AppData\Local\Android\Sdk',
      [ValidateSet('all', 'relay', 'compile', 'sms-native', 'audio', 'endpoint')][string]$Suite = 'all',
      [string]$ThreadId = '01a1bbe4-ec32-7d01-a5de-8fc711849d20')
$ErrorActionPreference = 'Stop'
$root = Split-Path $PSScriptRoot -Parent
$output = Join-Path $root 'build\calls-sms-validation'
if ($Suite -eq 'relay') { $output = Join-Path $output 'relay' }
if ($Suite -eq 'compile') { $output = Join-Path $output 'compile-only' }
if ($Suite -eq 'sms-native') { $output = Join-Path $output 'sms-native' }
if ($Suite -eq 'audio') { $output = Join-Path $output 'repair\audio' }
if ($Suite -eq 'endpoint') { $output = Join-Path $output 'usable\endpoint' }
$classes = Join-Path $output 'classes'
New-Item -ItemType Directory -Force $classes | Out-Null
$source = Join-Path $root 'android\module\src\main\java\io\github\qiangjian\connect'
$tests = Join-Path $root 'android\module\src\test\java\io\github\qiangjian\connect'
$cache = Join-Path $env:USERPROFILE '.gradle\caches'
$junit = (Get-ChildItem -LiteralPath (Join-Path $cache 'modules-2\files-2.1\junit\junit\4.13.2') -Recurse -Filter 'junit-4.13.2.jar' | Select-Object -First 1).FullName
$hamcrest = (Get-ChildItem -LiteralPath (Join-Path $cache 'modules-2\files-2.1\org.hamcrest\hamcrest-core\1.3') -Recurse -Filter 'hamcrest-core-1.3.jar' | Select-Object -First 1).FullName
$xposed = (Get-ChildItem -LiteralPath (Join-Path $cache '9.7.1\transforms') -Recurse -Filter 'api-102.0.0-api.jar' | Select-Object -First 1).FullName
$platform = Join-Path $Sdk 'platforms\android-37.0\android.jar'
$files = @(Get-ChildItem -LiteralPath $source -Filter 'TelephonySync*.java') + @(Get-ChildItem -LiteralPath $source -Filter 'OplusTelephony*.java') + @(Get-ChildItem -LiteralPath $source -Filter 'XiaomiTelephony*.java') + @(Get-ChildItem -LiteralPath $tests -Filter 'TelephonySync*Test.java')
$files += @('BridgeClient.java', 'IBridge.java', 'NativeLogicalSessions.java' | ForEach-Object { Get-Item -LiteralPath (Join-Path $source $_) })
$beforeHashes = @{}
foreach ($file in $files) { $beforeHashes[$file.FullName] = (Get-FileHash -LiteralPath $file.FullName -Algorithm SHA256).Hash.ToLowerInvariant() }
$ErrorActionPreference = 'Continue' # Windows PowerShell wraps native stderr warnings as ErrorRecords.
$compile = & (Join-Path $Jdk 'bin\javac.exe') '-J-Duser.language=en' --release 17 -encoding UTF-8 -cp "$platform;$xposed;$junit;$hamcrest" -d $classes @($files.FullName) 2>&1
$compileExit = $LASTEXITCODE
$ErrorActionPreference = 'Stop'
$compile | Set-Content -LiteralPath (Join-Path $output 'compile.txt') -Encoding utf8
if ($compileExit -ne 0) { $compile | Write-Output; throw 'Telephony targeted compilation failed' }
$testClasses = @('io.github.qiangjian.connect.TelephonySyncRelayTest')
if ($Suite -eq 'all') { $testClasses += @('io.github.qiangjian.connect.TelephonySyncControllerTest', 'io.github.qiangjian.connect.TelephonySyncSmsTest', 'io.github.qiangjian.connect.TelephonySyncSmsStateTest') }
if ($Suite -eq 'all') { $testClasses += 'io.github.qiangjian.connect.TelephonySyncSmsNativeTest' }
if ($Suite -eq 'all') { $testClasses += 'io.github.qiangjian.connect.TelephonySyncAudioSessionTest' }
if ($Suite -eq 'sms-native') { $testClasses = @('io.github.qiangjian.connect.TelephonySyncSmsNativeTest') }
if ($Suite -eq 'audio') { $testClasses = @('io.github.qiangjian.connect.TelephonySyncAudioSessionTest') }
if ($Suite -eq 'endpoint') { $testClasses = @('io.github.qiangjian.connect.TelephonySyncEndpointTest') }
if ($Suite -eq 'all') { $testClasses += 'io.github.qiangjian.connect.TelephonySyncEndpointTest' }
if ($Suite -eq 'compile') { $testClasses = @(); Write-Output 'PASS: owned Android sources compile; no tests rerun.' }
else {
    $result = & (Join-Path $Jdk 'bin\java.exe') -cp "$classes;$junit;$hamcrest" org.junit.runner.JUnitCore @testClasses 2>&1
    $result | Set-Content -LiteralPath (Join-Path $output 'tests.txt') -Encoding utf8
    $result | Write-Output
    if ($LASTEXITCODE -ne 0) { throw 'Telephony isolated tests failed' }
}
$ledger = [ordered]@{
    task = 'calls-sms'
    thread = $ThreadId
    utc = [DateTime]::UtcNow.ToString('o')
    command = "powershell -NoProfile -File scripts/test-telephony.ps1 -Suite $Suite"
    scriptSha256 = (Get-FileHash -LiteralPath $PSCommandPath -Algorithm SHA256).Hash.ToLowerInvariant()
    testClasses = $testClasses
    scope = $(if ($Suite -eq 'compile') { 'Compile only; all owned Android sources against API37/libxposed102. No tests rerun, APK build/install/device/SMS/call action.' } else { 'Selected isolated JVM tests; all owned Android sources compiled against API37/libxposed102. No APK build/install/device/SMS/call action.' })
    apk = $null
    sources = @($files | ForEach-Object { @{path=$_.FullName; sha256=(Get-FileHash -LiteralPath $_.FullName -Algorithm SHA256).Hash.ToLowerInvariant()} })
}
$ledger['sourceRaces'] = @($ledger.sources | Where-Object { $beforeHashes[$_.path] -ne $_.sha256 } | ForEach-Object { @{path=$_.path; before=$beforeHashes[$_.path]; after=$_.sha256} })
$ledger | ConvertTo-Json -Depth 6 | Set-Content -LiteralPath (Join-Path $output 'validation.json') -Encoding utf8
if ($ledger.sourceRaces.Count -ne 0) { throw 'Telephony compiler/test inputs changed during validation; do not reuse this result for the new sources.' }
