param(
    [string]$JavaRoot = 'C:\Program Files\Android\Android Studio\jbr',
    [string]$SdkRoot = "$env:LOCALAPPDATA\Android\Sdk",
    [switch]$CompileOnly
)
$ErrorActionPreference = 'Stop'
$project = Split-Path $PSScriptRoot -Parent
$run = Join-Path $project ('build/camera-validation/native-consumers-' + (Get-Date -Format 'yyyyMMdd-HHmmss-fff'))
New-Item -ItemType Directory -Path $run -Force | Out-Null
$main = Join-Path $project 'android/module/src/main/java/io/github/qiangjian/connect'
$own = @('CameraShareStillRequest','CameraShareJpeg','CameraShareStillReceipt','CameraShareStillSource','XiaomiCameraHardwareBinding','XiaomiCameraStillConsumer','XiaomiCameraNativeRequests')
$dependencies = @('CameraShareConfig','CameraShareLease')
$sources = @($own + $dependencies | ForEach-Object { Join-Path $main ($_ + '.java') })
$test = Join-Path $PSScriptRoot 'camera-sharing/CameraShareNativeConsumerChecks.java'
$androidJar = Join-Path $SdkRoot 'platforms/android-37.0/android.jar'
$ErrorActionPreference = 'Continue'
& (Join-Path $JavaRoot 'bin/javac.exe') '-J-Duser.language=en' --release 17 -encoding UTF-8 -cp $androidJar -d $run @sources $test 2>&1 | Out-File (Join-Path $run 'compile.txt') -Encoding utf8
$compileExit = $LASTEXITCODE
$ErrorActionPreference = 'Stop'
if ($compileExit -ne 0) { throw "Camera native consumers compile failed: $run/compile.txt" }
$testOutput = 'camera-native-consumer-compile-only: PASS'
$command = 'powershell -NoProfile -File scripts/test-camera-native-consumers.ps1 -CompileOnly'
$scope = 'New native consumers API37 compile only; unchanged model checks not rerun. No Camera2 exposure, native Surface write, preview render or APK claim.'
if (-not $CompileOnly) {
    $ErrorActionPreference = 'Continue'
    $testOutput = & (Join-Path $JavaRoot 'bin/java.exe') -cp $run io.github.qiangjian.connect.CameraShareNativeConsumerChecks 2>&1
    $testExit = $LASTEXITCODE
    $ErrorActionPreference = 'Stop'
    $testOutput | Out-File (Join-Path $run 'test.txt') -Encoding utf8
    if ($testExit -ne 0) { throw "Camera native consumers checks failed: $run/test.txt" }
    $command = 'powershell -NoProfile -File scripts/test-camera-native-consumers.ps1'
    $scope = 'New still/JPEG/replay/peer selection checks and API37 compile only. No Camera2 exposure, native Surface write, preview render or APK claim.'
}
$hashes = @($sources + $test + $PSCommandPath) | ForEach-Object {
    [ordered]@{ path = $_; sha256 = (Get-FileHash -LiteralPath $_ -Algorithm SHA256).Hash }
}
[ordered]@{
    result = 'PASS'; command = $command; scope = $scope;
    sources = $hashes; apk = $null; device_tested = $false
} | ConvertTo-Json -Depth 6 | Out-File (Join-Path $run 'result.json') -Encoding utf8
Write-Output $testOutput
Write-Output "Evidence: $run"
