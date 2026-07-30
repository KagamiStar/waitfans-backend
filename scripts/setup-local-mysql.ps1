[CmdletBinding()]
param(
    [string]$MySqlHome = "C:\Program Files\MySQL\MySQL Server 8.0",
    [int]$Port = 3307
)

$ErrorActionPreference = "Stop"
$projectRoot = Split-Path -Parent $PSScriptRoot
$runtimeRoot = Join-Path $projectRoot ".runtime\mysql"
$dataDir = Join-Path $runtimeRoot "data"
$configPath = Join-Path $runtimeRoot "my.ini"
$rootPasswordPath = Join-Path $runtimeRoot ".root-password"
$envPath = Join-Path $projectRoot ".env.local"
$sqlPath = Join-Path $projectRoot "database\waitfans.sql"
$layoutSqlPath = Join-Path $projectRoot "database\sharded-layout.sql"
$mysqld = Join-Path $MySqlHome "bin\mysqld.exe"
$mysql = Join-Path $MySqlHome "bin\mysql.exe"
$managedDatabases = @(
    "waitfans",
    "waitfans_carousel",
    "waitfans_video_anime",
    "waitfans_video_guochuang",
    "waitfans_video_douga",
    "waitfans_video_game",
    "waitfans_video_kichiku",
    "waitfans_video_music",
    "waitfans_video_dance",
    "waitfans_video_cinephile",
    "waitfans_video_ent",
    "waitfans_video_knowledge",
    "waitfans_video_tech",
    "waitfans_video_information",
    "waitfans_video_food",
    "waitfans_video_life",
    "waitfans_video_car",
    "waitfans_video_fashion",
    "waitfans_video_sports",
    "waitfans_video_animal",
    "waitfans_video_virtual"
)
$databaseSql = ($managedDatabases | ForEach-Object {
    "CREATE DATABASE IF NOT EXISTS ``$_`` CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;"
}) -join "`n"
$grantSql = ($managedDatabases | ForEach-Object {
    "GRANT ALL PRIVILEGES ON ``$_``.* TO 'waitfans_app'@'localhost';"
}) -join "`n"

function New-RandomPassword {
    param([int]$Length = 32)

    $alphabet = "abcdefghijkmnopqrstuvwxyzABCDEFGHJKLMNPQRSTUVWXYZ23456789"
    $bytes = New-Object byte[] $Length
    $rng = [System.Security.Cryptography.RandomNumberGenerator]::Create()
    try {
        $rng.GetBytes($bytes)
    }
    finally {
        $rng.Dispose()
    }

    $builder = New-Object System.Text.StringBuilder
    foreach ($byte in $bytes) {
        [void]$builder.Append($alphabet[$byte % $alphabet.Length])
    }
    return $builder.ToString()
}

function Wait-ForPort {
    param(
        [string]$HostName,
        [int]$TargetPort,
        [int]$Attempts = 30
    )

    for ($i = 0; $i -lt $Attempts; $i++) {
        $client = New-Object System.Net.Sockets.TcpClient
        try {
            $task = $client.ConnectAsync($HostName, $TargetPort)
            if ($task.Wait(1000) -and $client.Connected) {
                return
            }
        }
        catch {
        }
        finally {
            $client.Dispose()
        }
        Start-Sleep -Seconds 1
    }
    throw "MySQL did not open port $TargetPort."
}

function Set-LocalEnvironment {
    param(
        [string]$AppPassword
    )

    $values = [ordered]@{
        "WAITFANS_DB_URL" = "jdbc:mysql://127.0.0.1:$Port/waitfans?serverTimezone=Asia/Shanghai&useUnicode=true&characterEncoding=utf-8&useSSL=false&allowMultiQueries=true&allowPublicKeyRetrieval=true"
        "WAITFANS_DB_USERNAME" = "waitfans_app"
        "WAITFANS_DB_PASSWORD" = $AppPassword
        "WAITFANS_REDIS_HOST" = "127.0.0.1"
        "WAITFANS_REDIS_PORT" = "6379"
        "WAITFANS_ES_HOST" = "127.0.0.1"
        "WAITFANS_ES_PORT" = "9200"
    }

    $lines = [System.Collections.Generic.List[string]]::new()
    if (Test-Path -LiteralPath $envPath) {
        foreach ($line in Get-Content -LiteralPath $envPath) {
            [void]$lines.Add([string]$line)
        }
    }

    foreach ($entry in $values.GetEnumerator()) {
        $replacement = "$($entry.Key)=$($entry.Value)"
        $found = $false
        for ($i = 0; $i -lt $lines.Count; $i++) {
            if ($lines[$i] -match "^\s*$([regex]::Escape($entry.Key))=") {
                $lines[$i] = $replacement
                $found = $true
                break
            }
        }
        if (-not $found) {
            $lines.Add($replacement)
        }
    }

    $utf8 = New-Object System.Text.UTF8Encoding($false)
    [System.IO.File]::WriteAllLines($envPath, $lines, $utf8)
}

