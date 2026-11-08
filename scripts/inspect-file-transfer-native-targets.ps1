param([string]$ThreadId='01a1bbe4-daaf-7d83-a571-a2218ce9ab1f')
$ErrorActionPreference='Stop'
$repo=[IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
$out=Join-Path $repo 'build/file-transfer-validation/native-targets'
$lock=Join-Path $repo 'build/coordination-locks/devices.lock'
New-Item -ItemType Directory -Force -Path $out,(Split-Path $lock)|Out-Null
$lease=$null
try {
    try{$lease=[IO.File]::Open($lock,[IO.FileMode]::CreateNew,[IO.FileAccess]::Write,[IO.FileShare]::Read)}
    catch [IO.IOException]{Write-Output 'LOCK_BUSY: no device command executed';return}
    $owner=[Text.Encoding]::UTF8.GetBytes("files-native-targets $ThreadId $([DateTimeOffset]::Now.ToString('o'))")
    $lease.Write($owner,0,$owner.Length);$lease.Flush()
    $adb=(Get-Command adb).Source
    $records=@()
    foreach($target in @(@{serial='b59f8656';package='com.android.fileexplorer';brand='hyperos4'},@{serial='3B161Q00YN200000';package='com.coloros.filemanager';brand='coloros17'})){
        $prefix=Join-Path $out $target.serial
        $paths=@(& $adb -s $target.serial shell pm path $target.package 2>&1)
        $pathExit=$LASTEXITCODE
        $paths|Set-Content -LiteralPath "$prefix-path.txt" -Encoding utf8
        if($pathExit -ne 0){throw 'Native target package query failed; not retried'}
        $resolved=@(& $adb -s $target.serial shell cmd package resolve-activity --brief -a android.intent.action.MAIN -c android.intent.category.LAUNCHER -p $target.package 2>&1)
        $resolveExit=$LASTEXITCODE
        $resolved|Set-Content -LiteralPath "$prefix-launcher.txt" -Encoding utf8
        $capture=Join-Path $repo ("captures/device-interconnect-20261108/"+$target.brand+"/files-native/"+$target.package)
        New-Item -ItemType Directory -Force -Path $capture|Out-Null
        $files=@();$index=0
        foreach($line in $paths){
            $text=[string]$line
            if(-not $text.StartsWith('package:')){continue}
            $remote=$text.Substring(8).Trim()
            if($remote -notmatch '^/(data/app|system|system_ext|product|my_product|vendor|odm|apex)/[^\r\n]+\.apk$'){throw 'Unexpected APK path'}
            $file=Join-Path $capture ("target-$index.apk")
            if(Test-Path -LiteralPath $file){throw 'Target capture exists; inspect/reuse it instead of overwriting'}
            $pulled=@(& $adb -s $target.serial pull $remote $file 2>&1)
            if($LASTEXITCODE -ne 0){throw 'APK read failed; not retried'}
            $pulled|Set-Content -LiteralPath "$prefix-pull-$index.txt" -Encoding utf8
            $files+=[ordered]@{path=$file.Substring($repo.Length+1).Replace('\','/');sha256=(Get-FileHash -LiteralPath $file -Algorithm SHA256).Hash.ToLowerInvariant()};$index++
        }
        $records+=[ordered]@{serial=$target.serial;package=$target.package;path_exit=$pathExit;resolve_exit=$resolveExit;files=$files}
    }
    [ordered]@{utc=[DateTime]::UtcNow.ToString('o');command='powershell -NoProfile -File scripts/inspect-file-transfer-native-targets.ps1';script_sha256=(Get-FileHash -LiteralPath $PSCommandPath -Algorithm SHA256).Hash.ToLowerInvariant();scope='Read-only exact native package paths/launcher/APK bytes; no UI, user files, installation, process or session changes';records=$records}|ConvertTo-Json -Depth 6|Set-Content -LiteralPath (Join-Path $out 'ledger.json') -Encoding utf8
    Write-Output 'READ_ONLY_CAPTURED: exact native target APKs and launcher metadata'
}finally{if($null -ne $lease){$lease.Dispose();Remove-Item -LiteralPath $lock}}
