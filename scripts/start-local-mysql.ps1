[CmdletBinding()]
param(
    [string]$MySqlHome = "C:\Program Files\MySQL\MySQL Server 8.0",
    [int]$Port = 3307
)

$ErrorActionPreference = "Stop"
$projectRoot = Split-Path -Parent $PSScriptRoot
$runtimeRoot = Join-Path $projectRoot ".runtime\mysql"
$configPath = Join-Path $runtimeRoot "my.ini"
$mysqld = Join-Path $MySqlHome "bin\mysqld.exe"

$client = New-Object System.Net.Sockets.TcpClient
try {
    $task = $client.ConnectAsync("127.0.0.1", $Port)
    if ($task.Wait(1000) -and $client.Connected) {
        Write-Output "Waitfans local MySQL is already running on port $Port."
        exit 0
    }
}
catch {
}
finally {
    $client.Dispose()
}

if (-not (Test-Path -LiteralPath $configPath)) {
    throw "Local MySQL has not been initialized. Run scripts\setup-local-mysql.ps1 first."
}

$process = Start-Process `
    -FilePath $mysqld `
    -ArgumentList "--defaults-file=$configPath" `
    -WorkingDirectory $runtimeRoot `
    -WindowStyle Hidden `
    -PassThru
Write-Output "Waitfans local MySQL started (PID $($process.Id), port $Port)."