if (-not (Test-Path -LiteralPath $mysqld) -or -not (Test-Path -LiteralPath $mysql)) {
    throw "MySQL Server 8.0 binaries were not found under '$MySqlHome'."
}
if (-not (Test-Path -LiteralPath $sqlPath)) {
    throw "Database script not found: $sqlPath"
}
if (-not (Test-Path -LiteralPath $layoutSqlPath)) {
    throw "Database script not found: $layoutSqlPath"
}

New-Item -ItemType Directory -Force -Path $runtimeRoot | Out-Null
$hasExistingData = (Test-Path -LiteralPath $dataDir) -and
    (Get-ChildItem -Force -LiteralPath $dataDir -ErrorAction SilentlyContinue | Measure-Object).Count -gt 0
$utf8 = New-Object System.Text.UTF8Encoding($false)

if ($hasExistingData) {
    if (-not (Test-Path -LiteralPath $configPath) -or
        -not (Test-Path -LiteralPath $rootPasswordPath)) {
        throw "Local MySQL contains data but has no managed credentials. Refusing to modify it: $dataDir"
    }

    $portOpen = $false
    $client = New-Object System.Net.Sockets.TcpClient
    try {
        $task = $client.ConnectAsync("127.0.0.1", $Port)
        $portOpen = $task.Wait(1000) -and $client.Connected
    }
    catch {
        $portOpen = $false
    }
    finally {
        $client.Dispose()
    }

    if (-not $portOpen) {
        Start-Process `
            -FilePath $mysqld `
            -ArgumentList "--defaults-file=$configPath" `
            -WorkingDirectory $runtimeRoot `
            -WindowStyle Hidden | Out-Null
        Wait-ForPort -HostName "127.0.0.1" -TargetPort $Port
    }

    $rootPassword = (Get-Content -Raw -LiteralPath $rootPasswordPath).Trim()
    $appPassword = New-RandomPassword
    $resumeSql = @"
$databaseSql
CREATE USER IF NOT EXISTS 'waitfans_app'@'localhost' IDENTIFIED BY '$appPassword';
ALTER USER 'waitfans_app'@'localhost' IDENTIFIED BY '$appPassword';
$grantSql
FLUSH PRIVILEGES;
"@
    $previousMysqlPassword = $env:MYSQL_PWD
    $env:MYSQL_PWD = $rootPassword
    try {
        & $mysql `
            --protocol=TCP `
            --host=127.0.0.1 `
            "--port=$Port" `
            --user=root `
            "--execute=$resumeSql"
        if ($LASTEXITCODE -ne 0) {
            throw "Failed to refresh the local Waitfans application credentials."
        }

        $videoObjectType = & $mysql `
            --protocol=TCP `
            --host=127.0.0.1 `
            "--port=$Port" `
            --user=root `
            --batch `
            --skip-column-names `
            "--execute=SELECT TABLE_TYPE FROM information_schema.TABLES WHERE TABLE_SCHEMA = 'waitfans' AND TABLE_NAME = 'video';"
        if ($videoObjectType -eq "BASE TABLE") {
            $layoutArgs = @(
                "--protocol=TCP",
                "--host=127.0.0.1",
                "--port=$Port",
                "--user=root",
                "--default-character-set=utf8mb4"
            )
            $layoutProcess = Start-Process `
                -FilePath $mysql `
                -ArgumentList $layoutArgs `
                -RedirectStandardInput $layoutSqlPath `
                -NoNewWindow `
                -Wait `
                -PassThru
            if ($layoutProcess.ExitCode -ne 0) {
                throw "Failed to migrate the existing database to the partitioned video layout."
            }
        }
    }
    finally {
        $env:MYSQL_PWD = $previousMysqlPassword
    }

    Set-LocalEnvironment -AppPassword $appPassword
    Write-Output "Waitfans local MySQL is ready on 127.0.0.1:$Port."
    Write-Output "Application credentials were refreshed in .env.local; the root password remains under .runtime."
    return
}
New-Item -ItemType Directory -Force -Path $dataDir | Out-Null

