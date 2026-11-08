param(
    [string]$Adb = "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe",
    [string]$ThreadId = '01a1bbe4-ff7b-7ef2-a1b7-6dacb78d4347'
)
# Exact usesLibraryFiles path from the captured VDCService package dump. Read-only, no retries.
$ErrorActionPreference = 'Stop'
$project = Split-Path (Split-Path $PSScriptRoot -Parent) -Parent
$lockPath = Join-Path $project 'build/coordination-locks/devices.lock'
$ownedLock = $null
try {
    $ownedLock = [IO.File]::Open($lockPath, [IO.FileMode]::CreateNew, [IO.FileAccess]::Write, [IO.FileShare]::Read)
    $bytes = [Text.Encoding]::UTF8.GetBytes(([ordered]@{task='camera-sharing'; threadId=$ThreadId;
        at=[DateTimeOffset]::Now.ToString('o'); operation='read-only ODMF framework sample'} | ConvertTo-Json -Compress))
    $ownedLock.Write($bytes,0,$bytes.Length); $ownedLock.Flush()
    $run = Join-Path $project ('captures/camera-native-20261108/coloros17/odmf-' + (Get-Date -Format 'yyyyMMdd-HHmmss-fff'))
    New-Item -ItemType Directory -Path $run -Force | Out-Null
    $remote = '/system_ext/framework/oplusex/com.oplus.odmf/odmf.jar'
    $local = Join-Path $run 'odmf.jar'
    $ErrorActionPreference = 'Continue'
    & $Adb -s '3B161Q00YN200000' pull $remote $local 2>&1 | Out-File (Join-Path $run 'pull.txt') -Encoding utf8
    $code = $LASTEXITCODE
    $ErrorActionPreference = 'Stop'
    if ($code -ne 0) { throw 'ODMF sample pull failed; no retry or state change.' }
    [ordered]@{result='PASS'; threadId=$ThreadId; device='3B161Q00YN200000'; remote=$remote;
        file=$local; sha256=(Get-FileHash -LiteralPath $local -Algorithm SHA256).Hash;
        command='powershell -NoProfile -File scripts/camera-sharing/capture-odmf.ps1';
        script_sha256=(Get-FileHash -LiteralPath $PSCommandPath -Algorithm SHA256).Hash;
        scope='Read-only original framework library collection. No camera capture, API execution, permission, process, APK or session change.'
    } | ConvertTo-Json -Depth 4 | Out-File (Join-Path $run 'result.json') -Encoding utf8
    Write-Output "ODMF sample: $run"
} finally {
    if ($null -ne $ownedLock) { $ownedLock.Dispose(); Remove-Item -LiteralPath $lockPath }
}
