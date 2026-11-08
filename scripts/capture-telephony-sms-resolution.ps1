$ErrorActionPreference='Stop'
$root=Split-Path $PSScriptRoot -Parent
$output=Join-Path $root 'build\calls-sms-validation\usable\sms-resolution'
New-Item -ItemType Directory -Force $output|Out-Null
$ledger=Join-Path $output 'capture.json'
if(Test-Path -LiteralPath $ledger){throw 'SMS resolution metadata was already captured; inspect the ledger before another probe.'}
$lockPath=Join-Path $root 'build\coordination-locks\devices.lock';$lock=$null
try{$lock=[IO.File]::Open($lockPath,[IO.FileMode]::CreateNew,[IO.FileAccess]::Write,[IO.FileShare]::Read)}
catch [IO.IOException]{Write-Output 'Device lock busy; no metadata command executed.';exit 2}
try{
    $owner=[Text.Encoding]::UTF8.GetBytes('calls-sms / 01a1bbe4-ec32-7d01-a5de-8fc711849d20 / '+[DateTime]::UtcNow.ToString('o'))
    $lock.Write($owner,0,$owner.Length);$lock.Flush()
    $records=@()
    foreach($query in @(
        @{name='sendto';args=@('shell','cmd','package','query-activities','--brief','--user','0','-a','android.intent.action.SENDTO','-d','smsto:5550100')},
        @{name='sms_view';args=@('shell','cmd','package','query-activities','--brief','--user','0','-a','android.intent.action.VIEW','-d','content://sms')},
        @{name='sms_role';args=@('shell','cmd','role','get-role-holders','--user','0','android.app.role.SMS')},
        @{name='contacts_path';args=@('shell','pm','path','com.android.contacts')}
    )){
        $arguments=@('-s','b59f8656')+$query.args
        $text=@(& adb @arguments);$code=$LASTEXITCODE
        $records+=@{name=$query.name;command=('adb '+($arguments -join ' '));exitCode=$code;output=$text}
        if($query.name -eq 'contacts_path' -and $code -eq 0 -and $text.Count -eq 1 -and $text[0] -match '^package:/(system|product|system_ext)/[^\r\n]+\.apk$'){
            $destination=Join-Path $root 'captures\calls-sms-20261108\b59f8656\com.android.contacts'
            New-Item -ItemType Directory -Force $destination|Out-Null
            $apk=Join-Path $destination 'base.apk'
            if(!(Test-Path -LiteralPath $apk)){
                & adb -s b59f8656 pull $text[0].Substring(8) $apk
                if($LASTEXITCODE -ne 0){throw 'Stock contacts APK pull failed'}
            }
            $records+=@{name='stock_contacts_apk';path=$apk;sha256=(Get-FileHash $apk -Algorithm SHA256).Hash.ToLowerInvariant()}
        }
    }
    [ordered]@{thread='01a1bbe4-ec32-7d01-a5de-8fc711849d20';utc=[DateTime]::UtcNow.ToString('o');command='powershell -NoProfile -ExecutionPolicy Bypass -File scripts/capture-telephony-sms-resolution.ps1';scriptSha256=(Get-FileHash $PSCommandPath -Algorithm SHA256).Hash.ToLowerInvariant();scope='PackageManager intent resolution and SMS role metadata plus fixed stock contacts APK only. No activity start, UI, provider query, SMS send/record read or permission changes.';records=$records}|ConvertTo-Json -Depth 7|Set-Content $ledger -Encoding UTF8
    Write-Output 'PASS: stock SMS intent/role metadata captured; no SMS or UI operation.'
}finally{if($null -ne $lock){$lock.Dispose();Remove-Item -LiteralPath $lockPath}}
