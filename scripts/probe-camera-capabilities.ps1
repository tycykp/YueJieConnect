param([string[]]$Serials = @('b59f8656','3B161Q00YN200000'))
$ErrorActionPreference = 'Stop'
$project = Split-Path $PSScriptRoot -Parent
$out = Join-Path $project ('build/camera-validation/metadata-' + (Get-Date -Format 'yyyyMMdd-HHmmss-fff'))
$classes = Join-Path $out 'classes'
$dex = Join-Path $out 'dex'
New-Item -ItemType Directory -Force -Path $classes,$dex | Out-Null
$javaBin = 'C:\Program Files\Android\Android Studio\jbr\bin'
$sdk = Join-Path $env:LOCALAPPDATA 'Android/Sdk'
$androidJar = Join-Path $sdk 'platforms/android-37.0/android.jar'
$adb = Join-Path $sdk 'platform-tools/adb.exe'
$source = Join-Path $project 'android/module/src/debug/java/io/github/qiangjian/connect/CameraShareCapabilityProbe.java'
$main = Join-Path $project 'android/module/src/main/java/io/github/qiangjian/connect'
$sources = @($source) + @('CameraShareConfig','CameraShareLease','CameraShareSource','CameraShareStillSession','CameraShareStillRequest','CameraShareStillSource','CameraShareJpeg' | ForEach-Object { Join-Path $main ($_ + '.java') })
$ErrorActionPreference = 'Continue'
& (Join-Path $javaBin 'javac.exe') '-J-Duser.language=en' --release 17 -encoding UTF-8 -cp $androidJar -d $classes @sources 2>&1 | Out-File (Join-Path $out 'compile.log') -Encoding utf8
$compileExit = $LASTEXITCODE
$ErrorActionPreference = 'Stop'
if ($compileExit -ne 0) { throw 'Camera metadata probe compilation failed' }
$classJar = Join-Path $out 'classes.jar'
& (Join-Path $javaBin 'jar.exe') cf $classJar -C $classes .
if ($LASTEXITCODE -ne 0) { throw 'Camera metadata probe jar failed' }
$oldJava = $env:JAVA_HOME
try {
    $env:JAVA_HOME = Split-Path $javaBin -Parent
    & (Join-Path $sdk 'build-tools/37.0.0/d8.bat') --min-api 37 --lib $androidJar --output $dex $classJar
    if ($LASTEXITCODE -ne 0) { throw 'Camera metadata probe dex failed' }
} finally { $env:JAVA_HOME = $oldJava }
$probe = Join-Path $out 'qj-camera-metadata.jar'
& (Join-Path $javaBin 'jar.exe') cf $probe -C $dex classes.dex
if ($LASTEXITCODE -ne 0) { throw 'Camera metadata dex jar failed' }
$lockDir = Join-Path $project 'build/coordination-locks'
New-Item -ItemType Directory -Force -Path $lockDir | Out-Null
$lockPath = Join-Path $lockDir 'devices.lock'
$lock = $null
try {
    $lock = [IO.File]::Open($lockPath,[IO.FileMode]::CreateNew,[IO.FileAccess]::ReadWrite,[IO.FileShare]::Read)
    $owner = [Text.Encoding]::UTF8.GetBytes("camera-sharing 01a1bb24-1803-7351-a7d4-ed54ddbce294 $([DateTimeOffset]::Now.ToString('o'))")
    $lock.Write($owner,0,$owner.Length)
    $lock.Flush()
    foreach ($serial in $Serials) {
        if ($serial -notin @('b59f8656','3B161Q00YN200000')) { throw 'Unknown camera test device' }
        & $adb -s $serial push $probe /data/local/tmp/qj-camera-metadata.jar
        if ($LASTEXITCODE -ne 0) { throw "Metadata probe push failed $serial" }
        try {
            $ErrorActionPreference = 'Continue'
            $lines = & $adb -s $serial shell 'CLASSPATH=/data/local/tmp/qj-camera-metadata.jar timeout 12 app_process /system/bin io.github.qiangjian.connect.CameraShareCapabilityProbe 2>&1' 2>&1
            $probeExit = $LASTEXITCODE
            $ErrorActionPreference = 'Stop'
            $lines | Out-File (Join-Path $out "$serial.log") -Encoding utf8
            if ($probeExit -ne 0) { throw "Camera metadata probe failed $serial" }
            $metadata = @($lines | Where-Object { $_ -like 'QJ_CAMERA_METADATA *' })
            if ($metadata.Count -ne 1) { throw "Camera metadata result missing $serial" }
            $metadata[0].Substring(19) | Out-File (Join-Path $out "$serial.json") -Encoding utf8
        } finally { & $adb -s $serial shell rm /data/local/tmp/qj-camera-metadata.jar }
    }
    @($sources + @($probe,$PSCommandPath)) | ForEach-Object { Get-FileHash -LiteralPath $_ -Algorithm SHA256 } | Select-Object Path,Hash | ConvertTo-Json | Out-File (Join-Path $out 'source-sha256.json') -Encoding utf8
    Write-Output "PASS camera metadata only; no open/capture/permission changes. Evidence: $out"
} finally {
    if ($null -ne $lock) { $lock.Dispose(); Remove-Item -LiteralPath $lockPath }
}
