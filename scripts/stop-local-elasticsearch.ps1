[CmdletBinding()]
param([int]$Port = 9200)

$ErrorActionPreference = "Stop"
$projectRoot = Split-Path -Parent $PSScriptRoot
$expectedHome = (Join-Path $projectRoot ".runtime\elasticsearch").ToLowerInvariant()
$connection = Get-NetTCPConnection `
    -LocalAddress 127.0.0.1 `
    -LocalPort $Port `
    -State Listen `
    -ErrorAction SilentlyContinue |
    Select-Object -First 1

if (-not $connection) {
    Write-Output "Elasticsearch is not listening on port $Port."
    exit 0
}

$elasticsearchProcess = Get-Process -Id $connection.OwningProcess -ErrorAction Stop
$processPath = ([string]$elasticsearchProcess.Path).ToLowerInvariant()
if ($elasticsearchProcess.ProcessName -ne "java" -or
    -not $processPath.StartsWith($expectedHome)) {
    throw "Port $Port is owned by a process outside the Waitfans runtime; refusing to stop it."
}

Stop-Process -Id $connection.OwningProcess
Write-Output "Waitfans local Elasticsearch stopped (PID $($connection.OwningProcess))."
