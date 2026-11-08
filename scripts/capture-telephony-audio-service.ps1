param([string]$ThreadId = '01a1bbe4-ec32-7d01-a5de-8fc711849d20')
$ErrorActionPreference = 'Stop'
$root = Split-Path $PSScriptRoot -Parent
$output = Join-Path $root 'build\calls-sms-validation\repair'
New-Item -ItemType Directory -Force $output | Out-Null
$ledgerPath = Join-Path $output 'audio-service-capture.json'
if (Test-Path -LiteralPath $ledgerPath) { throw 'This metadata capture already has a ledger; inspect it before repeating.' }
$lockPath = Join-Path $root 'build\coordination-locks\devices.lock'
New-Item -ItemType Directory -Force (Split-Path $lockPath -Parent) | Out-Null
$lock = $null
try { $lock = [IO.File]::Open($lockPath, [IO.FileMode]::CreateNew, [IO.FileAccess]::Write, [IO.FileShare]::Read) }
catch [IO.IOException] { Write-Output 'Device lock busy; no device operation performed.'; exit 2 }
try {
    $owner = [Text.Encoding]::UTF8.GetBytes("calls-sms / $ThreadId / " + [DateTime]::UtcNow.ToString('o'))
    $lock.Write($owner, 0, $owner.Length); $lock.Flush()
    $records = @()
    foreach ($serial in @('b59f8656', '3B161Q00YN200000')) {
        $packages = @(& adb -s $serial shell pm list packages -s)
        if ($LASTEXITCODE -ne 0) { throw "System package metadata unavailable: $serial" }
        $candidates = @($packages | Where-Object { $_ -match '(?i)(audio|sms|mms|telephony|incall|continuity|cloudservice)' })
        $record = [ordered]@{ serial=$serial; candidateSystemPackages=$candidates; package='com.miui.audiomonitor'; paths=@(); pathExit=$null; apk=$null; sha256=$null }
        if ($serial -eq 'b59f8656') {
            $record.paths = @(& adb -s $serial shell pm path com.miui.audiomonitor)
            $record.pathExit = $LASTEXITCODE
            if ($record.pathExit -eq 0 -and $record.paths.Count -eq 1 -and $record.paths[0] -match '^package:/[^\r\n]+\.apk$') {
                $destination = Join-Path $root 'captures\calls-sms-20261108\b59f8656\com.miui.audiomonitor'
                New-Item -ItemType Directory -Force $destination | Out-Null
                $apk = Join-Path $destination 'base.apk'
                if (Test-Path -LiteralPath $apk) { throw 'Audio service sample exists; preserve and inspect it instead of overwriting.' }
                & adb -s $serial pull $record.paths[0].Substring(8) $apk
                if ($LASTEXITCODE -ne 0) { throw 'System audio service APK extraction failed' }
                $record.apk=$apk; $record.sha256=(Get-FileHash -LiteralPath $apk -Algorithm SHA256).Hash.ToLowerInvariant()
            }
        }
        $records += $record
    }
    [ordered]@{thread=$ThreadId; utc=[DateTime]::UtcNow.ToString('o'); command='powershell -NoProfile -File scripts/capture-telephony-audio-service.ps1'; scriptSha256=(Get-FileHash -LiteralPath $PSCommandPath -Algorithm SHA256).Hash.ToLowerInvariant(); scope='System package names and audio service APK only. No bind/start, session, permission, records, microphone, call or SMS action.'; moduleApk='code19/0.5.3-business-v6 (unchanged; not tested by this capture)'; records=$records} | ConvertTo-Json -Depth 7 | Set-Content -LiteralPath $ledgerPath -Encoding UTF8
    Write-Output "PASS: metadata/system APK capture saved to $ledgerPath"
} finally {
    if ($null -ne $lock) { $lock.Dispose(); Remove-Item -LiteralPath $lockPath }
}
