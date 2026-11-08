param(
    [string]$Adb = "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe",
    [string]$Device = '3B161Q00YN200000',
    [string]$ThreadId = '01a1bba3-1737-7371-b440-a7d9fc656b13'
)
# Future authorized device window only. No install, process restart, session, camera or permission change.
$ErrorActionPreference = 'Stop'
$project = Split-Path $PSScriptRoot -Parent
$lockDir = Join-Path $project 'build/coordination-locks'
New-Item -ItemType Directory -Path $lockDir -Force | Out-Null
$lockPath = Join-Path $lockDir 'devices.lock'
$ownedLock = $null
$utf8 = New-Object System.Text.UTF8Encoding($false)
function Invoke-CameraAdb([string[]]$Arguments) {
    $ErrorActionPreference = 'Continue'
    $result = & $Adb @Arguments 2>&1
    $exit = $LASTEXITCODE
    $ErrorActionPreference = 'Stop'
    if ($exit -ne 0) { throw ('ADB failed: ' + ($Arguments -join ' ') + ': ' + ($result -join "`n")) }
    return $result
}
try {
    # CreateNew is atomic; a busy lock fails without deleting/replacing another task's lease.
    $ownedLock = [System.IO.File]::Open($lockPath, [System.IO.FileMode]::CreateNew,
        [System.IO.FileAccess]::Write, [System.IO.FileShare]::Read)
    $owner = [ordered]@{task='camera-sharing'; threadId=$ThreadId; at=[DateTimeOffset]::Now.ToString('o');
        operation='read-only com.oplus.vdc package/APK sample'} | ConvertTo-Json -Compress
    $ownerBytes = $utf8.GetBytes($owner)
    $ownedLock.Write($ownerBytes,0,$ownerBytes.Length)
    $ownedLock.Flush()
    $state = Invoke-CameraAdb -Arguments @('-s',$Device,'get-state')
    if (($state -join '').Trim() -ne 'device') { throw 'Camera sample device is offline; no retry.' }
    $run = Join-Path $project ('captures/camera-native-20261108/coloros17/vdc-' + (Get-Date -Format 'yyyyMMdd-HHmmss-fff'))
    New-Item -ItemType Directory -Path $run -Force | Out-Null
    $package = 'com.oplus.vdc'
    $paths = @(Invoke-CameraAdb -Arguments @('-s',$Device,'shell','pm','path',$package))
    $paths | Out-File (Join-Path $run 'package-paths.txt') -Encoding utf8
    $apks = @($paths | ForEach-Object {
        $line = ([string]$_).Trim()
        if ($line.StartsWith('package:')) { $line.Substring(8) }
    })
    if ($apks.Count -lt 1) { throw 'com.oplus.vdc is not installed; do not infer camera support.' }
    Invoke-CameraAdb -Arguments @('-s',$Device,'shell','dumpsys','package',$package) |
        Out-File (Join-Path $run 'package.txt') -Encoding utf8
    $evidence = @()
    $i = 0
    foreach ($remote in $apks) {
        # Use a fixed local basename, not a remote-supplied filesystem path.
        if (-not $remote.StartsWith('/') -or -not $remote.EndsWith('.apk') -or $remote.Contains("`n")) {
            throw 'Invalid package APK path'
        }
        $local = Join-Path $run ('split-{0:D2}.apk' -f $i)
        Invoke-CameraAdb -Arguments @('-s',$Device,'pull',$remote,$local) |
            Out-File (Join-Path $run ('pull-{0:D2}.txt' -f $i)) -Encoding utf8
        $evidence += [ordered]@{ remote=$remote; file=$local; sha256=(Get-FileHash $local -Algorithm SHA256).Hash }
        $i++
    }
    [ordered]@{result='PASS'; task='camera-sharing'; threadId=$ThreadId; device=$Device; package=$package;
        command='powershell -NoProfile -File scripts/capture-camera-native-samples.ps1';
        scope='Read-only original package/APK collection; no camera capture, native consumer or APK installation claim';
        script_sha256=(Get-FileHash $PSCommandPath -Algorithm SHA256).Hash; apks=$evidence
    } | ConvertTo-Json -Depth 6 | Out-File (Join-Path $run 'result.json') -Encoding utf8
    Write-Output "Read-only camera native sample: $run"
} finally {
    if ($null -ne $ownedLock) {
        $ownedLock.Dispose()
        Remove-Item -LiteralPath $lockPath
    }
}
