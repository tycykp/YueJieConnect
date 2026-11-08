$ErrorActionPreference = 'Stop'
$root = Split-Path $PSScriptRoot -Parent
$output = Join-Path $root 'build\calls-sms-validation\repair\lint'
New-Item -ItemType Directory -Force $output | Out-Null
$lockPath = Join-Path $root 'build\coordination-locks\build.lock'
$lease = [Guid]::NewGuid().ToString()
$lock = $null
try { $lock = [IO.File]::Open($lockPath, [IO.FileMode]::CreateNew, [IO.FileAccess]::Write, [IO.FileShare]::Read) }
catch [IO.IOException] { Write-Output 'Build lock busy; no Gradle task started.'; exit 2 }
function OwnedSnapshot {
    $source = Join-Path $root 'android\module\src\main\java\io\github\qiangjian\connect'
    $map = [ordered]@{}
    foreach ($file in @((Get-ChildItem $source -Filter 'TelephonySync*.java'), (Get-ChildItem $source -Filter 'OplusTelephony*.java'), (Get-ChildItem $source -Filter 'XiaomiTelephony*.java')) | ForEach-Object { $_ }) {
        $map[$file.FullName] = (Get-FileHash -LiteralPath $file.FullName -Algorithm SHA256).Hash.ToLowerInvariant()
    }
    return $map
}
try {
    $owner = [ordered]@{ task='calls-sms'; thread='01a1bbe4-ec32-7d01-a5de-8fc711849d20'; lease=$lease; utc=[DateTime]::UtcNow.ToString('o') } | ConvertTo-Json -Compress
    $bytes = [Text.Encoding]::UTF8.GetBytes($owner); $lock.Write($bytes, 0, $bytes.Length); $lock.Flush()
    $before = OwnedSnapshot
    $env:JAVA_HOME = 'C:\Program Files\Android\Android Studio\jbr'
    $env:ANDROID_HOME = Join-Path $env:LOCALAPPDATA 'Android\Sdk'
    Push-Location $root
    $ErrorActionPreference = 'Continue'
    & .\gradlew.bat :android:module:lintDebug --console=plain 2>&1 | Tee-Object -FilePath (Join-Path $output 'lint.txt')
    $lintExit = $LASTEXITCODE
    $ErrorActionPreference = 'Stop'
    $after = OwnedSnapshot
    $races = @($before.Keys | Where-Object { $before[$_] -ne $after[$_] })
    $report = Join-Path $root 'android\module\build\intermediates\lint_intermediate_text_report\debug\lintReportDebug\lint-results-debug.txt'
    if (Test-Path -LiteralPath $report) { Copy-Item -LiteralPath $report -Destination (Join-Path $output 'lint-report.txt') }
    [ordered]@{ task='calls-sms'; thread='01a1bbe4-ec32-7d01-a5de-8fc711849d20'; utc=[DateTime]::UtcNow.ToString('o'); command='gradlew.bat :android:module:lintDebug --console=plain'; scriptSha256=(Get-FileHash -LiteralPath $PSCommandPath -Algorithm SHA256).Hash.ToLowerInvariant(); exitCode=$lintExit; scope='Lint only; no tests/APK acceptance/install/devices/audio/call/SMS. Native OEM permission checks retained; module microphone permission not requested.'; sources=$after; races=$races } | ConvertTo-Json -Depth 5 | Set-Content -LiteralPath (Join-Path $output 'validation.json') -Encoding UTF8
    if ($lintExit -ne 0 -or $races.Count -ne 0) { throw 'Lint failed or telephony input changed; inspect evidence.' }
    Write-Output 'PASS: lint; telephony inputs unchanged during validation.'
} finally {
    if ((Get-Location).Path -eq $root) { Pop-Location }
    if ($null -ne $lock) {
        $lock.Dispose()
        if ((Get-Content -LiteralPath $lockPath -Raw | ConvertFrom-Json).lease -eq $lease) { Remove-Item -LiteralPath $lockPath }
    }
}
