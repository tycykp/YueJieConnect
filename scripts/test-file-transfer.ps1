param([switch]$ProtocolOnly, [switch]$CompileOnly, [switch]$SendOnly, [switch]$ReceiveOnly, [switch]$EndpointOnly, [switch]$LeaseOnly, [switch]$NegotiationOnly)
$ErrorActionPreference = 'Stop'
$repo = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
$out = Join-Path $repo 'build/file-transfer-validation'
$classes = Join-Path $out 'classes'
New-Item -ItemType Directory -Force -Path $classes | Out-Null
$jdk = 'C:\Program Files\Android\Android Studio\jbr\bin'
$gradleCache = Join-Path $env:USERPROFILE '.gradle\caches\modules-2\files-2.1'
$junit = (Get-ChildItem -LiteralPath (Join-Path $gradleCache 'junit\junit\4.13.2') -Filter '*.jar' -Recurse | Select-Object -First 1).FullName
$hamcrest = (Get-ChildItem -LiteralPath (Join-Path $gradleCache 'org.hamcrest\hamcrest-core\1.3') -Filter '*.jar' -Recurse | Select-Object -First 1).FullName
$android = Join-Path $env:LOCALAPPDATA 'Android\Sdk\platforms\android-37.0\android.jar'
$xposed = (Get-ChildItem -LiteralPath (Join-Path $env:USERPROFILE '.gradle/caches/9.7.1/transforms') -Filter 'api-102.0.0-api.jar' -Recurse | Select-Object -First 1).FullName
$src = Join-Path $repo 'android/module/src/main/java/io/github/qiangjian/connect'
$tests = @('FileTransferEngineTest.java', 'FileTransferProtocolTest.java', 'FileTransferSendCoordinatorTest.java', 'FileTransferReceiveLeaseTest.java', 'FileTransferNativeResultTest.java', 'FileTransferEndpointTest.java') | ForEach-Object { Join-Path $repo "android/module/src/test/java/io/github/qiangjian/connect/$_" }
$sources = @('FileTransferEngine.java', 'FileTransferSource.java', 'FileTransferProtocol.java', 'FileTransferClient.java', 'FileTransferEndpoint.java', 'FileTransferUris.java', 'OplusFileCodec.java', 'OplusFileSink.java', 'XiaomiFileCodec.java', 'FileTransferSendCoordinator.java', 'OplusFileAdapter.java', 'XiaomiFileAdapter.java', 'OplusFileHooks.java', 'FileTransferReceiveLease.java', 'FileTransferReceiveDocuments.java', 'XiaomiFileReceiveHooks.java', 'FileTransferNativeResult.java', 'XiaomiFileSendHooks.java') | ForEach-Object { Join-Path $src $_ }
$sources += Join-Path $src 'FileTransferNegotiation.java'
$sources += @('FileTransferBridge.java','BridgeClient.java','IBridge.java','NativeLogicalSessions.java') | ForEach-Object { Join-Path $src $_ }
$tests += Join-Path $repo 'android/module/src/test/java/io/github/qiangjian/connect/FileTransferNegotiationTest.java'
python (Join-Path $repo 'scripts/prepare-file-transfer-json.py')
if ($LASTEXITCODE -ne 0) { throw 'Android JSON test source preparation failed' }
$jsonSources = @(Get-ChildItem -LiteralPath (Join-Path $out 'json-src/org/json') -Filter '*.java' | ForEach-Object { $_.FullName })
& (Join-Path $jdk 'javac.exe') --release 17 -encoding UTF-8 -cp "$android;$junit;$xposed" -d $classes @sources @tests @jsonSources
if ($LASTEXITCODE -ne 0) { throw 'File module compilation failed' }
$testClasses = @('io.github.qiangjian.connect.FileTransferProtocolTest')
if ($NegotiationOnly) { $testClasses = @('io.github.qiangjian.connect.FileTransferNegotiationTest') }
elseif ($LeaseOnly) { $testClasses = @('io.github.qiangjian.connect.FileTransferReceiveLeaseTest') }
elseif ($EndpointOnly) { $testClasses = @('io.github.qiangjian.connect.FileTransferEndpointTest') }
elseif ($ReceiveOnly) { $testClasses = @('io.github.qiangjian.connect.FileTransferEngineTest', 'io.github.qiangjian.connect.FileTransferReceiveLeaseTest', 'io.github.qiangjian.connect.FileTransferNativeResultTest', 'io.github.qiangjian.connect.FileTransferEndpointTest') }
elseif ($SendOnly) { $testClasses += 'io.github.qiangjian.connect.FileTransferSendCoordinatorTest' }
elseif (-not $ProtocolOnly) { $testClasses += 'io.github.qiangjian.connect.FileTransferEngineTest', 'io.github.qiangjian.connect.FileTransferSendCoordinatorTest', 'io.github.qiangjian.connect.FileTransferReceiveLeaseTest', 'io.github.qiangjian.connect.FileTransferNativeResultTest', 'io.github.qiangjian.connect.FileTransferEndpointTest' }
if ($CompileOnly) { $result = @('PASS: file module javac compilation only'); $exitCode = 0; $testClasses = @() }
else { $result = & (Join-Path $jdk 'java.exe') -cp "$classes;$junit;$hamcrest" org.junit.runner.JUnitCore @testClasses 2>&1; $exitCode = $LASTEXITCODE }
$suffix = if ($CompileOnly) { '-compile' } elseif ($NegotiationOnly) { '-negotiation' } elseif ($LeaseOnly) { '-lease-repair' } elseif ($EndpointOnly) { '-endpoint' } elseif ($ReceiveOnly) { '-receive' } elseif ($SendOnly) { '-send' } elseif ($ProtocolOnly) { '-protocol' } else { '' }
$result | Set-Content -LiteralPath (Join-Path $out "junit$suffix.txt") -Encoding utf8
$hashes = @($sources + $tests + $PSCommandPath + (Join-Path $repo 'scripts/prepare-file-transfer-json.py')) | ForEach-Object { [ordered]@{ path = $_.Substring($repo.Length + 1).Replace('\', '/'); sha256 = (Get-FileHash -LiteralPath $_ -Algorithm SHA256).Hash.ToLowerInvariant() } }
[ordered]@{ utc = [DateTime]::UtcNow.ToString('o'); command = "powershell -NoProfile -File scripts/test-file-transfer.ps1 $(if ($CompileOnly) { '-CompileOnly' } elseif ($NegotiationOnly) { '-NegotiationOnly' } elseif ($LeaseOnly) { '-LeaseOnly' } elseif ($EndpointOnly) { '-EndpointOnly' } elseif ($ReceiveOnly) { '-ReceiveOnly' } elseif ($SendOnly) { '-SendOnly' } elseif ($ProtocolOnly) { '-ProtocolOnly' })"; exit_code = $exitCode; tests = $testClasses; scope = 'New file modules javac; selected JUnit tests, Android JSON sources. No APK or device execution.'; sources = $hashes } | ConvertTo-Json -Depth 6 | Set-Content -LiteralPath (Join-Path $out "ledger$suffix.json") -Encoding utf8
$result
if ($exitCode -ne 0) { throw 'File transfer tests failed' }
