param([string]$OutputDirectory='build/screen-media-runtime-validation', [switch]$CheckTools)
$ErrorActionPreference='Stop'
$root=Split-Path -Parent $PSScriptRoot
$out=Join-Path $root $OutputDirectory
New-Item -ItemType Directory -Force -Path $out | Out-Null
$channel=Join-Path $root 'transport/src/media_channel.rs'
$runtime=Join-Path $root 'transport/src/media_runtime.rs'
$wrapper=Join-Path $out 'lib.rs'
$manifest=Join-Path $out 'Cargo.toml'
$sourceText='#[path="'+$channel.Replace([char]92,[char]47)+'"] pub mod media_channel;'+[Environment]::NewLine+
    '#[path="'+$runtime.Replace([char]92,[char]47)+'"] pub mod media_runtime;'+[Environment]::NewLine
[IO.File]::WriteAllText($wrapper,$sourceText,[Text.UTF8Encoding]::new($false))
$cargoText=@"
[package]
name = "yj-screen-media-runtime-validation"
version = "0.0.0"
edition = "2021"
[workspace]
[lib]
path = "lib.rs"
[dependencies]
snow = "=0.9.6"
getrandom = "=0.2.16"
sha2 = "=0.10.9"
"@
[IO.File]::WriteAllText($manifest,$cargoText,[Text.UTF8Encoding]::new($false))
function Invoke-RuntimeCargo([string]$Command,[string]$Extra,[string]$Log,[string]$TargetDirectory) {
    $process=[Diagnostics.Process]::new()
    $process.StartInfo.FileName=(Get-Command cargo).Source
    $process.StartInfo.Arguments=$Command+' --offline --manifest-path "'+$manifest+'" --target-dir "'+$TargetDirectory+'" '+$Extra
    $process.StartInfo.UseShellExecute=$false; $process.StartInfo.CreateNoWindow=$true
    $process.StartInfo.RedirectStandardOutput=$true; $process.StartInfo.RedirectStandardError=$true
    try {
        if(-not $process.Start()) { throw 'could not start isolated runtime validation' }
        $stdout=$process.StandardOutput.ReadToEndAsync(); $stderr=$process.StandardError.ReadToEndAsync()
        $process.WaitForExit(); $result=$stdout.Result+$stderr.Result
        [IO.File]::WriteAllText((Join-Path $out $Log),$result,[Text.UTF8Encoding]::new($false)); Write-Output $result
        if($process.ExitCode -ne 0) { throw "runtime cargo $Command failed: $($process.ExitCode)" }
    } finally { $process.Dispose() }
}
$target=Join-Path $out 'target'
# Compile the stable media channel but only execute this runtime's new tests.
Invoke-RuntimeCargo 'test' 'media_runtime::tests:: -- --test-threads=1' 'tests.log' $target
if($CheckTools) {
    Invoke-RuntimeCargo 'clippy' '--all-targets -- -D warnings' 'clippy.log' $target
    Invoke-RuntimeCargo 'check' '--target aarch64-linux-android' 'android-check.log' (Join-Path $out 'android-target')
}
@($channel,$runtime,$PSCommandPath,$wrapper,$manifest,(Join-Path $out 'Cargo.lock')) | ForEach-Object { Get-FileHash -LiteralPath $_ -Algorithm SHA256 } |
    Select-Object Path,Hash | ConvertTo-Json | Set-Content -LiteralPath (Join-Path $out 'source-sha256.json') -Encoding utf8
