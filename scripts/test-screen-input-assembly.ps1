param([string]$OutputDirectory = 'build/screen-repair-input-assembly-validation',
    [string[]]$TestClasses = @('RemoteInputReceiverTest','RemoteInputOplusTargetTest'))
$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $PSScriptRoot
$out = Join-Path $root $OutputDirectory
& (Join-Path $PSScriptRoot 'test-screen-input.ps1') -OutputDirectory $OutputDirectory -CompileOnly
$classes = Join-Path $out 'classes'
$java = 'C:\Program Files\Android\Android Studio\jbr\bin'
$sdk = Join-Path $env:LOCALAPPDATA 'Android/Sdk/platforms/android-37.0/android.jar'
$junit = Get-ChildItem -LiteralPath (Join-Path $env:USERPROFILE '.gradle/caches/modules-2/files-2.1/junit/junit/4.13.2') -Recurse -Filter '*.jar' | Select-Object -First 1 -ExpandProperty FullName
$hamcrest = Get-ChildItem -LiteralPath (Join-Path $env:USERPROFILE '.gradle/caches/modules-2/files-2.1/org.hamcrest/hamcrest-core/1.3') -Recurse -Filter '*.jar' | Select-Object -First 1 -ExpandProperty FullName
# A host-only Looper boundary permits the actual adapter's main-thread guard to execute.
# No device service, permission, native focus or OEM hook is constructed by these tests.
$fixture = Join-Path $out 'fixtures/android/os/Looper.java'
New-Item -ItemType Directory -Force -Path (Split-Path -Parent $fixture) | Out-Null
[IO.File]::WriteAllText($fixture, 'package android.os; public final class Looper { private static final Looper MAIN=new Looper(); public static Looper myLooper(){return MAIN;} public static Looper getMainLooper(){return MAIN;} }', [Text.UTF8Encoding]::new($false))
& (Join-Path $java 'javac.exe') --release 17 -encoding UTF-8 -d $classes $fixture 2>&1 | Tee-Object -FilePath (Join-Path $out 'fixture-compile.log')
if ($LASTEXITCODE -ne 0) { throw 'assembly fixture compile failed' }
$qualified = @($TestClasses | ForEach-Object { "io.github.yuejie.connect.$_" })
& (Join-Path $java 'java.exe') -cp "$classes;$sdk;$(Join-Path $out 'xposed-api.jar');$junit;$hamcrest" org.junit.runner.JUnitCore @qualified 2>&1 | Tee-Object -FilePath (Join-Path $out 'tests.log')
if ($LASTEXITCODE -ne 0) { throw 'input assembly tests failed' }
$ledger = @(Get-Content -Raw -LiteralPath (Join-Path $out 'source-sha256.json') | ConvertFrom-Json | ForEach-Object { $_ })
$ledger += @($fixture,$PSCommandPath) | ForEach-Object { Get-FileHash -LiteralPath $_ -Algorithm SHA256 } | Select-Object Path,Hash
$ledger | ConvertTo-Json | Set-Content -LiteralPath (Join-Path $out 'source-sha256.json') -Encoding utf8
