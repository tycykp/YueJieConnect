$ErrorActionPreference='Stop'
$root=Split-Path $PSScriptRoot -Parent
$output=Join-Path $root 'build\calls-sms-validation\usable\audio-channel'
New-Item -ItemType Directory -Force $output|Out-Null
$source=Join-Path $root 'transport\src\telephony_audio.rs'
$manifest=Join-Path $output 'Cargo.toml';$portable=$source.Replace([char]92,[char]47)
$content=@"
[package]
name = "yj-telephony-audio-validation"
version = "0.0.0"
edition = "2021"
[workspace]
[lib]
path = "$portable"
[dependencies]
snow = "=0.9.6"
getrandom = "=0.2.16"
sha2 = "=0.10.9"
"@
[IO.File]::WriteAllText($manifest,$content,[Text.UTF8Encoding]::new($false))
$lockPath=Join-Path $root 'build\coordination-locks\build.lock';$lock=$null
try{$lock=[IO.File]::Open($lockPath,[IO.FileMode]::CreateNew,[IO.FileAccess]::Write,[IO.FileShare]::Read)}
catch [IO.IOException]{Write-Output 'Build lock busy; no audio-channel build started.';exit 2}
try{
    $owner=[Text.Encoding]::UTF8.GetBytes('calls-sms / 01a1bbe4-ec32-7d01-a5de-8fc711849d20 / '+[DateTime]::UtcNow.ToString('o'))
    $lock.Write($owner,0,$owner.Length);$lock.Flush()
    foreach($step in @('test','clippy')){
        $cargoArgs=@($step,'--offline','--manifest-path',$manifest,'--target-dir',(Join-Path $output 'target'))
        if($step -eq 'clippy'){$cargoArgs+=@('--all-targets','--','-D','warnings')}
        $ErrorActionPreference='Continue'
        & cargo @cargoArgs 2>&1 | Tee-Object -FilePath (Join-Path $output "$step.txt")
        $code=$LASTEXITCODE;$ErrorActionPreference='Stop';if($code -ne 0){throw "Audio channel $step failed: $code"}
    }
    [ordered]@{thread='01a1bbe4-ec32-7d01-a5de-8fc711849d20';utc=[DateTime]::UtcNow.ToString('o');command='powershell -NoProfile -ExecutionPolicy Bypass -File scripts/test-telephony-audio-channel.ps1';scope='Real localhost duplex AUDIO-purpose Noise, synthetic PCM only; no JNI/Android microphone/call/device';sources=@(@($source,$manifest,$PSCommandPath,(Join-Path $output 'Cargo.lock'))|ForEach-Object{@{path=$_;sha256=(Get-FileHash -LiteralPath $_ -Algorithm SHA256).Hash.ToLowerInvariant()}})}|ConvertTo-Json -Depth 5|Set-Content (Join-Path $output 'validation.json') -Encoding UTF8
}finally{if($null -ne $lock){$lock.Dispose();Remove-Item -LiteralPath $lockPath}}
