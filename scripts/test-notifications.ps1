param([switch]$NativeOnly, [switch]$CarrierOnly)
$ErrorActionPreference = 'Stop'
$repo = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
$out = Join-Path $repo 'build/notifications-validation'
$classes = Join-Path $out 'classes'
New-Item -ItemType Directory -Force -Path $classes | Out-Null
$jdk = 'C:\Program Files\Android\Android Studio\jbr\bin'
$cache = Join-Path $env:USERPROFILE '.gradle/caches/modules-2/files-2.1'
$junit = Get-ChildItem -LiteralPath (Join-Path $cache 'junit/junit/4.13.2') -Recurse -Filter '*.jar' | Select-Object -First 1 -ExpandProperty FullName
$hamcrest = Get-ChildItem -LiteralPath (Join-Path $cache 'org.hamcrest/hamcrest-core/1.3') -Recurse -Filter '*.jar' | Select-Object -First 1 -ExpandProperty FullName
$android = Join-Path $env:LOCALAPPDATA 'Android/Sdk/platforms/android-37.0/android.jar'
$source = Join-Path $repo 'android/module/src/main/java/io/github/yuejie/connect'
$sources = @('NotificationSyncCodec', 'NotificationSyncLedger', 'NotificationSyncRuntime', 'NotificationSyncListener', 'NotificationSyncActionReceiver', 'NotificationSyncActionPolicy', 'NotificationSyncBridgeTransport', 'NotificationSyncFragments', 'NotificationSyncIngress', 'NotificationSyncEndpoint', 'XiaomiNotificationCodec', 'XiaomiNotificationAdapter', 'OplusNotificationPolicy', 'BridgeClient', 'IBridge', 'NativeLogicalSessions') | ForEach-Object { Join-Path $source "$_.java" }
$sources += Join-Path $repo 'android/module/src/debug/java/io/github/yuejie/connect/NotificationSyncFixture.java'
$testClasses = @('NotificationSyncActionPolicyTest', 'XiaomiNotificationCodecTest', 'NotificationSyncBridgeTransportTest')
if (-not $NativeOnly) { $testClasses += 'NotificationSyncCodecTest' }
if ($CarrierOnly) { $testClasses = @('NotificationSyncFragmentsTest', 'NotificationSyncIngressTest', 'NotificationSyncBridgeTransportTest') }
elseif (-not $NativeOnly) { $testClasses += @('NotificationSyncFragmentsTest', 'NotificationSyncIngressTest') }
$tests = @($testClasses | ForEach-Object { Join-Path $repo "android/module/src/test/java/io/github/yuejie/connect/$_.java" })
if ($NativeOnly -and -not $CarrierOnly) {
    # Carrier helpers are compile dependencies of BridgeTransportTest; do not rerun their suites here.
    $tests += @('NotificationSyncFragmentsTest', 'NotificationSyncIngressTest') | ForEach-Object { Join-Path $repo "android/module/src/test/java/io/github/yuejie/connect/$_.java" }
}
$tests += @(Get-ChildItem -LiteralPath (Join-Path $repo 'android/module/src/test/java/com/xiaomi/mirror/message') -Filter '*.java' | ForEach-Object FullName)
python (Join-Path $repo 'scripts/prepare-notification-test-dependencies.py')
if ($LASTEXITCODE -ne 0) { throw 'Notification test dependency preparation failed' }
$json = @(Get-ChildItem -LiteralPath (Join-Path $out 'json-src/org/json') -Filter '*.java' | ForEach-Object FullName)
$api = Join-Path $out 'xposed-api.jar'
$suffix = if ($CarrierOnly) { '-carrier-round2' } elseif ($NativeOnly) { '-native' } else { '' }
& (Join-Path $jdk 'javac.exe') --release 17 -encoding UTF-8 -cp "$android;$api;$junit" -d $classes @sources @tests @json 2>&1 | Tee-Object -FilePath (Join-Path $out "compile$suffix.log")
if ($LASTEXITCODE -ne 0) { throw 'Notification source compilation failed' }
$qualified = @($testClasses | ForEach-Object { "io.github.yuejie.connect.$_" })
& (Join-Path $jdk 'java.exe') -cp "$classes;$android;$api;$junit;$hamcrest" org.junit.runner.JUnitCore @qualified 2>&1 | Tee-Object -FilePath (Join-Path $out "tests$suffix.log")
$testExit = $LASTEXITCODE
$hashes = @($sources + $tests + $PSCommandPath + (Join-Path $repo 'scripts/prepare-notification-test-dependencies.py')) | ForEach-Object { [ordered]@{ path = $_.Substring($repo.Length + 1).Replace('\', '/'); sha256 = (Get-FileHash -LiteralPath $_ -Algorithm SHA256).Hash.ToLowerInvariant() } }
[ordered]@{ utc = [DateTime]::UtcNow.ToString('o'); command = "powershell -NoProfile -File scripts/test-notifications.ps1 $(if ($CarrierOnly) { '-CarrierOnly' } elseif ($NativeOnly) { '-NativeOnly' })"; exit_code = $testExit; tests = $qualified; scope = 'SDK37/libxposed102 compile; selected JVM tests with synthetic native descriptor fixtures and installed Android JSON source. Not native execution or device delivery.'; apk = $null; sources = $hashes } | ConvertTo-Json -Depth 6 | Set-Content -LiteralPath (Join-Path $out "ledger$suffix.json") -Encoding utf8
if ($testExit -ne 0) { throw 'Notification tests failed' }
