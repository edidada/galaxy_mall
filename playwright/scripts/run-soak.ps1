param(
    [string]$Duration = '30m',
    [int]$Rate = 5,
    [string]$K6 = 'k6',
    [string]$Output = 'artifacts/soak'
)
$ErrorActionPreference = 'Stop'
if ($env:K6_DURATION -or $env:K6_VUS) { throw 'Unset native K6_DURATION/K6_VUS before this scenario test' }
if ($Rate -lt 1 -or $Rate -gt 10000) { throw 'Rate out of range' }
New-Item -ItemType Directory -Force -Path $Output | Out-Null
$env:K6_ALLOW_LOAD = '1'
$env:LOAD_DURATION = $Duration
$env:K6_RATE = [string]$Rate
$env:K6_OUTPUT_DIR = $Output
& $K6 run --quiet performance/catalog.k6.js
if ($LASTEXITCODE -ne 0) { throw "Soak failed: exit=$LASTEXITCODE" }
