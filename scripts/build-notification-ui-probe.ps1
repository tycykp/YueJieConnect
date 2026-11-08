$ErrorActionPreference = 'Stop'
$repo = Split-Path -Parent $PSScriptRoot
$out = Join-Path $repo 'build/notifications-validation/repair-round3/ui-probe'
$classes = Join-Path $out 'classes'
$dex = Join-Path $out 'dex'
[IO.Directory]::CreateDirectory($classes) | Out-Null
[IO.Directory]::CreateDirectory($dex) | Out-Null
$javaBin = 'C:\Program Files\Android\Android Studio\jbr\bin'
$sdk = Join-Path $env:LOCALAPPDATA 'Android/Sdk'
$androidJar = Join-Path $sdk 'platforms/android-37.0/android.jar'
$source = Join-Path $PSScriptRoot 'notifications/NotificationSyncUiProbe.java'
$uiautomator = Join-Path $sdk 'platforms/android-37.0/uiautomator.jar'
$junit = Get-ChildItem -LiteralPath (Join-Path $env:USERPROFILE '.gradle/caches/modules-2/files-2.1/junit/junit/4.13.2') -Filter '*.jar' -Recurse | Select-Object -First 1 -ExpandProperty FullName
& (Join-Path $javaBin 'javac.exe') --release 17 -encoding UTF-8 -cp "$androidJar;$uiautomator;$junit" -d $classes $source
if ($LASTEXITCODE -ne 0) { throw 'Notification UI probe javac failed' }
# Current ROMs retain the legacy platform runner but omit its annotation dependency.
# Copy only these SDK compatibility annotations; no UI or notification behavior is stubbed.
$baseTestJar = Join-Path $sdk 'platforms/android-35/optional/android.test.base.jar'
Push-Location $classes
try {
    & (Join-Path $javaBin 'jar.exe') xf $baseTestJar android/test/RepetitiveTest.class android/test/FlakyTest.class
    if ($LASTEXITCODE -ne 0) { throw 'Legacy UI runner annotation extraction failed' }
} finally { Pop-Location }
& (Join-Path $javaBin 'jar.exe') cf (Join-Path $out 'classes.jar') -C $classes .
if ($LASTEXITCODE -ne 0) { throw 'Notification UI class jar failed' }
$previousJava = $env:JAVA_HOME
try {
    $env:JAVA_HOME = Split-Path -Parent $javaBin
    & (Join-Path $sdk 'build-tools/37.0.0/d8.bat') --min-api 35 --lib $androidJar --lib $uiautomator --lib $junit --output $dex (Join-Path $out 'classes.jar')
    if ($LASTEXITCODE -ne 0) { throw 'Notification UI probe DEX failed' }
} finally { $env:JAVA_HOME = $previousJava }
& (Join-Path $javaBin 'jar.exe') cf (Join-Path $out 'probe.jar') -C $dex classes.dex
if ($LASTEXITCODE -ne 0) { throw 'Notification UI probe jar failed' }
[ordered]@{command='powershell -NoProfile -File scripts/build-notification-ui-probe.ps1'; source_sha256=(Get-FileHash -LiteralPath $source).Hash.ToLowerInvariant(); jar_sha256=(Get-FileHash -LiteralPath (Join-Path $out 'probe.jar')).Hash.ToLowerInvariant(); compatibility_annotations_sdk_sha256=(Get-FileHash -LiteralPath $baseTestJar).Hash.ToLowerInvariant(); result='passed'; scope='Temporary platform UiAutomator probe SDK37 compile/dex with SDK35 annotation compatibility only, no UI/notification behavior stub and no business validation'} | ConvertTo-Json | Set-Content -LiteralPath (Join-Path $out 'compile.json') -Encoding utf8
