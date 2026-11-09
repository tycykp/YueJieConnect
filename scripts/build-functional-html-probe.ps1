$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $PSScriptRoot
$out = Join-Path $root 'build/functional-test-20261108/html-probe'
$classes = Join-Path $out 'classes'
$dex = Join-Path $out 'dex'
[IO.Directory]::CreateDirectory($classes) | Out-Null
[IO.Directory]::CreateDirectory($dex) | Out-Null
$javaBin = 'C:\Program Files\Android\Android Studio\jbr\bin'
$sdk = Join-Path $env:LOCALAPPDATA 'Android/Sdk'
$androidJar = Join-Path $sdk 'platforms/android-37.0/android.jar'
$source = Join-Path $PSScriptRoot 'functional-test/YjHtmlClipboard.java'
& (Join-Path $javaBin 'javac.exe') --release 17 -encoding UTF-8 -cp $androidJar -d $classes $source
if ($LASTEXITCODE -ne 0) { throw 'Standalone HTML probe compilation failed' }
& (Join-Path $javaBin 'jar.exe') cf (Join-Path $out 'classes.jar') -C $classes .
if ($LASTEXITCODE -ne 0) { throw 'Standalone HTML class jar failed' }
$oldJava = $env:JAVA_HOME
try {
    $env:JAVA_HOME = Split-Path -Parent $javaBin
    & (Join-Path $sdk 'build-tools/37.0.0/d8.bat') --min-api 37 --lib $androidJar --output $dex (Join-Path $out 'classes.jar')
    if ($LASTEXITCODE -ne 0) { throw 'Standalone HTML probe DEX failed' }
} finally { $env:JAVA_HOME = $oldJava }
& (Join-Path $javaBin 'jar.exe') cf (Join-Path $out 'probe.jar') -C $dex classes.dex
if ($LASTEXITCODE -ne 0) { throw 'Standalone HTML probe jar failed' }
[ordered]@{command='powershell -NoProfile -File scripts/build-functional-html-probe.ps1';source_sha256=(Get-FileHash -LiteralPath $source).Hash; jar_sha256=(Get-FileHash -LiteralPath (Join-Path $out 'probe.jar')).Hash; scope='Temporary test helper only; no production build or feature pass'} | ConvertTo-Json | Set-Content -LiteralPath (Join-Path $out 'compile.json') -Encoding utf8
