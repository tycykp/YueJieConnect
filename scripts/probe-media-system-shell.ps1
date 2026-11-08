$ErrorActionPreference = 'Stop'
$taskRoot = Split-Path -Parent $PSScriptRoot
$taskOut = Join-Path $taskRoot 'build/media-controls/shell-api'
$lockPath = Join-Path $taskRoot 'build/coordination-locks/devices.lock'
[IO.Directory]::CreateDirectory($taskOut) | Out-Null
[IO.Directory]::CreateDirectory((Split-Path -Parent $lockPath)) | Out-Null
$lease = $null
$leaseText = 'media-controls 01a1bbe5-051e-73a2-aa34-64939ce47da5 help-only ' + [guid]::NewGuid().ToString() + ' ' + [DateTimeOffset]::Now.ToString('o')
$evidence = [ordered]@{ task='media-controls'; scope='Read-only cmd media_session help; no user session enumeration, UI, media state or pairing'; devices=@(); acquired=$false; script_sha256=(Get-FileHash -LiteralPath $PSCommandPath -Algorithm SHA256).Hash }
try {
    $lease = [IO.File]::Open($lockPath,[IO.FileMode]::CreateNew,[IO.FileAccess]::ReadWrite,[IO.FileShare]::Read)
    $bytes = [Text.Encoding]::UTF8.GetBytes($leaseText); $lease.Write($bytes,0,$bytes.Length); $lease.Flush()
    $evidence.acquired = $true
    python (Join-Path $PSScriptRoot 'probe-media-system-shell.py') --output-dir $taskOut --expected-lock $leaseText
    if ($LASTEXITCODE -ne 0) { throw 'Read-only media shell API probe failed; evidence retained, no retry' }
} catch {
    $evidence.error = $_.Exception.Message
    throw
} finally {
    [IO.File]::WriteAllText((Join-Path $taskOut 'evidence.json'),($evidence | ConvertTo-Json -Depth 8),[Text.UTF8Encoding]::new($false))
    if ($null -ne $lease) { $lease.Dispose(); Remove-Item -LiteralPath $lockPath }
}
