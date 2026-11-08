param([string]$OutputDirectory = 'build/screen-input-validation', [switch]$CompileOnly,
    [string[]]$TestClasses = @('ScreenCastPacketTest','ScreenCastFrameQueueTest','RemoteInputSessionTest','ScreenCastSessionTest','RemoteInputDesktopSessionTest','XiaomiScreenInputCodecTest','RemoteInputDesktopExecutorTest','ScreenCastNativeConsentTest','RemoteInputSenderTest','OplusScreenNativeProofTest'))
$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $PSScriptRoot
$out = Join-Path $root $OutputDirectory
$classes = Join-Path $out 'classes'
New-Item -ItemType Directory -Force -Path $classes | Out-Null
$java = 'C:\Program Files\Android\Android Studio\jbr\bin'
$sdk = Join-Path $env:LOCALAPPDATA 'Android/Sdk/platforms/android-37.0/android.jar'
$junit = Get-ChildItem -LiteralPath (Join-Path $env:USERPROFILE '.gradle/caches/modules-2/files-2.1/junit/junit/4.13.2') -Recurse -Filter '*.jar' | Select-Object -First 1 -ExpandProperty FullName
$hamcrest = Get-ChildItem -LiteralPath (Join-Path $env:USERPROFILE '.gradle/caches/modules-2/files-2.1/org.hamcrest/hamcrest-core/1.3') -Recurse -Filter '*.jar' | Select-Object -First 1 -ExpandProperty FullName
$xposedAar = Get-ChildItem -LiteralPath (Join-Path $env:USERPROFILE '.gradle/caches/modules-2/files-2.1/io.github.libxposed/api/102.0.0') -Recurse -Filter '*.aar' | Select-Object -First 1 -ExpandProperty FullName
Add-Type -AssemblyName System.IO.Compression.FileSystem
$archive = [IO.Compression.ZipFile]::OpenRead($xposedAar)
$xposed = Join-Path $out 'xposed-api.jar'
try { [IO.Compression.ZipFileExtensions]::ExtractToFile($archive.GetEntry('classes.jar'),$xposed,$true) } finally { $archive.Dispose() }
$sourceDir = Join-Path $root 'android/module/src/main/java/io/github/qiangjian/connect'
$testDir = Join-Path $root 'android/module/src/test/java/io/github/qiangjian/connect'
$sources = @('ScreenCastFormat','ScreenCastPacket','ScreenCastFrameQueue','ScreenCastVideoEncoder','ScreenCastVideoDecoder','ScreenCastProjectionSource','ScreenCastSession','ScreenCastNativeSession','ScreenCastBridge','RemoteInputSession','RemoteInputImeTarget','RemoteInputPointerTarget','RemoteInputDesktopSession','RemoteInputDesktopTarget','RemoteInputDesktopExecutor','RemoteInputReceiver','RemoteInputPacket','RemoteInputOplusTarget','OplusScreenInputAdapter','OplusScreenFocusAdapter','XiaomiScreenSinkAdapter','XiaomiScreenRequestAdapter','OplusScreenSourceAdapter','OplusScreenRequestAdapter','OplusScreenSessionLease','XiaomiScreenInputCodec','ScreenCastNativeConsent','XiaomiScreenCirculateDelegate','OplusScreenLinkerAdapter','RemoteInputSender','XiaomiScreenInputAdapter') | ForEach-Object { Join-Path $sourceDir "${_}.java" }
$tests = @('ScreenCastPacketTest','ScreenCastFrameQueueTest','RemoteInputSessionTest','ScreenCastSessionTest','ScreenCastNativeSessionTest','ScreenCastBridgeTest','RemoteInputDesktopSessionTest','XiaomiScreenInputCodecTest','RemoteInputDesktopExecutorTest','RemoteInputReceiverTest','RemoteInputPacketTest','RemoteInputOplusTargetTest','OplusScreenFocusAdapterTest','OplusScreenReleaseTest','OplusScreenSessionLeaseTest','ScreenCastNativeConsentTest','RemoteInputSenderTest','OplusScreenNativeProofTest') | ForEach-Object { Join-Path $testDir "${_}.java" }
& (Join-Path $java 'javac.exe') --release 17 -encoding UTF-8 -cp "$sdk;$xposed;$junit;$hamcrest" -d $classes @sources @tests 2>&1 | Tee-Object -FilePath (Join-Path $out 'compile.log')
if ($LASTEXITCODE -ne 0) { throw 'screen-input compilation failed' }
if (-not $CompileOnly) {
    $qualified = @($TestClasses | ForEach-Object { "io.github.qiangjian.connect.$_" })
    & (Join-Path $java 'java.exe') -cp "$classes;$junit;$hamcrest" org.junit.runner.JUnitCore @qualified 2>&1 | Tee-Object -FilePath (Join-Path $out 'tests.log')
    if ($LASTEXITCODE -ne 0) { throw 'screen-input tests failed' }
}
@($sources + $tests + $PSCommandPath) | ForEach-Object { Get-FileHash -LiteralPath $_ -Algorithm SHA256 } | Select-Object Path,Hash | ConvertTo-Json | Set-Content -LiteralPath (Join-Path $out 'source-sha256.json') -Encoding utf8
