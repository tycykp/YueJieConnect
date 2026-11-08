$ErrorActionPreference='Stop'
$root=Split-Path $PSScriptRoot -Parent
$output=Join-Path $root 'build\calls-sms-validation\usable\phone-anchor'
New-Item -ItemType Directory -Force $output|Out-Null
$ledger=Join-Path $output 'device-metadata.json'
if(Test-Path $ledger){throw 'Phone Binder system-library metadata already captured; inspect the existing ledger.'}
$lockPath=Join-Path $root 'build\coordination-locks\devices.lock';$lock=$null
try{$lock=[IO.File]::Open($lockPath,[IO.FileMode]::CreateNew,[IO.FileAccess]::Write,[IO.FileShare]::Read)}
catch [IO.IOException]{Write-Output 'Device lock busy; no phone metadata operation started.';exit 2}
try{
    $owner=[Text.Encoding]::UTF8.GetBytes('calls-sms / 01a1bbe4-ec32-7d01-a5de-8fc711849d20 / '+[DateTime]::UtcNow.ToString('o'));$lock.Write($owner,0,$owner.Length);$lock.Flush()
    $records=@()
    foreach($serial in @('b59f8656','3B161Q00YN200000')){
        $service=@(& adb -s $serial shell service check phone);$serviceExit=$LASTEXITCODE
        $metadata=@(& adb -s $serial shell dumpsys package com.android.phone)
        if($LASTEXITCODE -ne 0){throw 'Phone package metadata unavailable'}
        $permissions=@($metadata|Where-Object{$_ -match 'android.permission.(RECORD_AUDIO|CAPTURE_AUDIO_OUTPUT|MODIFY_AUDIO_SETTINGS)(:|$)'})
        $destination=Join-Path $root ('captures\calls-sms-20261108\'+$serial+'\system-libraries')
        New-Item -ItemType Directory -Force $destination|Out-Null
        $library=Join-Path $destination 'libbinder_ndk.so'
        if(!(Test-Path $library)){
            & adb -s $serial pull /system/lib64/libbinder_ndk.so $library
            if($LASTEXITCODE -ne 0){throw 'Fixed system Binder library extraction failed'}
        }
        $records+=@{serial=$serial;phoneService=$service;serviceCheckExit=$serviceExit;phoneAudioPermissionMetadata=$permissions;library=$library;librarySha256=(Get-FileHash $library -Algorithm SHA256).Hash.ToLowerInvariant()}
    }
    [ordered]@{thread='01a1bbe4-ec32-7d01-a5de-8fc711849d20';utc=[DateTime]::UtcNow.ToString('o');command='powershell -NoProfile -ExecutionPolicy Bypass -File scripts/capture-telephony-phone-anchor.ps1';scriptSha256=(Get-FileHash $PSCommandPath -Algorithm SHA256).Hash.ToLowerInvariant();scope='Fixed system library and phone service/permission metadata only. Shell service existence is not Bridge UID access or process authentication. No transact, start/kill, record, call, SMS or microphone operation.';records=$records}|ConvertTo-Json -Depth 5|Set-Content $ledger -Encoding UTF8
    Write-Output 'PASS: phone Binder library/permission metadata captured; no phone transaction or audio.'
}finally{if($null -ne $lock){$lock.Dispose();Remove-Item -LiteralPath $lockPath}}
