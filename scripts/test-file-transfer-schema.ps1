param([string]$ThreadId = '01a1bbe4-daaf-7d83-a571-a2218ce9ab1f')
$ErrorActionPreference = 'Stop'
$repo = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
$out = Join-Path $repo 'build/file-transfer-validation/schema-harness'
$lock = Join-Path $repo 'build/coordination-locks/build.lock'
$source = Join-Path $repo 'transport/src/file_transfer.rs'
New-Item -ItemType Directory -Force -Path $out, (Split-Path $lock) | Out-Null
$lease = $null
try {
    $lease = [IO.File]::Open($lock, [IO.FileMode]::CreateNew, [IO.FileAccess]::Write, [IO.FileShare]::Read)
    $owner = [Text.Encoding]::UTF8.GetBytes("files-schema $ThreadId $([DateTimeOffset]::Now.ToString('o'))")
    $lease.Write($owner, 0, $owner.Length); $lease.Flush()
    # Isolated crate/target/lockfile: shared Cargo entry points are owned by native-pairing.
    @'
[package]
name = "yj-file-transfer-schema-check"
version = "0.0.0"
edition = "2021"
[workspace]
[dependencies]
serde = { version = "=1.0.228", features = ["derive"] }
serde_json = "=1.0.145"
[lib]
path = "lib.rs"
'@ | Set-Content -LiteralPath (Join-Path $out 'Cargo.toml') -Encoding utf8
    $modulePath = $source.Replace('\', '/')
    "#[path = `"$modulePath`"]`npub mod file_transfer;" | Set-Content -LiteralPath (Join-Path $out 'lib.rs') -Encoding utf8
    $before = (Get-FileHash -LiteralPath $source -Algorithm SHA256).Hash.ToLowerInvariant()
    $records = @()
    foreach ($phase in @('test', 'clippy')) {
        $command = @($phase, '--offline', '--manifest-path', ('"' + (Join-Path $out 'Cargo.toml') + '"'), '--target-dir', ('"' + (Join-Path $out 'target') + '"'))
        if ($phase -eq 'clippy') { $command += @('--all-targets', '--', '-D', 'warnings') }
        $stdout = Join-Path $out "$phase-stdout.log"
        $stderr = Join-Path $out "$phase-stderr.log"
        $process = Start-Process -FilePath (Get-Command cargo).Source -ArgumentList $command -WindowStyle Hidden -Wait -PassThru -RedirectStandardOutput $stdout -RedirectStandardError $stderr
        $exitCode = $process.ExitCode
        $result = @(Get-Content -LiteralPath $stdout) + @(Get-Content -LiteralPath $stderr)
        $result | Set-Content -LiteralPath (Join-Path $out "$phase.log") -Encoding utf8
        $records += [ordered]@{ command=('cargo ' + ($command -join ' ')); exit_code=$exitCode; log="$phase.log" }
        $result
        if ($exitCode -ne 0) { break }
    }
    $after = (Get-FileHash -LiteralPath $source -Algorithm SHA256).Hash.ToLowerInvariant()
    [ordered]@{ utc=[DateTime]::UtcNow.ToString('o'); commands=$records; exit_code=$exitCode;
        scope='Delegated new file schema only; isolated Cargo crate, no APK/device execution';
        source='transport/src/file_transfer.rs'; source_sha256=$before; source_races=($before -ne $after);
        script_sha256=(Get-FileHash -LiteralPath $PSCommandPath -Algorithm SHA256).Hash.ToLowerInvariant(); apk=$null
    } | ConvertTo-Json -Depth 5 | Set-Content -LiteralPath (Join-Path $out 'ledger.json') -Encoding utf8
    if ($exitCode -ne 0 -or $before -ne $after) { throw 'File schema validation failed or source changed' }
} finally {
    if ($null -ne $lease) { $lease.Dispose(); Remove-Item -LiteralPath $lock }
}
