param(
    [string]$JavaRoot = 'C:\Program Files\Android\Android Studio\jbr',
    [string]$SdkRoot = "$env:LOCALAPPDATA\Android\Sdk",
    [switch]$CompileOnly
)
$ErrorActionPreference = 'Stop'
$project = Split-Path $PSScriptRoot -Parent
$output = Join-Path $project 'build/camera-validation'
$run = Join-Path $output (Get-Date -Format 'yyyyMMdd-HHmmss-fff')
New-Item -ItemType Directory -Path $run -Force | Out-Null
$main = Join-Path $project 'android/module/src/main/java/io/github/yuejie/connect'
$own = @(Get-ChildItem -LiteralPath $main -Filter 'CameraShare*.java') + @(Get-ChildItem -LiteralPath $main -Filter 'XiaomiCamera*.java') + @(Get-ChildItem -LiteralPath $main -Filter 'OplusCamera*.java')
$dependencies = @('ScreenCastVideoEncoder','ScreenCastVideoDecoder','ScreenCastFormat','ScreenCastPacket','ScreenCastFrameQueue') | ForEach-Object { Get-Item -LiteralPath (Join-Path $main ($_ + '.java')) }
$sources = @($own + $dependencies | ForEach-Object { $_.FullName })
$androidJar = Join-Path $SdkRoot 'platforms/android-37.0/android.jar'
$xposed = (Get-ChildItem -LiteralPath (Join-Path $env:USERPROFILE '.gradle/caches/9.7.1/transforms') -Filter 'api-102.0.0-api.jar' -Recurse | Select-Object -First 1).FullName
if (-not $xposed) { throw 'Existing libxposed API102 jar not found; do not change dependencies.' }
$compileArgs = @('--release','17','-encoding','UTF-8','-cp',"$androidJar;$xposed",'-d',$run) + $sources
$ErrorActionPreference = 'Continue' # Windows PowerShell wraps native stderr notes as error records.
& (Join-Path $JavaRoot 'bin/javac.exe') '-J-Duser.language=en' @compileArgs 2>&1 | Out-File -LiteralPath (Join-Path $run 'compile.txt') -Encoding utf8
$ErrorActionPreference = 'Stop'
if ($LASTEXITCODE -ne 0) { throw "Camera Android compilation failed; see $run/compile.txt" }
$testSource = Join-Path $PSScriptRoot 'camera-sharing/CameraShareModelChecks.java'
$scope = 'Android API 37 compile only; model checks not rerun; no device capture'
$command = 'powershell -NoProfile -File scripts/test-camera-sharing.ps1 -CompileOnly'
$verifiedPaths = @($sources + $PSCommandPath)
$testOutput = 'camera-compile-only: PASS'
if (-not $CompileOnly) {
    $ErrorActionPreference = 'Continue'
    & (Join-Path $JavaRoot 'bin/javac.exe') '-J-Duser.language=en' --release 17 -encoding UTF-8 -cp $run -d $run $testSource 2>&1 | Out-File -LiteralPath (Join-Path $run 'test-compile.txt') -Encoding utf8
    $ErrorActionPreference = 'Stop'
    if ($LASTEXITCODE -ne 0) { throw "Camera checks compilation failed; see $run/test-compile.txt" }
    $ErrorActionPreference = 'Continue'
    $testOutput = & (Join-Path $JavaRoot 'bin/java.exe') -cp $run io.github.yuejie.connect.CameraShareModelChecks 2>&1
    $testExit = $LASTEXITCODE
    $ErrorActionPreference = 'Stop'
    $testOutput | Out-File -LiteralPath (Join-Path $run 'test.txt') -Encoding utf8
    if ($testExit -ne 0) { throw "Camera checks failed; see $run/test.txt" }
    $scope = 'Android API 37 compile and camera model checks only; no device capture'
    $command = 'powershell -NoProfile -File scripts/test-camera-sharing.ps1'
    $verifiedPaths += $testSource
}
$hashes = $verifiedPaths | ForEach-Object {
    [ordered]@{ path = $_; sha256 = (Get-FileHash -LiteralPath $_ -Algorithm SHA256).Hash }
}
[ordered]@{
    result = 'PASS'; scope = $scope; command = $command;
    apk = $null; device_tested = $false; sources = $hashes
} | ConvertTo-Json -Depth 6 | Out-File -LiteralPath (Join-Path $run 'result.json') -Encoding utf8
Write-Output $testOutput
Write-Output "Evidence: $run"
