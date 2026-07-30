[CmdletBinding()]
param(
    [string]$EnvFile = ".env.local",
    [switch]$Force
)

$ErrorActionPreference = "Stop"
$projectRoot = Split-Path -Parent $PSScriptRoot
$envPath = Join-Path $projectRoot $EnvFile
$sqlPath = Join-Path $projectRoot "database\waitfans.sql"
$layoutSqlPath = Join-Path $projectRoot "database\sharded-layout.sql"
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
$managedDatabaseList = ($managedDatabases | ForEach-Object { "'$_'" }) -join ","

if (Test-Path -LiteralPath $envPath) {
    foreach ($line in Get-Content -LiteralPath $envPath) {
        $trimmed = $line.Trim()
        if (-not $trimmed -or $trimmed.StartsWith("#")) {
            continue
        }

        $separator = $trimmed.IndexOf("=")
        if ($separator -lt 1) {
            throw "Invalid environment entry: $line"
        }

        $name = $trimmed.Substring(0, $separator).Trim()
        $value = $trimmed.Substring($separator + 1).Trim()
        Set-Item -Path "Env:$name" -Value $value
    }
}

$dbUrl = $env:WAITFANS_DB_URL
if (-not $dbUrl) {
    $dbUrl = "jdbc:mysql://127.0.0.1:3306/waitfans"
}
if ($dbUrl -notmatch '^jdbc:mysql://(?<host>[^:/?]+)(:(?<port>\d+))?/(?<database>[A-Za-z0-9_]+)') {
    throw "Unsupported WAITFANS_DB_URL: $dbUrl"
}

$dbHost = $Matches.host
$dbPort = if ($Matches.port) { [int]$Matches.port } else { 3306 }
$database = $Matches.database
if ($dbHost -notin @("127.0.0.1", "localhost", "::1")) {
    throw "Database initialization is restricted to localhost. Import remote databases manually."
}
if ($database -ne "waitfans") {
    throw "Refusing to initialize unexpected database '$database'."
}

$mysqlCommand = Get-Command mysql -ErrorAction SilentlyContinue
if ($mysqlCommand) {
    $mysql = $mysqlCommand.Source
}
else {
    $mysql = "C:\Program Files\MySQL\MySQL Server 8.0\bin\mysql.exe"
}
if (-not (Test-Path -LiteralPath $mysql)) {
    throw "MySQL client not found. Add mysql.exe to PATH or update this script."
}
if (-not (Test-Path -LiteralPath $sqlPath)) {
    throw "SQL file not found: $sqlPath"
}
if (-not (Test-Path -LiteralPath $layoutSqlPath)) {
    throw "SQL file not found: $layoutSqlPath"
}

$dbUser = if ($env:WAITFANS_DB_USERNAME) { $env:WAITFANS_DB_USERNAME } else { "root" }
$commonArgs = @(
    "--protocol=TCP",
    "--host=$dbHost",
    "--port=$dbPort",
    "--user=$dbUser",
    "--batch",
    "--skip-column-names"
)

$previousMysqlPassword = $env:MYSQL_PWD
$env:MYSQL_PWD = $env:WAITFANS_DB_PASSWORD
try {
    $existing = & $mysql @commonArgs "--execute=SELECT SCHEMA_NAME FROM INFORMATION_SCHEMA.SCHEMATA WHERE SCHEMA_NAME IN ($managedDatabaseList) LIMIT 1;"
    if ($LASTEXITCODE -ne 0) {
        throw "Unable to connect to MySQL. Check WAITFANS_DB_USERNAME and WAITFANS_DB_PASSWORD in .env.local."
    }
    if ($existing -and -not $Force) {
        throw "Waitfans databases already exist. Re-run with -Force only if replacing all managed databases is intended."
    }
    if ($existing -and $Force) {
        $dropSql = ($managedDatabases | ForEach-Object {
            "DROP DATABASE IF EXISTS ``$_``;"
        }) -join " "
        & $mysql @commonArgs "--execute=$dropSql"
        if ($LASTEXITCODE -ne 0) {
            throw "Failed to replace the managed Waitfans databases."
        }
        $existing = $null
    }
    if (-not $existing) {
        & $mysql @commonArgs "--execute=CREATE DATABASE waitfans CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;"
        if ($LASTEXITCODE -ne 0) {
            throw "Failed to create database 'waitfans'."
        }
    }

    $importArgs = $commonArgs + @("waitfans")
    $process = Start-Process `
        -FilePath $mysql `
        -ArgumentList $importArgs `
        -RedirectStandardInput $sqlPath `
        -NoNewWindow `
        -Wait `
        -PassThru
    if ($process.ExitCode -ne 0) {
        throw "Failed to import database\waitfans.sql (exit code $($process.ExitCode))."
    }

    $layoutProcess = Start-Process `
        -FilePath $mysql `
        -ArgumentList $commonArgs `
        -RedirectStandardInput $layoutSqlPath `
        -NoNewWindow `
        -Wait `
        -PassThru
    if ($layoutProcess.ExitCode -ne 0) {
        throw "Failed to import database\sharded-layout.sql (exit code $($layoutProcess.ExitCode))."
    }

    Write-Output "Waitfans core, carousel and partitioned video databases initialized successfully."
}
finally {
    $env:MYSQL_PWD = $previousMysqlPassword
}
