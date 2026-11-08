$ErrorActionPreference = 'Stop'
$root = Split-Path $PSScriptRoot -Parent
$output = Join-Path $root 'build\calls-sms-validation\repair\sms-consumer-capture.json'
if (Test-Path -LiteralPath $output) { throw 'SMS consumer capture has already been performed; inspect its ledger.' }
$lockPath = Join-Path $root 'build\coordination-locks\devices.lock'
$lock = $null
try { $lock = [IO.File]::Open($lockPath, [IO.FileMode]::CreateNew, [IO.FileAccess]::Write, [IO.FileShare]::Read) }
catch [IO.IOException] { Write-Output 'Device lock busy; no device operation performed.'; exit 2 }
try {
    $owner = [Text.Encoding]::UTF8.GetBytes('calls-sms / 01a1bbe4-ec32-7d01-a5de-8fc711849d20 / ' + [DateTime]::UtcNow.ToString('o'))
    $lock.Write($owner, 0, $owner.Length); $lock.Flush()
    $paths = @(& adb -s b59f8656 shell pm path com.miui.cloudservice)
    $queryExit = $LASTEXITCODE
    if ($queryExit -ne 0 -or $paths.Count -ne 1 -or $paths[0] -notmatch '^package:/[^\r\n]+\.apk$') { throw 'Cloudservice system APK path unavailable' }
    $destination = Join-Path $root 'captures\calls-sms-20261108\b59f8656\com.miui.cloudservice'
    New-Item -ItemType Directory -Force $destination | Out-Null
    $apk = Join-Path $destination 'base.apk'
    if (Test-Path -LiteralPath $apk) { throw 'Preserve the existing cloudservice sample; do not overwrite it.' }
    & adb -s b59f8656 pull $paths[0].Substring(8) $apk
    if ($LASTEXITCODE -ne 0) { throw 'Cloudservice APK extraction failed' }
    [ordered]@{ thread='01a1bbe4-ec32-7d01-a5de-8fc711849d20'; utc=[DateTime]::UtcNow.ToString('o'); command='powershell -NoProfile -File scripts/capture-telephony-sms-consumer.ps1'; scriptSha256=(Get-FileHash -LiteralPath $PSCommandPath -Algorithm SHA256).Hash.ToLowerInvariant(); serial='b59f8656'; package='com.miui.cloudservice'; path=$paths[0]; apk=$apk; sha256=(Get-FileHash -LiteralPath $apk -Algorithm SHA256).Hash.ToLowerInvariant(); scope='System APK only. No service launch, cloud connection, SMS content, record access, permission change or SMS send.' } | ConvertTo-Json | Set-Content -LiteralPath $output -Encoding UTF8
    Write-Output "PASS: system APK capture saved to $output"
} finally { if ($null -ne $lock) { $lock.Dispose(); Remove-Item -LiteralPath $lockPath } }
