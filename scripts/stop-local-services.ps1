[CmdletBinding()]
param([string]$RedisWslDistribution = "Ubuntu-D")

$ErrorActionPreference = "Stop"
$projectRoot = Split-Path -Parent $PSScriptRoot
$keepAlivePidPath = Join-Path $projectRoot ".runtime\redis\wsl-keepalive.pid"

& (Join-Path $PSScriptRoot "stop-local-backend.ps1")
& (Join-Path $PSScriptRoot "stop-local-elasticsearch.ps1")

$mysql = Get-NetTCPConnection `
    -LocalAddress 127.0.0.1 `
    -LocalPort 3307 `
    -State Listen `
    -ErrorAction SilentlyContinue
if ($mysql) {
    & (Join-Path $PSScriptRoot "stop-local-mysql.ps1")
}

$redis = Get-NetTCPConnection `
    -LocalAddress 127.0.0.1 `
    -LocalPort 6379 `
    -State Listen `
    -ErrorAction SilentlyContinue
if ($redis) {
    & wsl.exe -d $RedisWslDistribution -u root -- service redis-server stop
    if ($LASTEXITCODE -ne 0) {
        throw "Failed to stop Redis in WSL distribution '$RedisWslDistribution'."
    }
}

if (Test-Path -LiteralPath $keepAlivePidPath) {
    $keepAlivePid = [int](Get-Content -Raw -LiteralPath $keepAlivePidPath).Trim()
    $keepAliveProcess = Get-Process -Id $keepAlivePid -ErrorAction SilentlyContinue
    if ($keepAliveProcess) {
        if ($keepAliveProcess.ProcessName -ne "wsl") {
            throw "PID $keepAlivePid is not the Waitfans WSL keepalive process; refusing to stop it."
        }
        Stop-Process -Id $keepAlivePid
    }
    Remove-Item -LiteralPath $keepAlivePidPath
}

Write-Output "Waitfans local services stopped."
