$ErrorActionPreference = 'Stop'
$root = Split-Path $PSScriptRoot -Parent
$lockDirectory = Join-Path $root 'build\coordination-locks'
New-Item -ItemType Directory -Force $lockDirectory | Out-Null
$lockPath = Join-Path $lockDirectory 'devices.lock'
$lock = $null
try {
    $lock = [System.IO.File]::Open($lockPath, [System.IO.FileMode]::CreateNew, [System.IO.FileAccess]::Write, [System.IO.FileShare]::Read)
} catch [System.IO.IOException] {
    Write-Output 'Device lock busy; no device operation performed.'
    exit 2
}
try {
    $owner = 'calls-sms / 01a1bb24-1139-7310-9260-f756f51e880b / ' + [DateTime]::UtcNow.ToString('o')
    $bytes = [Text.Encoding]::UTF8.GetBytes($owner)
    $lock.Write($bytes, 0, $bytes.Length); $lock.Flush()
    $output = Join-Path $root 'build\calls-sms-validation\package-paths.json'
    $records = @()
    foreach ($serial in @('b59f8656', '3B161Q00YN200000')) {
        foreach ($package in @('com.android.incallui', 'com.android.mms', 'com.android.phone')) {
            $paths = @(& adb -s $serial shell pm path $package)
            $queryExit = $LASTEXITCODE
            $records += @{ serial=$serial; package=$package; paths=$paths; exitCode=$queryExit }
        }
    }
    $records | ConvertTo-Json -Depth 4 | Set-Content -LiteralPath $output -Encoding utf8
    $records | ConvertTo-Json -Depth 4 | Write-Output
} finally {
    if ($null -ne $lock) { $lock.Dispose(); Remove-Item -LiteralPath $lockPath }
}
