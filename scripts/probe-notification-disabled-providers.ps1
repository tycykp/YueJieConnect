$ErrorActionPreference='Stop'
$repo=Split-Path -Parent $PSScriptRoot
$lockPath=Join-Path $repo 'build/coordination-locks/devices.lock'
$out=Join-Path $repo 'build/notifications-validation/repair-round3/oplus-disabled-provider-metadata.json'
if(Test-Path -LiteralPath $out){throw 'Disabled-provider metadata already recorded; do not repeat without new scope'}
$lease=$null
try{
    $lease=[IO.File]::Open($lockPath,[IO.FileMode]::CreateNew,[IO.FileAccess]::ReadWrite,[IO.FileShare]::Read)
    $bytes=[Text.Encoding]::UTF8.GetBytes('notifications 01a1bbe4-dde6-7732-9ead-763df753f644 disabled-provider-metadata '+[DateTimeOffset]::Now.ToString('o'))
    $lease.Write($bytes,0,$bytes.Length);$lease.Flush()
    $raw=adb -s 3B161Q00YN200000 shell cmd package query-services --brief --components --query-flags 512 -a android.service.notification.NotificationListenerService
    if($LASTEXITCODE-ne0 -or ($raw -match 'Error:|Unknown option|Exception|usage:')){throw 'Disabled notification service metadata query failed'}
    $vendor=@($raw|Where-Object{$_-match '^com\.(oplus|coloros|heytap|oppo)\.[A-Za-z0-9_.]+/[A-Za-z0-9_.$]+$'})
    [ordered]@{utc=[DateTime]::UtcNow.ToString('o');serial='3B161Q00YN200000';command='adb -s 3B161Q00YN200000 shell cmd package query-services --brief --components --query-flags 512 -a android.service.notification.NotificationListenerService';query_flags=512;script_sha256=(Get-FileHash -LiteralPath $PSCommandPath -Algorithm SHA256).Hash.ToLowerInvariant();vendor_components=$vendor;scope='New MATCH_DISABLED_COMPONENTS metadata scope prompted by discovered disabled Xiaomi OEM listener. Oplus only. No notification contents, setting writes, process/session/pairing change or ability claim.';apk=$null}|ConvertTo-Json -Depth 4|Set-Content -LiteralPath $out -Encoding utf8
    Get-Content -LiteralPath $out
}finally{if($null-ne$lease){$lease.Dispose();Remove-Item -LiteralPath $lockPath}}
