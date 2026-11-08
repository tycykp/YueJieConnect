$ErrorActionPreference = 'Stop'
$repo=Split-Path -Parent $PSScriptRoot
$out=Join-Path $repo 'build/notifications-validation/repair-round3/distributed'
python (Join-Path $PSScriptRoot 'prepare-xiaomi-notification-reflection.py')
if($LASTEXITCODE-ne0){throw 'Native DTO preparation failed'}
$javaBin='C:\Program Files\Android\Android Studio\jbr\bin'
$cache=Join-Path $env:USERPROFILE '.gradle/caches/modules-2/files-2.1'
$junit=Get-ChildItem -LiteralPath (Join-Path $cache 'junit/junit/4.13.2') -Recurse -Filter '*.jar'|Select-Object -First 1 -ExpandProperty FullName
$hamcrest=Get-ChildItem -LiteralPath (Join-Path $cache 'org.hamcrest/hamcrest-core/1.3') -Recurse -Filter '*.jar'|Select-Object -First 1 -ExpandProperty FullName
$codec=Join-Path $repo 'android/module/src/main/java/io/github/qiangjian/connect/XiaomiNotificationDistributedCodec.java'
$test=Join-Path $PSScriptRoot 'notifications/XiaomiNotificationDistributedReflectionTest.java'
$sources=@(Get-ChildItem -LiteralPath (Join-Path $out 'reflection-src') -Recurse -Filter '*.java'|ForEach-Object FullName)
& (Join-Path $javaBin 'javac.exe') --release 17 -encoding UTF-8 -cp $junit -d $out $codec $test @sources 2>&1|Tee-Object -FilePath (Join-Path $out 'reflection-compile.log')
if($LASTEXITCODE-ne0){throw 'Native DTO reflection compile failed'}
& (Join-Path $javaBin 'java.exe') -cp "$out;$junit;$hamcrest" org.junit.runner.JUnitCore io.github.qiangjian.connect.XiaomiNotificationDistributedReflectionTest 2>&1|Tee-Object -FilePath (Join-Path $out 'reflection-tests.log')
$testExit=$LASTEXITCODE
$hashes=[ordered]@{};foreach($file in @($codec,$test,$PSCommandPath,(Join-Path $PSScriptRoot 'prepare-xiaomi-notification-reflection.py'))){$hashes[$file.Substring($repo.Length+1).Replace('\','/')]=(Get-FileHash -LiteralPath $file -Algorithm SHA256).Hash.ToLowerInvariant()}
[ordered]@{utc=[DateTime]::UtcNow.ToString('o');command='powershell -NoProfile -File scripts/test-xiaomi-distributed-reflection.ps1';tests=2;result=$(if($testExit-eq0){'passed'}else{'failed'});sources=$hashes;scope='New DTO reflection tests against captured original OEM Java implementation with only annotations stripped. No unchanged four codec suites, devices, Gradle/Cargo or native UI execution.';apk=$null}|ConvertTo-Json -Depth 5|Set-Content -LiteralPath (Join-Path $out 'reflection-ledger.json') -Encoding utf8
if($testExit-ne0){throw 'Native DTO reflection tests failed'}
