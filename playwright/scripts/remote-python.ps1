param(
    [Parameter(Mandatory=$true)][string]$Script,
    [string]$RemoteHost = '192.168.31.77',
    [string]$SshUser = 'wdidada',
    [string]$Argument = '',
    [string]$Output = ''
)
$ErrorActionPreference = 'Stop'
if (!$env:CLUSTER_SSH_PASSWORD) { throw 'Set CLUSTER_SSH_PASSWORD in the process environment' }
if ($Argument -and $Argument -notmatch '^[A-Za-z0-9_,-]+$') { throw 'Unsafe remote argument' }
$resolved = (Resolve-Path -LiteralPath $Script).Path
$encoded = [Convert]::ToBase64String([IO.File]::ReadAllBytes($resolved))
$command = "sudo -S -p '' -v && printf '%s' '$encoded' | base64 -d | sudo -n python3 - $Argument"
$result = $env:CLUSTER_SSH_PASSWORD | & 'C:\Program Files\PuTTY\plink.exe' -ssh -batch -l $SshUser -pw $env:CLUSTER_SSH_PASSWORD $RemoteHost $command
$remoteExit = $LASTEXITCODE
if ($Output) {
    $parsed = ($result -join "`n") | ConvertFrom-Json
    $parent = Split-Path -Parent $Output
    if ($parent) { New-Item -ItemType Directory -Force -Path $parent | Out-Null }
    $parsed | ConvertTo-Json -Depth 30 | Set-Content -LiteralPath $Output -Encoding utf8
}
$result
if ($remoteExit -ne 0) { throw "Remote check failed: exit=$remoteExit; inspect saved JSON evidence" }
