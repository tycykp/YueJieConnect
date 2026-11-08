param([ValidateSet('inventory','clean')][string]$Mode='inventory')
$ErrorActionPreference='Stop'
$root=Split-Path -Parent $PSScriptRoot
$lock=Join-Path $root 'build/coordination-locks/devices.lock'
$lease=$null
try {
    $lease=[IO.File]::Open($lock,[IO.FileMode]::CreateNew,[IO.FileAccess]::Write,[IO.FileShare]::Read)
    $owner=[Text.Encoding]::UTF8.GetBytes('functional-cleanup 01a1bbcf-5271-7900-bb0d-bee38aff77b9 '+[DateTimeOffset]::Now.ToString('o'))
    $lease.Write($owner,0,$owner.Length);$lease.Flush()
    $env:PYTHONIOENCODING='utf-8'
    python (Join-Path $PSScriptRoot 'cleanup-functional-device-residue.py') $Mode
    if($LASTEXITCODE -ne 0){throw 'Device residue cleanup step failed'}
} finally {
    if($null -ne $lease){$lease.Dispose();Remove-Item -LiteralPath $lock}
}
