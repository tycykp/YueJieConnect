param([switch]$IncludeTeleService)
$ErrorActionPreference = 'Stop'
$root = Split-Path $PSScriptRoot -Parent
$directory = Join-Path $root 'build\coordination-locks'
New-Item -ItemType Directory -Force $directory | Out-Null
$lockPath = Join-Path $directory 'devices.lock'
$lock = $null
try { $lock = [System.IO.File]::Open($lockPath, [System.IO.FileMode]::CreateNew, [System.IO.FileAccess]::Write, [System.IO.FileShare]::Read) }
catch [System.IO.IOException] { Write-Output 'Device lock busy; no device operation performed.'; exit 2 }
try {
    $owner = 'calls-sms / 01a1bb24-1139-7310-9260-f756f51e880b / ' + [DateTime]::UtcNow.ToString('o')
    $bytes = [Text.Encoding]::UTF8.GetBytes($owner); $lock.Write($bytes,0,$bytes.Length); $lock.Flush()
    $inventory = Get-Content -LiteralPath (Join-Path $root 'build\calls-sms-validation\package-paths.json') -Raw | ConvertFrom-Json
    $records = @()
    foreach ($item in $inventory) {
        if ($item.package -ne 'com.android.incallui' -and $item.package -ne 'com.android.mms' -and !($IncludeTeleService -and $item.package -eq 'com.android.phone')) { continue }
        if ($item.exitCode -ne 0 -or $item.paths.Count -ne 1) { continue }
        $destination = Join-Path $root ('captures\calls-sms-20261108\' + $item.serial + '\' + $item.package)
        New-Item -ItemType Directory -Force $destination | Out-Null
        $apk = Join-Path $destination 'base.apk'
        if (!(Test-Path -LiteralPath $apk)) {
            & adb -s $item.serial pull $item.paths[0].Substring(8) $apk
            if ($LASTEXITCODE -ne 0) { throw 'System APK extraction failed' }
        }
        $records += @{ serial=$item.serial; package=$item.package; file=$apk; sha256=(Get-FileHash -LiteralPath $apk -Algorithm SHA256).Hash.ToLowerInvariant() }
    }
    $records | ConvertTo-Json -Depth 4 | Set-Content -LiteralPath (Join-Path $root 'build\calls-sms-validation\extra-apks.json') -Encoding utf8
    $records | ConvertTo-Json -Depth 4 | Write-Output
} finally { if ($null -ne $lock) { $lock.Dispose(); Remove-Item -LiteralPath $lockPath } }
