param([switch]$CheckTools)
$ErrorActionPreference = 'Stop'
$root = Split-Path $PSScriptRoot -Parent
$out = Join-Path $root ('build/camera-validation/still-channel-' + (Get-Date -Format 'yyyyMMdd-HHmmss-fff'))
New-Item -ItemType Directory -Path $out -Force | Out-Null
$source = Join-Path $root 'transport/src/camera_still.rs'
$manifest = Join-Path $out 'Cargo.toml'
$portable = $source.Replace([char]92,[char]47)
$content = @"
[package]
name = "qj-camera-still-validation"
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
[IO.File]::WriteAllText($manifest,$content,[Text.UTF8Encoding]::new($false))
$commands = @()
function Invoke-StillCargo([string[]]$Arguments, [string]$Log) {
    $process = [Diagnostics.Process]::new()
    $process.StartInfo.FileName = (Get-Command cargo).Source
    $process.StartInfo.Arguments = ($Arguments -join ' ') + ' --offline --manifest-path "' + $manifest + '" --target-dir "' + (Join-Path $out 'target') + '"'
    if ($Arguments[0] -eq 'clippy') { $process.StartInfo.Arguments += ' -- -D warnings' }
    $process.StartInfo.UseShellExecute = $false; $process.StartInfo.CreateNoWindow = $true
    $process.StartInfo.RedirectStandardOutput = $true; $process.StartInfo.RedirectStandardError = $true
    try {
        if (-not $process.Start()) { throw 'Cannot start isolated camera still Cargo validation' }
        $stdout = $process.StandardOutput.ReadToEndAsync(); $stderr = $process.StandardError.ReadToEndAsync()
        $process.WaitForExit(); $output = $stdout.Result + $stderr.Result
        [IO.File]::WriteAllText((Join-Path $out $Log),$output,[Text.UTF8Encoding]::new($false))
        $script:commands += [ordered]@{command=('cargo ' + $process.StartInfo.Arguments); exit_code=$process.ExitCode; log=$Log}
        Write-Output $output
        if ($process.ExitCode -ne 0) { throw "Camera still validation failed: $Log" }
    } finally { $process.Dispose() }
}
try {
    Invoke-StillCargo -Arguments @('test') -Log 'tests.log'
    if ($CheckTools) {
        Invoke-StillCargo -Arguments @('clippy','--all-targets') -Log 'clippy.log'
        Invoke-StillCargo -Arguments @('check','--target','aarch64-linux-android') -Log 'android-check.log'
    }
    $result = 'PASS'
} catch { $result = 'FAIL'; throw }
finally {
    $inputs = @($source,$PSCommandPath,$manifest)
    if (Test-Path -LiteralPath (Join-Path $out 'Cargo.lock')) { $inputs += (Join-Path $out 'Cargo.lock') }
    $hashes = @($inputs | ForEach-Object { [ordered]@{path=$_;sha256=(Get-FileHash -LiteralPath $_ -Algorithm SHA256).Hash} })
    [ordered]@{result=$result; commands=$commands; sources=$hashes; apk=$null; device_tested=$false;
        scope='New independent JPEG NNpsk0 framing/session-consent/replay/cancel checks with synthetic bytes over loopback. No actual JPEG decoding, camera capture, native consumer or business success. Isolated Cargo target; no shared build mutation.'
    } | ConvertTo-Json -Depth 7 | Out-File (Join-Path $out 'result.json') -Encoding utf8
    Write-Output "Evidence: $out"
}
