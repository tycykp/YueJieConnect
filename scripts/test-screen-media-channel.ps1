param([string]$OutputDirectory='build/screen-media-channel-validation', [switch]$CheckTools)
$ErrorActionPreference='Stop'
$root=Split-Path -Parent $PSScriptRoot
$out=Join-Path $root $OutputDirectory
New-Item -ItemType Directory -Force -Path $out | Out-Null
$source=Join-Path $root 'transport/src/media_channel.rs'
$manifest=Join-Path $out 'Cargo.toml'
$portable=$source.Replace([char]92,[char]47)
$text=@"
[package]
name = "yj-screen-media-validation"
version = "0.0.0"
edition = "2021"
[workspace]
[lib]
path = "$portable"
[dependencies]
snow = "=0.9.6"
getrandom = "=0.2.16"
sha2 = "=0.10.9"
"@
[IO.File]::WriteAllText($manifest,$text,[Text.UTF8Encoding]::new($false))
$target=Join-Path $out 'target'
function Invoke-CargoValidation([string]$Command,[string]$Extra,[string]$Log,[string]$TargetDirectory) {
    $process=[Diagnostics.Process]::new()
    $process.StartInfo.FileName=(Get-Command cargo).Source
    $process.StartInfo.Arguments=$Command+' --offline --manifest-path "'+$manifest+'" --target-dir "'+$TargetDirectory+'" '+$Extra
    $process.StartInfo.UseShellExecute=$false
    $process.StartInfo.CreateNoWindow=$true
    $process.StartInfo.RedirectStandardOutput=$true
    $process.StartInfo.RedirectStandardError=$true
    try {
        if(-not $process.Start()) { throw 'could not start isolated cargo validation' }
        $stdout=$process.StandardOutput.ReadToEndAsync()
        $stderr=$process.StandardError.ReadToEndAsync()
        $process.WaitForExit()
        $result=$stdout.Result+$stderr.Result
        [IO.File]::WriteAllText((Join-Path $out $Log),$result,[Text.UTF8Encoding]::new($false))
        Write-Output $result
        if($process.ExitCode -ne 0) { throw "cargo $Command failed: $($process.ExitCode)" }
    } finally { $process.Dispose() }
}
Invoke-CargoValidation 'test' '' 'tests.log' $target
if($CheckTools) {
    Invoke-CargoValidation 'clippy' '--all-targets -- -D warnings' 'clippy.log' $target
    Invoke-CargoValidation 'check' '--target aarch64-linux-android' 'android-check.log' (Join-Path $out 'android-target')
}
@($source,$PSCommandPath,$manifest,(Join-Path $out 'Cargo.lock')) | ForEach-Object { Get-FileHash -LiteralPath $_ -Algorithm SHA256 } |
    Select-Object Path,Hash | ConvertTo-Json | Set-Content -LiteralPath (Join-Path $out 'source-sha256.json') -Encoding utf8
