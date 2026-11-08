$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $PSScriptRoot
$lockPath = Join-Path $root 'build/coordination-locks/devices.lock'
$lease = $null
try {
    $lease = [IO.File]::Open($lockPath,[IO.FileMode]::CreateNew,[IO.FileAccess]::ReadWrite,[IO.FileShare]::Read)
    $bytes = [Text.Encoding]::UTF8.GetBytes('clipboard 01a1bbe4-d754-7773-85dd-5cd0f1ce029f native-consumer-inventory '+[DateTimeOffset]::Now.ToString('o'))
    $lease.Write($bytes,0,$bytes.Length); $lease.Flush()
    python (Join-Path $PSScriptRoot 'capture-clipboard-native-consumers.py')
    if ($LASTEXITCODE -ne 0) { throw 'Native consumer inventory failed; inspect its exact evidence' }
} finally { if ($null -ne $lease) { $lease.Dispose(); Remove-Item -LiteralPath $lockPath } }
