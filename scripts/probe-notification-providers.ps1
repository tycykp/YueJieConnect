# Read service metadata only. Never invoke dumpsys notification or read notification contents.
$ErrorActionPreference = 'Stop'
$repo = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
$lockDir = Join-Path $repo 'build/coordination-locks'
$lockPath = Join-Path $lockDir 'devices.lock'
$out = Join-Path $repo 'build/notifications-validation'
New-Item -ItemType Directory -Force -Path $lockDir,$out | Out-Null
$lock = $null
try {
    try { $lock = [IO.File]::Open($lockPath, [IO.FileMode]::CreateNew, [IO.FileAccess]::ReadWrite, [IO.FileShare]::Read) }
    catch [IO.IOException] { Write-Output 'devices.lock busy; provider probe not run'; exit 2 }
    $owner = [Text.Encoding]::UTF8.GetBytes("notifications / 01a1bb23-deee-7020-bb48-1311cec639e9 / $([DateTime]::UtcNow.ToString('o')) / read-only notification service metadata")
    $lock.Write($owner,0,$owner.Length); $lock.Flush()
    $records = @()
    foreach ($serial in @('b59f8656','3B161Q00YN200000')) {
        $services = & C:\adb\adb.exe -s $serial shell cmd package query-services --brief --components -a android.service.notification.NotificationListenerService 2>&1
        if ($LASTEXITCODE -ne 0) { throw 'Notification provider metadata query failed' }
        $vendor = @($services | Where-Object { $_ -match '(com\.(oplus|coloros|heytap|xiaomi|miui|milink)|com\.android\.systemui)' })
        $records += [ordered]@{ serial=$serial; components=$vendor; command='shell cmd package query-services --brief --components -a android.service.notification.NotificationListenerService'; scope='Vendor service component metadata only; no permission changes, no notification content access, no session changes' }
    }
    [ordered]@{ utc=[DateTime]::UtcNow.ToString('o'); probes=$records; script_sha256=(Get-FileHash -LiteralPath $PSCommandPath -Algorithm SHA256).Hash.ToLowerInvariant() } | ConvertTo-Json -Depth 6 | Set-Content -LiteralPath (Join-Path $out 'provider-metadata.json') -Encoding utf8
    $records | ConvertTo-Json -Depth 5
} finally {
    if ($null -ne $lock) { $lock.Dispose(); Remove-Item -LiteralPath $lockPath }
}
