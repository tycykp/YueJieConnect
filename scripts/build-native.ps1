param([string]$Sdk = $env:ANDROID_HOME)
$ErrorActionPreference = 'Stop'
$root = Split-Path $PSScriptRoot -Parent
if (!$Sdk -or !(Test-Path -LiteralPath $Sdk)) { $Sdk = Join-Path $env:LOCALAPPDATA 'Android\Sdk' }
$llvm = Join-Path $Sdk 'ndk\29.0.14206865\toolchains\llvm\prebuilt\windows-x86_64\bin'
$linker = Join-Path $llvm 'aarch64-linux-android29-clang.cmd'
if (!(Test-Path -LiteralPath $linker)) { throw "Install NDK 29.0.14206865 in $Sdk" }
$env:CARGO_TARGET_AARCH64_LINUX_ANDROID_LINKER = $linker
$env:CARGO_TARGET_AARCH64_LINUX_ANDROID_RUSTFLAGS = '-C link-arg=-Wl,-z,max-page-size=16384'
Push-Location $root
try {
    cargo build --locked --release --target aarch64-linux-android -p yj-jni
    if ($LASTEXITCODE -ne 0) { throw 'Rust Android build failed' }
    $dest = Join-Path $root 'android\module\build\generated\jniLibs\arm64-v8a'
    New-Item -ItemType Directory -Force $dest | Out-Null
    Copy-Item -LiteralPath (Join-Path $root 'target\aarch64-linux-android\release\libyj_jni.so') -Destination $dest
} finally { Pop-Location }
