[CmdletBinding()]
param(
    [string]$MySqlHome = "C:\Program Files\MySQL\MySQL Server 8.0",
    [int]$Port = 3307
)

$ErrorActionPreference = "Stop"
$projectRoot = Split-Path -Parent $PSScriptRoot
$runtimeRoot = Join-Path $projectRoot ".runtime\mysql"
$rootPasswordPath = Join-Path $runtimeRoot ".root-password"
$mysqlAdmin = Join-Path $MySqlHome "bin\mysqladmin.exe"

if (-not (Test-Path -LiteralPath $rootPasswordPath)) {
    throw "Generated local MySQL root password was not found: $rootPasswordPath"
}

$previousMysqlPassword = $env:MYSQL_PWD
$env:MYSQL_PWD = (Get-Content -Raw -LiteralPath $rootPasswordPath).Trim()
try {
    & $mysqlAdmin `
        --protocol=TCP `
        --host=127.0.0.1 `
        "--port=$Port" `
        --user=root `
        shutdown
    if ($LASTEXITCODE -ne 0) {
        throw "MySQL shutdown failed."
    }
}
finally {
    $env:MYSQL_PWD = $previousMysqlPassword
}

Write-Output "Waitfans local MySQL stopped."
