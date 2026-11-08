param(
 [string]$Apk='build/native-pairing-validation/module-v20-runtime-candidate.apk',
 [string]$Metadata='build/native-pairing-validation/v20-runtime-candidate-apk.json'
)
$ErrorActionPreference='Stop'
$root=Split-Path -Parent $PSScriptRoot
$lockPath=Join-Path $root 'build/coordination-locks/devices.lock'
$token=[Guid]::NewGuid().ToString('N')
$lease=$null
try {
 $lease=[IO.File]::Open($lockPath,[IO.FileMode]::CreateNew,[IO.FileAccess]::ReadWrite,[IO.FileShare]::Read)
 $bytes=[Text.Encoding]::UTF8.GetBytes("device-finder 01a1bbe4-f070-7731-9b98-3abbf216410c $token $([DateTimeOffset]::Now.ToString('o'))")
 $lease.Write($bytes,0,$bytes.Length);$lease.Flush()
 python -X utf8 -u (Join-Path $PSScriptRoot 'test-device-finder-native.py') --apk $Apk --metadata $Metadata
 if($LASTEXITCODE -ne 0){throw 'Finder window failed; inspect its evidence'}
} finally {
 if($null -ne $lease){$lease.Dispose();if([IO.File]::ReadAllText($lockPath).Contains($token)){Remove-Item -LiteralPath $lockPath}}
}
