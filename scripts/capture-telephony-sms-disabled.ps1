$ErrorActionPreference='Stop'
$root=Split-Path $PSScriptRoot -Parent
$output=Join-Path $root 'build\calls-sms-validation\usable\sms-resolution\disabled-packages.json'
if(Test-Path $output){throw 'Disabled/uninstalled SMS package metadata already checked; inspect existing evidence.'}
$lockPath=Join-Path $root 'build\coordination-locks\devices.lock';$lock=$null
try{$lock=[IO.File]::Open($lockPath,[IO.FileMode]::CreateNew,[IO.FileAccess]::Write,[IO.FileShare]::Read)}
catch [IO.IOException]{Write-Output 'Device lock busy; no disabled-package query executed.';exit 2}
try{
    $owner=[Text.Encoding]::UTF8.GetBytes('calls-sms / 01a1bbe4-ec32-7d01-a5de-8fc711849d20 / '+[DateTime]::UtcNow.ToString('o'));$lock.Write($owner,0,$owner.Length);$lock.Flush()
    $records=@()
    foreach($name in @('mms','sms','messag')){
        $lines=@(& adb -s b59f8656 shell pm list packages -u $name);$code=$LASTEXITCODE
        $records+=@{filter=$name;command="adb -s b59f8656 shell pm list packages -u $name";exitCode=$code;packages=$lines}
    }
    [ordered]@{thread='01a1bbe4-ec32-7d01-a5de-8fc711849d20';utc=[DateTime]::UtcNow.ToString('o');command='powershell -NoProfile -ExecutionPolicy Bypass -File scripts/capture-telephony-sms-disabled.ps1';scriptSha256=(Get-FileHash $PSCommandPath -Algorithm SHA256).Hash.ToLowerInvariant();scope='Package names including disabled/uninstalled users only. Does not enable/install a package or access SMS data.';records=$records}|ConvertTo-Json -Depth 5|Set-Content $output -Encoding UTF8
    Write-Output 'PASS: disabled/uninstalled native SMS candidate metadata saved; no state changes.'
}finally{if($null -ne $lock){$lock.Dispose();Remove-Item -LiteralPath $lockPath}}
