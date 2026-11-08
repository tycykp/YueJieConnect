param([switch]$CompileOnly, [switch]$IntegrationOnly, [switch]$Round2Only, [switch]$ReceiveOnly, [switch]$ReceiptsOnly, [switch]$PayloadOnly, [switch]$RepairOnly, [switch]$SyncOnly, [switch]$ContinuationOnly)
$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $PSScriptRoot
$out = Join-Path $root 'build/app-relay-validation'
New-Item -ItemType Directory -Force -Path "$out/classes" | Out-Null
$java = 'C:/Program Files/Android/Android Studio/jbr/bin'
$sdk = "$env:LOCALAPPDATA/Android/Sdk/platforms/android-37.0/android.jar"
$cache = "$env:USERPROFILE/.gradle/caches"
$api = Get-ChildItem "$cache/9.7.1/transforms/*/transformed/api-102.0.0/jars/classes.jar" | Select-Object -First 1 -ExpandProperty FullName
$junit = Get-ChildItem "$cache/modules-2/files-2.1/junit/junit/4.13.2/*/junit-4.13.2.jar" | Select-Object -First 1 -ExpandProperty FullName
$hamcrest = Get-ChildItem "$cache/modules-2/files-2.1/org.hamcrest/hamcrest-core/1.3/*/hamcrest-core-1.3.jar" | Select-Object -First 1 -ExpandProperty FullName
$sources = @(Get-ChildItem "$root/android/module/src/main/java/io/github/qiangjian/connect/*Relay*.java" | Where-Object Name -Match '^(AppRelay|XiaomiRelay|OplusRelay)' | Select-Object -ExpandProperty FullName)
$sources += Join-Path $root 'android/module/src/debug/java/io/github/qiangjian/connect/AppRelayNativeProbe.java'
$dependencies = @('BridgeClient.java','IBridge.java','NativeLogicalSessions.java','FileTransferClient.java','FileTransferEngine.java','FileTransferProtocol.java','FileTransferSource.java','FileTransferUris.java','FileTransferEndpoint.java') | ForEach-Object { Join-Path $root "android/module/src/main/java/io/github/qiangjian/connect/$_" }
$dependencies += @('FileTransferReceiveDocuments.java','FileTransferReceiveLease.java','FileTransferNegotiation.java','FileTransferBridge.java') | ForEach-Object { Join-Path $root "android/module/src/main/java/io/github/qiangjian/connect/$_" }
$tests = @(Get-ChildItem "$root/android/module/src/test/java/io/github/qiangjian/connect/AppRelay*Test.java" | Select-Object -ExpandProperty FullName)
$tests += Join-Path $root 'android/module/src/test/java/io/github/qiangjian/connect/OplusRelayPayloadTest.java'
$tests += Join-Path $root 'android/module/src/test/java/io/github/qiangjian/connect/OplusRelayControlTest.java'
$fixtures = @('com/xiaomi/mirror/message/RelayAppMessage.java','com/xiaomi/mirror/message/RecentAppsMessage.java','com/oplus/synergy/bean/RelayInfo.java','com/oplus/synergy/bean/AbilityProfile.java') | ForEach-Object { Join-Path $root "android/module/src/test/java/$_" }
# Reuse the already prepared Android JSON source without writing another task's build directory.
$jsonSources = @(Get-ChildItem "$root/build/file-transfer-validation/json-src/org/json/*.java" | Select-Object -ExpandProperty FullName)
if ($jsonSources.Count -ne 6) { throw 'Expected the six recorded Android JSON test sources from file-transfer validation' }
$inputPaths = @($sources + $dependencies + $tests + $fixtures + $jsonSources + $PSCommandPath)
$beforeHashes = @($inputPaths | ForEach-Object { $hash = Get-FileHash -LiteralPath $_ -Algorithm SHA256; [pscustomobject]@{path=$_.Substring($root.Length+1);sha256=$hash.Hash.ToLowerInvariant()} })
$ErrorActionPreference = 'Continue'
$compileOutput = & "$java/javac.exe" '-J-Duser.language=en' -encoding UTF-8 --release 17 -classpath "$sdk;$api;$junit" -d "$out/classes" @sources @dependencies @tests @fixtures @jsonSources 2>&1
$compileExit = $LASTEXITCODE
$ErrorActionPreference = 'Stop'
$compileOutput | Tee-Object "$out/compile-integration.log"
if ($compileExit -ne 0) { throw 'Relay Java compilation failed' }
$hashes = @($inputPaths | ForEach-Object { $hash = Get-FileHash -LiteralPath $_ -Algorithm SHA256; [pscustomobject]@{path=$_.Substring($root.Length+1);sha256=$hash.Hash.ToLowerInvariant()} })
$races = @(for ($index=0; $index -lt $hashes.Count; $index++) { if ($beforeHashes[$index].sha256 -ne $hashes[$index].sha256) { [pscustomobject]@{path=$hashes[$index].path;before=$beforeHashes[$index].sha256;after=$hashes[$index].sha256} } })
$races | ConvertTo-Json | Set-Content "$out/compile-source-races.json" -Encoding utf8
if ($races.Count) { throw 'Relay compile inputs changed while javac was reading; no passing ledger recorded' }
$hashes | ConvertTo-Json | Set-Content "$out/compile-source-sha256.json" -Encoding utf8
$selected = @()
if (!$CompileOnly) {
    $selected = @('io.github.qiangjian.connect.AppRelayCatalogTest','io.github.qiangjian.connect.AppRelayCodecTest','io.github.qiangjian.connect.AppRelayFileTokensTest')
    if (!$IntegrationOnly) { $selected += @('io.github.qiangjian.connect.AppRelayRequestTest','io.github.qiangjian.connect.AppRelayTasksTest',
        'io.github.qiangjian.connect.OplusRelayPayloadTest','io.github.qiangjian.connect.AppRelaySenderTest','io.github.qiangjian.connect.AppRelayDirectoryTest','io.github.qiangjian.connect.AppRelayReceiveTokensTest') }
    if ($Round2Only) { $selected = @('io.github.qiangjian.connect.AppRelayRequestTest','io.github.qiangjian.connect.AppRelayTasksTest','io.github.qiangjian.connect.AppRelayCodecTest','io.github.qiangjian.connect.OplusRelayPayloadTest','io.github.qiangjian.connect.AppRelaySenderTest','io.github.qiangjian.connect.AppRelayDirectoryTest') }
    if ($ReceiveOnly) { $selected = @('io.github.qiangjian.connect.AppRelayReceiveTokensTest') }
    if ($ReceiptsOnly) { $selected = @('io.github.qiangjian.connect.AppRelayCodecTest','io.github.qiangjian.connect.AppRelaySenderTest') }
    if ($PayloadOnly) { $selected = @('io.github.qiangjian.connect.OplusRelayPayloadTest') }
    if ($RepairOnly) { $selected = @('io.github.qiangjian.connect.AppRelaySyncTest','io.github.qiangjian.connect.OplusRelayControlTest') }
    if ($SyncOnly) { $selected = @('io.github.qiangjian.connect.AppRelaySyncTest') }
    if ($ContinuationOnly) { $selected = @('io.github.qiangjian.connect.AppRelaySyncTest','io.github.qiangjian.connect.AppRelaySyncClientTest','io.github.qiangjian.connect.AppRelayDocumentDecisionsTest','io.github.qiangjian.connect.AppRelayCodecTest') }
    $testLog = if ($ContinuationOnly) { 'tests-continuation.log' } elseif ($SyncOnly) { 'tests-sync.log' } elseif ($RepairOnly) { 'tests-repair.log' } elseif ($Round2Only) { 'tests-round2.log' } elseif ($ReceiveOnly) { 'tests-receive.log' } elseif ($ReceiptsOnly) { 'tests-receipts.log' } elseif ($PayloadOnly) { 'tests-payload.log' } else { 'tests-integration.log' }
    & "$java/java.exe" -classpath "$out/classes;$junit;$hamcrest" org.junit.runner.JUnitCore @selected 2>&1 | Tee-Object "$out/$testLog"
    if ($LASTEXITCODE -ne 0) { throw 'Relay unit tests failed' }
    Write-Output 'App-relay targeted JVM tests passed.'
}
$ledger = if ($CompileOnly) { 'integration-compile-ledger.json' } elseif ($ContinuationOnly) { 'continuation-tests-ledger.json' } elseif ($SyncOnly) { 'sync-tests-ledger.json' } elseif ($RepairOnly) { 'repair-tests-ledger.json' } elseif ($Round2Only) { 'round2-tests-ledger.json' } elseif ($ReceiveOnly) { 'receive-tests-ledger.json' } elseif ($ReceiptsOnly) { 'receipts-tests-ledger.json' } elseif ($PayloadOnly) { 'payload-tests-ledger.json' } else { 'integration-tests-ledger.json' }
[ordered]@{utc=[DateTime]::UtcNow.ToString('o');command="powershell -NoProfile -File scripts/test-app-relay.ps1 $(if($CompileOnly){'-CompileOnly'}elseif($ContinuationOnly){'-ContinuationOnly'}elseif($SyncOnly){'-SyncOnly'}elseif($RepairOnly){'-RepairOnly'}elseif($Round2Only){'-Round2Only'}elseif($ReceiveOnly){'-ReceiveOnly'}elseif($ReceiptsOnly){'-ReceiptsOnly'}elseif($PayloadOnly){'-PayloadOnly'}elseif($IntegrationOnly){'-IntegrationOnly'})"; tests=$selected; result='passed'; sources=$hashes; before_sources=$beforeHashes; source_races=$races; apk='none'; device='none'} | ConvertTo-Json -Depth 5 | Set-Content "$out/$ledger" -Encoding utf8
Write-Output 'App-relay isolated Java compilation passed. No Gradle, APK install, or device access.'
