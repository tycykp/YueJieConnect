param(
    [Parameter(Mandatory=$true)][string]$Apk,
    [Parameter(Mandatory=$true)][string]$Inputs,
    [Parameter(Mandatory=$true)][ValidateSet('action','reply')][string]$Kind,
    [ValidateSet('both','xiaomi-to-oplus','oplus-to-xiaomi')][string]$Direction='both',
    [Parameter(Mandatory=$true)][string]$Out
)
$ErrorActionPreference = 'Stop'
$repo = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
$lockPath = Join-Path $repo 'build/coordination-locks/devices.lock'
[IO.Directory]::CreateDirectory((Split-Path -Parent $lockPath)) | Out-Null
$lease = $null
try {
    $lease = [IO.File]::Open($lockPath,[IO.FileMode]::CreateNew,[IO.FileAccess]::ReadWrite,[IO.FileShare]::Read)
    $owner = [Text.Encoding]::UTF8.GetBytes('notifications 01a1bbe4-dde6-7732-9ead-763df753f644 ' + [DateTimeOffset]::Now.ToString('o'))
    $lease.Write($owner,0,$owner.Length)
    $lease.Flush()
    $env:PYTHONIOENCODING = 'utf-8'
    python (Join-Path $PSScriptRoot 'test-notification-actions-native.py') --apk $Apk --inputs $Inputs --kind $Kind --direction $Direction --out $Out
    if ($LASTEXITCODE -ne 0) { throw 'Notification action observation blocked/failed; evidence retained, no automatic retry' }
} finally {
    if ($null -ne $lease) { $lease.Dispose(); Remove-Item -LiteralPath $lockPath }
}
