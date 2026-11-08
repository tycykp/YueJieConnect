param([string[]]$Serials = @('b59f8656','3B161Q00YN200000'), [string]$OutputDirectory = 'build/screen-codec-validation')
$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $PSScriptRoot
$out = Join-Path $root $OutputDirectory
$classes = Join-Path $out 'classes'
$dex = Join-Path $out 'dex'
New-Item -ItemType Directory -Force -Path $classes,$dex | Out-Null
$java = 'C:\Program Files\Android\Android Studio\jbr\bin'
$sdk = Join-Path $env:LOCALAPPDATA 'Android/Sdk'
$androidJar = Join-Path $sdk 'platforms/android-37.0/android.jar'
$adb = Join-Path $sdk 'platform-tools/adb.exe'
$sourceDir = Join-Path $root 'android/module/src/main/java/io/github/qiangjian/connect'
$sources = @('ScreenCastFormat','ScreenCastPacket','ScreenCastFrameQueue','ScreenCastVideoEncoder','ScreenCastVideoDecoder') | ForEach-Object { Join-Path $sourceDir "${_}.java" }
$sources += Join-Path $root 'android/module/src/debug/java/io/github/qiangjian/connect/ScreenCastCodecProbe.java'
& (Join-Path $java 'javac.exe') --release 17 -encoding UTF-8 -cp $androidJar -d $classes @sources
if ($LASTEXITCODE -ne 0) { throw 'probe compilation failed' }
$classJar = Join-Path $out 'probe-classes.jar'
& (Join-Path $java 'jar.exe') cf $classJar -C $classes .
if ($LASTEXITCODE -ne 0) { throw 'probe jar failed' }
$oldJava = $env:JAVA_HOME
try {
    $env:JAVA_HOME = Split-Path -Parent $java
    & (Join-Path $sdk 'build-tools/37.0.0/d8.bat') --min-api 37 --lib $androidJar --output $dex $classJar
    if ($LASTEXITCODE -ne 0) { throw 'probe dex failed' }
} finally { $env:JAVA_HOME = $oldJava }
$probe = Join-Path $out 'qj-screen-codec-probe.jar'
& (Join-Path $java 'jar.exe') cf $probe -C $dex classes.dex
if ($LASTEXITCODE -ne 0) { throw 'probe dex jar failed' }
$lockDir = Join-Path $root 'build/coordination-locks'
New-Item -ItemType Directory -Force -Path $lockDir | Out-Null
$lockPath = Join-Path $lockDir 'devices.lock'
$lock = $null
try {
    $lock = [System.IO.File]::Open($lockPath,[System.IO.FileMode]::CreateNew,[System.IO.FileAccess]::ReadWrite,[System.IO.FileShare]::Read)
    $owner = [Text.Encoding]::UTF8.GetBytes("screen-input 01a1bb37-7fd1-7fa2-a941-1261f262c2c9 $([DateTimeOffset]::Now.ToString('o'))")
    $lock.Write($owner,0,$owner.Length); $lock.Flush()
    foreach ($serial in $Serials) {
        if ($serial -notin @('b59f8656','3B161Q00YN200000')) { throw 'unknown test device' }
        & $adb -s $serial push $probe /data/local/tmp/qj-screen-codec-probe.jar
        if ($LASTEXITCODE -ne 0) { throw "probe push failed $serial" }
        try {
            & $adb -s $serial shell 'CLASSPATH=/data/local/tmp/qj-screen-codec-probe.jar timeout 15 app_process /system/bin io.github.qiangjian.connect.ScreenCastCodecProbe 2>&1' | Tee-Object -FilePath (Join-Path $out "$serial.log")
            if ($LASTEXITCODE -ne 0) { throw "codec probe failed $serial exit=$LASTEXITCODE" }
        } finally { & $adb -s $serial shell rm /data/local/tmp/qj-screen-codec-probe.jar }
    }
    @($sources + $probe + $PSCommandPath) | ForEach-Object { Get-FileHash -LiteralPath $_ -Algorithm SHA256 } | Select-Object Path,Hash | ConvertTo-Json | Set-Content -LiteralPath (Join-Path $out 'source-sha256.json') -Encoding utf8
} finally {
    if ($null -ne $lock) { $lock.Dispose(); Remove-Item -LiteralPath $lockPath }
}
