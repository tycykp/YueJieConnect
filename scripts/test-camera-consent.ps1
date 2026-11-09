param(
    [string]$JavaRoot = 'C:\Program Files\Android\Android Studio\jbr',
    [string]$SdkRoot = "$env:LOCALAPPDATA\Android\Sdk"
)
$ErrorActionPreference = 'Stop'
$project = Split-Path $PSScriptRoot -Parent
$run = Join-Path $project ('build/camera-validation/consent-' + (Get-Date -Format 'yyyyMMdd-HHmmss-fff'))
New-Item -ItemType Directory -Path $run -Force | Out-Null
$main = Join-Path $project 'android/module/src/main/java/io/github/yuejie/connect'
$sources = @('CameraShareConsentRegistry','CameraShareConsentActivity','CameraShareSourceService',
    'CameraShareConfig','CameraShareLease','CameraShareSource','CameraShareSender','CameraShareStillSession',
    'CameraShareStillRequest','CameraShareStillSource','CameraShareJpeg','ScreenCastVideoEncoder',
    'ScreenCastFormat','ScreenCastPacket','ScreenCastFrameQueue') | ForEach-Object { Join-Path $main ($_ + '.java') }
$test = Join-Path $PSScriptRoot 'camera-sharing/CameraShareConsentChecks.java'
$androidJar = Join-Path $SdkRoot 'platforms/android-37.0/android.jar'
$ErrorActionPreference = 'Continue'
& (Join-Path $JavaRoot 'bin/javac.exe') '-J-Duser.language=en' --release 17 -encoding UTF-8 -cp $androidJar -d $run @sources $test 2>&1 |
    Out-File (Join-Path $run 'compile.txt') -Encoding utf8
$compileExit = $LASTEXITCODE
$ErrorActionPreference = 'Stop'
if ($compileExit -ne 0) { throw "Camera consent compilation failed: $run/compile.txt" }
$ErrorActionPreference = 'Continue'
$output = & (Join-Path $JavaRoot 'bin/java.exe') -cp $run io.github.yuejie.connect.CameraShareConsentChecks 2>&1
$testExit = $LASTEXITCODE
$ErrorActionPreference = 'Stop'
$output | Out-File (Join-Path $run 'test.txt') -Encoding utf8
if ($testExit -ne 0) { throw "Camera consent checks failed: $run/test.txt" }
$hashes = @($sources + $test + $PSCommandPath + $androidJar) | ForEach-Object {
    [ordered]@{path=$_; sha256=(Get-FileHash -LiteralPath $_ -Algorithm SHA256).Hash}
}
[ordered]@{result='PASS'; command='powershell -NoProfile -File scripts/test-camera-consent.ps1'; sources=$hashes;
    scope='New per-native-request consent state checks plus real Android API37 compilation. No permission/FGS/Camera2/native consumer/APK/device claim.';
    apk=$null; device_tested=$false
} | ConvertTo-Json -Depth 6 | Out-File (Join-Path $run 'result.json') -Encoding utf8
Write-Output $output
Write-Output "Evidence: $run"
