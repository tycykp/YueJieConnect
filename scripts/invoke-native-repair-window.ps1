param([Parameter(Mandatory=$true)][string]$Apk,[Parameter(Mandatory=$true)][string]$BuildLedger)
$ErrorActionPreference='Stop'
$root=Split-Path -Parent $PSScriptRoot
$lockPath=Join-Path $root 'build/coordination-locks/devices.lock'
$lease=$null
try {
    $lease=[IO.File]::Open($lockPath,[IO.FileMode]::CreateNew,[IO.FileAccess]::ReadWrite,[IO.FileShare]::Read)
    $owner=[Text.Encoding]::UTF8.GetBytes('native-pairing 01a1bbe4-a33d-7f83-80db-5442125d938f '+[DateTimeOffset]::Now.ToString('o'))
    $lease.Write($owner,0,$owner.Length);$lease.Flush()
    $env:PYTHONIOENCODING='utf-8'
    python -u (Join-Path $PSScriptRoot 'native-repair-window.py') --apk $Apk --build-ledger $BuildLedger
    if($LASTEXITCODE -ne 0){throw 'Native repair window failed; inspect its recorded result'}
} finally {if($null -ne $lease){$lease.Dispose();Remove-Item -LiteralPath $lockPath}}
