param(
    [string]$Apk = '',
    [string]$ThreadId = $env:CODEX_THREAD_ID,
    [ValidateSet('both','xiaomi-to-oplus','oplus-to-xiaomi')][string]$Direction = 'both',
    [ValidateSet('plain','html')][string]$Format = 'plain',
    [switch]$PrepareSession,
    [switch]$FocusNative,
    [string]$BuildLedger = ''
)
$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $PSScriptRoot
if ([string]::IsNullOrWhiteSpace($Apk)) { $Apk = Join-Path $root 'android/module/build/outputs/apk/debug/module-debug.apk' }
if (!(Test-Path -LiteralPath $Apk -PathType Leaf)) { throw 'Expected integrated debug APK is missing' }
if ([string]::IsNullOrWhiteSpace($BuildLedger)) { $BuildLedger = Join-Path $root 'build/clipboard-validation/integrated-ledger.json' }
if ([string]::IsNullOrWhiteSpace($ThreadId)) { throw 'Pass the owning Codex thread ID' }
$lockDir = Join-Path $root 'build/coordination-locks'
New-Item -ItemType Directory -Force -Path $lockDir | Out-Null
$lockPath = Join-Path $lockDir 'devices.lock'
$lock = $null
try {
    $lock = [System.IO.File]::Open($lockPath,[System.IO.FileMode]::CreateNew,[System.IO.FileAccess]::ReadWrite,[System.IO.FileShare]::Read)
    $owner = [Text.Encoding]::UTF8.GetBytes("clipboard $ThreadId $([DateTimeOffset]::Now.ToString('o'))")
    $lock.Write($owner,0,$owner.Length)
    $lock.Flush()
    if ($PrepareSession) {
        if ($Direction -ne 'both') { throw 'Session preparation is limited to one both-direction window' }
        $focusArgs = @()
        if ($FocusNative) { $focusArgs = @('--focus-native') }
        python (Join-Path $PSScriptRoot 'prepare-clipboard-device.py') --apk (Resolve-Path -LiteralPath $Apk).Path --lock-owner $ThreadId --installed --format $Format --build-ledger $BuildLedger --stop-created-session @focusArgs
    } else {
        python (Join-Path $PSScriptRoot 'validate-clipboard-native.py') --apk (Resolve-Path -LiteralPath $Apk).Path --lock-owner $ThreadId --direction $Direction --format $Format --build-ledger $BuildLedger
    }
    if ($LASTEXITCODE -ne 0) { throw 'Native clipboard validation failed; inspect build/clipboard-validation/device-* evidence' }
} finally {
    if ($null -ne $lock) { $lock.Dispose(); Remove-Item -LiteralPath $lockPath }
}
