param(
    [Parameter(Mandatory=$true)][string]$Apk,
    [Parameter(Mandatory=$true)][string]$BuildLedger,
    [Parameter(Mandatory=$true)][string]$BuildInputs,
    [Parameter(Mandatory=$true)][string]$ExpectedSession,
    [Parameter(Mandatory=$true)][string]$NetworkOperation
)
$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $PSScriptRoot
$lockPath = Join-Path $projectRoot 'build/coordination-locks/devices.lock'
$leaseId = [Guid]::NewGuid().ToString('N')
$lease = $null
try {
    $lease = [IO.File]::Open($lockPath,[IO.FileMode]::CreateNew,[IO.FileAccess]::ReadWrite,[IO.FileShare]::Read)
    $owner = [Text.Encoding]::UTF8.GetBytes('network-sharing 01a1bbe4-e840-7143-bb3f-9684d9fcea49 ' + $leaseId + ' ' + [DateTimeOffset]::Now.ToString('o'))
    $lease.Write($owner,0,$owner.Length); $lease.Flush()
    $env:PYTHONIOENCODING = 'utf-8'
    python -u (Join-Path $PSScriptRoot 'network-sharing-window.py') --apk $Apk --build-ledger $BuildLedger --build-inputs $BuildInputs --expected-session $ExpectedSession --network-operation $NetworkOperation --lease-id $leaseId
    if ($LASTEXITCODE -ne 0) { throw 'Network device window failed; inspect its preserved result' }
} finally {
    if ($null -ne $lease) {
        $lease.Dispose()
        if ([IO.File]::ReadAllText($lockPath).Contains($leaseId)) { Remove-Item -LiteralPath $lockPath }
    }
}
