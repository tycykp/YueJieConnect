param([Parameter(Mandatory=$true)][string]$Scene,
      [string]$OutputDirectory = 'build/media-controls/native-controller')
$ErrorActionPreference = 'Stop'
$taskRoot = Split-Path -Parent $PSScriptRoot
$scenePath = if ([IO.Path]::IsPathRooted($Scene)) { $Scene } else { Join-Path $taskRoot $Scene }
$outputPath = if ([IO.Path]::IsPathRooted($OutputDirectory)) { $OutputDirectory } else { Join-Path $taskRoot $OutputDirectory }
$lockPath = Join-Path $taskRoot 'build/coordination-locks/devices.lock'
[IO.Directory]::CreateDirectory((Split-Path -Parent $lockPath)) | Out-Null
$lease = $null
$leaseText = 'media-controls 01a1bbe5-051e-73a2-aa34-64939ce47da5 native-controller ' + [guid]::NewGuid().ToString() + ' ' + [DateTimeOffset]::Now.ToString('o')
try {
    $lease = [IO.File]::Open($lockPath,[IO.FileMode]::CreateNew,[IO.FileAccess]::ReadWrite,[IO.FileShare]::Read)
    $bytes = [Text.Encoding]::UTF8.GetBytes($leaseText); $lease.Write($bytes,0,$bytes.Length); $lease.Flush()
    python (Join-Path $PSScriptRoot 'test-media-system-controller.py') --scene $scenePath --output-dir $outputPath --expected-lock $leaseText
    if ($LASTEXITCODE -ne 0) { throw 'Targeted media controller validation incomplete; inspect evidence, no automatic retry' }
} finally {
    if ($null -ne $lease) { $lease.Dispose(); Remove-Item -LiteralPath $lockPath }
}
