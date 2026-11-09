$ErrorActionPreference='Stop'
$root=Split-Path $PSScriptRoot -Parent
$output=Join-Path $root 'build\calls-sms-validation\usable\connections'
$classes=Join-Path $output 'classes';New-Item -ItemType Directory -Force $classes|Out-Null
$source=Join-Path $root 'android\module\src\main\java\io\github\yuejie\connect'
$fixtures=Join-Path $PSScriptRoot 'telephony-fixtures'
$files=@('TelephonySyncConnections.java','TelephonySyncController.java','TelephonySyncAudioSession.java'|ForEach-Object{Get-Item (Join-Path $source $_)})+@(Get-ChildItem $fixtures -Recurse -Filter '*.java')
$jdk='C:\Program Files\Android\Android Studio\jbr\bin'
$ErrorActionPreference='Continue'
& (Join-Path $jdk 'javac.exe') --release 17 -encoding UTF-8 -d $classes @($files.FullName) 2>&1 | Tee-Object -FilePath (Join-Path $output 'compile.txt')
$code=$LASTEXITCODE;$ErrorActionPreference='Stop';if($code -ne 0){throw 'Isolated native connection wrapper compilation failed'}
& (Join-Path $jdk 'java.exe') -cp $classes io.github.yuejie.connect.TelephonySyncConnectionsCheck | Tee-Object -FilePath (Join-Path $output 'tests.txt')
if($LASTEXITCODE -ne 0){throw 'Native connection wrapper scenarios failed'}
[ordered]@{thread='01a1bbe4-ec32-7d01-a5de-8fc711849d20';utc=[DateTime]::UtcNow.ToString('o');command='powershell -NoProfile -ExecutionPolicy Bypass -File scripts/test-telephony-connections.ps1';scope='Actual production Connections wrapper against host-only Android/native/Looper doubles. Not an OEM UID/PID proof, native UI, microphone or call test.';sources=@($files|ForEach-Object{@{path=$_.FullName;sha256=(Get-FileHash $_.FullName -Algorithm SHA256).Hash.ToLowerInvariant()}})}|ConvertTo-Json -Depth 5|Set-Content (Join-Path $output 'validation.json') -Encoding UTF8
