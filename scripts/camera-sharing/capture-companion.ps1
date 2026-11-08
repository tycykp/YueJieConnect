param([string]$Adb = "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe")
# Resolve the real SurfaceUtils timestamp contract from the documented OEM library, read-only.
$ErrorActionPreference = 'Stop'
$project = Split-Path (Split-Path $PSScriptRoot -Parent) -Parent
$lockPath = Join-Path $project 'build/coordination-locks/devices.lock'
$ownedLock = $null
try {
    $ownedLock = [IO.File]::Open($lockPath, [IO.FileMode]::CreateNew, [IO.FileAccess]::Write, [IO.FileShare]::Read)
    $bytes = [Text.Encoding]::UTF8.GetBytes(([ordered]@{task='camera-sharing'; threadId='01a1bbe4-ff7b-7ef2-a1b7-6dacb78d4347';
        at=[DateTimeOffset]::Now.ToString('o'); operation='read-only camera companion timestamp contract'} | ConvertTo-Json -Compress))
    $ownedLock.Write($bytes,0,$bytes.Length); $ownedLock.Flush()
    $run = Join-Path $project ('captures/camera-native-20261108/hyperos4/companion-' + (Get-Date -Format 'yyyyMMdd-HHmmss-fff'))
    New-Item -ItemType Directory -Path $run -Force | Out-Null
    $remote = '/system_ext/framework/com.xiaomi.hardware.camera.companion-V1.jar'
    $local = Join-Path $run 'companion.jar'
    $ErrorActionPreference = 'Continue'
    & $Adb -s 'b59f8656' pull $remote $local 2>&1 | Out-File (Join-Path $run 'pull.txt') -Encoding utf8
    $code = $LASTEXITCODE
    $ErrorActionPreference = 'Stop'
    if ($code -ne 0) { throw 'Camera companion sample failed; no retry.' }
    [ordered]@{result='PASS'; threadId='01a1bbe4-ff7b-7ef2-a1b7-6dacb78d4347'; device='b59f8656';
        file=$local; remote=$remote; sha256=(Get-FileHash -LiteralPath $local -Algorithm SHA256).Hash;
        command='powershell -NoProfile -File scripts/camera-sharing/capture-companion.ps1';
        script_sha256=(Get-FileHash -LiteralPath $PSCommandPath -Algorithm SHA256).Hash;
        scope='Original shared framework library only; no camera/process/permission/session/APK change.'
    } | ConvertTo-Json -Depth 4 | Out-File (Join-Path $run 'result.json') -Encoding utf8
    Write-Output "Camera companion sample: $run"
} finally {
    if ($null -ne $ownedLock) { $ownedLock.Dispose(); Remove-Item -LiteralPath $lockPath }
}
