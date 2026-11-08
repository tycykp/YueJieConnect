param([Parameter(Mandatory=$true)][ValidateSet('prepare','pair','native-ui','profiles','native-select','native-connect','notifications','notification-actions','finder-readiness','rich-and-disconnect','cleanup-ui','final-observation')][string]$Phase)
$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $PSScriptRoot
$lockPath = Join-Path $root 'build/coordination-locks/devices.lock'
[IO.Directory]::CreateDirectory((Split-Path -Parent $lockPath)) | Out-Null
$lease = $null
try {
    $lease = [IO.File]::Open($lockPath,[IO.FileMode]::CreateNew,[IO.FileAccess]::ReadWrite,[IO.FileShare]::Read)
    $owner = [Text.Encoding]::UTF8.GetBytes('functional-test 01a1bbcf-5271-7900-bb0d-bee38aff77b9 ' + [DateTimeOffset]::Now.ToString('o'))
    $lease.Write($owner,0,$owner.Length)
    $lease.Flush()
    $env:PYTHONIOENCODING = 'utf-8'
    python (Join-Path $PSScriptRoot 'run-functional-sweep.py') $Phase
    if ($LASTEXITCODE -ne 0) { throw "Functional sweep phase $Phase failed; recorded without retry" }
} finally {
    if ($null -ne $lease) { $lease.Dispose(); Remove-Item -LiteralPath $lockPath }
}
