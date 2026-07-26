[CmdletBinding()]
param([string]$RedisWslDistribution = "Ubuntu-D")

$ErrorActionPreference = "Stop"
$projectRoot = Split-Path -Parent $PSScriptRoot
$redisRuntime = Join-Path $projectRoot ".runtime\redis"
$keepAlivePidPath = Join-Path $redisRuntime "wsl-keepalive.pid"

function Test-TcpPort {
    param([string]$HostName, [int]$TargetPort)
    $client = New-Object System.Net.Sockets.TcpClient
    try {
        $task = $client.ConnectAsync($HostName, $TargetPort)
        return $task.Wait(1000) -and $client.Connected
    }
    catch {
        return $false
    }
    finally {
        $client.Dispose()
    }
}

$keepAliveRunning = $false
if (Test-Path -LiteralPath $keepAlivePidPath) {
    $keepAlivePid = [int](Get-Content -Raw -LiteralPath $keepAlivePidPath).Trim()
    $keepAliveProcess = Get-Process -Id $keepAlivePid -ErrorAction SilentlyContinue
    $keepAliveRunning = $keepAliveProcess -and $keepAliveProcess.ProcessName -eq "wsl"
}
if (-not $keepAliveRunning) {
    New-Item -ItemType Directory -Force -Path $redisRuntime | Out-Null
    $keepAlive = Start-Process `
        -FilePath "wsl.exe" `
        -ArgumentList @(
            "-d",
            $RedisWslDistribution,
            "--exec",
            "tail",
            "-f",
            "/dev/null"
        ) `
        -WindowStyle Hidden `
        -RedirectStandardOutput (Join-Path $redisRuntime "wsl-keepalive.stdout.log") `
        -RedirectStandardError (Join-Path $redisRuntime "wsl-keepalive.stderr.log") `
        -PassThru
    [System.IO.File]::WriteAllText(
        $keepAlivePidPath,
        [string]$keepAlive.Id,
        (New-Object System.Text.UTF8Encoding($false))
    )
    Start-Sleep -Seconds 2
}

& wsl.exe -d $RedisWslDistribution -u root -- sysctl -w vm.overcommit_memory=1
if ($LASTEXITCODE -ne 0) {
    throw "Failed to configure Redis memory overcommit in WSL distribution '$RedisWslDistribution'."
}

if (-not (Test-TcpPort -HostName "127.0.0.1" -TargetPort 6379)) {
    & wsl.exe -d $RedisWslDistribution -u root -- service redis-server start
    if ($LASTEXITCODE -ne 0) {
        throw "Failed to start Redis in WSL distribution '$RedisWslDistribution'."
    }
}

& (Join-Path $PSScriptRoot "start-local-mysql.ps1")
& (Join-Path $PSScriptRoot "start-local-elasticsearch.ps1")
& (Join-Path $PSScriptRoot "check-env.ps1")
