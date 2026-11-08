$ErrorActionPreference = 'Stop'
$repo = Split-Path -Parent $PSScriptRoot
$out = Join-Path $repo 'build/notifications-validation/repair-round3/distributed'
[IO.Directory]::CreateDirectory($out)|Out-Null
$javaBin = 'C:\Program Files\Android\Android Studio\jbr\bin'
$androidJar = Join-Path $env:LOCALAPPDATA 'Android/Sdk/platforms/android-37.0/android.jar'
$cache = Join-Path $env:USERPROFILE '.gradle/caches/modules-2/files-2.1'
$junit = Get-ChildItem -LiteralPath (Join-Path $cache 'junit/junit/4.13.2') -Recurse -Filter '*.jar'|Select-Object -First 1 -ExpandProperty FullName
$hamcrest = Get-ChildItem -LiteralPath (Join-Path $cache 'org.hamcrest/hamcrest-core/1.3') -Recurse -Filter '*.jar'|Select-Object -First 1 -ExpandProperty FullName
$api = Join-Path $repo 'build/notifications-validation/xposed-api.jar'
$names = @('XiaomiNotificationDistributedCodec','XiaomiNotificationDistributedSource','XiaomiNotificationDistributedSink','NotificationSyncCodec','NotificationSyncLedger')
$sources = @($names|ForEach-Object {Join-Path $repo "android/module/src/main/java/io/github/qiangjian/connect/$_.java"})
$test = Join-Path $repo 'android/module/src/test/java/io/github/qiangjian/connect/XiaomiNotificationDistributedCodecTest.java'
& (Join-Path $javaBin 'javac.exe') --release 17 -encoding UTF-8 -cp "$androidJar;$api;$junit" -d $out @sources $test 2>&1|Tee-Object -FilePath (Join-Path $out 'compile.log')
if($LASTEXITCODE-ne0){throw 'Distributed notification compile failed'}
& (Join-Path $javaBin 'java.exe') -cp "$out;$junit;$hamcrest" org.junit.runner.JUnitCore io.github.qiangjian.connect.XiaomiNotificationDistributedCodecTest 2>&1|Tee-Object -FilePath (Join-Path $out 'tests.log')
$testExit=$LASTEXITCODE
$hashes=[ordered]@{};foreach($source in ($sources+@($test,$PSCommandPath))){$hashes[$source.Substring($repo.Length+1).Replace('\','/')]=(Get-FileHash -LiteralPath $source -Algorithm SHA256).Hash.ToLowerInvariant()}
[ordered]@{utc=[DateTime]::UtcNow.ToString('o');command='powershell -NoProfile -File scripts/test-xiaomi-distributed-notifications.ps1';result=$(if($testExit-eq0){'passed'}else{'failed'});tests=4;sources=$hashes;apk=$null;scope='SDK37/libxposed102 compile of independent native source/sink; new bounded codec JVM tests only. Not OEM process execution, integration, transport or native delivery.'}|ConvertTo-Json -Depth 6|Set-Content -LiteralPath (Join-Path $out 'ledger.json') -Encoding utf8
if($testExit-ne0){throw 'Distributed notification codec tests failed'}