$baseDirIni = $MySqlHome.Replace("\", "/")
$dataDirIni = $dataDir.Replace("\", "/")
$logPathIni = (Join-Path $runtimeRoot "mysql-error.log").Replace("\", "/")
$pidPathIni = (Join-Path $runtimeRoot "mysqld.pid").Replace("\", "/")
$config = @"
[mysqld]
basedir="$baseDirIni"
datadir="$dataDirIni"
port=$Port
bind-address=127.0.0.1
mysqlx=0
character-set-server=utf8mb4
collation-server=utf8mb4_unicode_ci
default-time-zone=+08:00
log-error="$logPathIni"
pid-file="$pidPathIni"
secure-file-priv=""

[client]
host=127.0.0.1
port=$Port
default-character-set=utf8mb4
"@
[System.IO.File]::WriteAllText($configPath, $config, $utf8)

& $mysqld "--defaults-file=$configPath" --initialize-insecure --console
if ($LASTEXITCODE -ne 0) {
    throw "MySQL data directory initialization failed."
}

$serverProcess = Start-Process `
    -FilePath $mysqld `
    -ArgumentList "--defaults-file=$configPath" `
    -WorkingDirectory $runtimeRoot `
    -WindowStyle Hidden `
    -PassThru
Wait-ForPort -HostName "127.0.0.1" -TargetPort $Port

$rootPassword = New-RandomPassword
$appPassword = New-RandomPassword
$adminSql = @"
ALTER USER 'root'@'localhost' IDENTIFIED BY '$rootPassword';
$databaseSql
CREATE USER 'waitfans_app'@'localhost' IDENTIFIED BY '$appPassword';
$grantSql
FLUSH PRIVILEGES;
"@

& $mysql `
    --protocol=TCP `
    --host=127.0.0.1 `
    "--port=$Port" `
    --user=root `
    "--execute=$adminSql"
if ($LASTEXITCODE -ne 0) {
    Stop-Process -Id $serverProcess.Id -Force -ErrorAction SilentlyContinue
    throw "Failed to create the Waitfans database and local users."
}

$previousMysqlPassword = $env:MYSQL_PWD
$env:MYSQL_PWD = $appPassword
try {
    $importArgs = @(
        "--protocol=TCP",
        "--host=127.0.0.1",
        "--port=$Port",
        "--user=waitfans_app",
        "--default-character-set=utf8mb4",
        "waitfans"
    )
    $import = Start-Process `
        -FilePath $mysql `
        -ArgumentList $importArgs `
        -RedirectStandardInput $sqlPath `
        -NoNewWindow `
        -Wait `
        -PassThru
    if ($import.ExitCode -ne 0) {
        throw "Failed to import database\waitfans.sql."
    }

    $layoutArgs = @(
        "--protocol=TCP",
        "--host=127.0.0.1",
        "--port=$Port",
        "--user=waitfans_app",
        "--default-character-set=utf8mb4"
    )
    $layoutProcess = Start-Process `
        -FilePath $mysql `
        -ArgumentList $layoutArgs `
        -RedirectStandardInput $layoutSqlPath `
        -NoNewWindow `
        -Wait `
        -PassThru
    if ($layoutProcess.ExitCode -ne 0) {
        throw "Failed to import database\sharded-layout.sql."
    }
}
finally {
    $env:MYSQL_PWD = $previousMysqlPassword
}

[System.IO.File]::WriteAllText($rootPasswordPath, $rootPassword, $utf8)
Set-LocalEnvironment -AppPassword $appPassword

Write-Output "Waitfans local MySQL is ready on 127.0.0.1:$Port."
Write-Output "Application credentials were saved to .env.local; the generated root password remains under .runtime."
