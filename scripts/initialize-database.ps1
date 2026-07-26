[CmdletBinding()]
param(
    [string]$EnvFile = ".env.local",
    [switch]$Force
)

$ErrorActionPreference = "Stop"
$projectRoot = Split-Path -Parent $PSScriptRoot
$envPath = Join-Path $projectRoot $EnvFile
$sqlPath = Join-Path $projectRoot "database\waitfans.sql"

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
    $existing = & $mysql @commonArgs "--execute=SELECT SCHEMA_NAME FROM INFORMATION_SCHEMA.SCHEMATA WHERE SCHEMA_NAME = 'waitfans';"
    if ($LASTEXITCODE -ne 0) {
        throw "Unable to connect to MySQL. Check WAITFANS_DB_USERNAME and WAITFANS_DB_PASSWORD in .env.local."
    }
    if ($existing -and -not $Force) {
        throw "Database 'waitfans' already exists. Re-run with -Force only if replacing its tables is intended."
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

    Write-Output "Database 'waitfans' initialized successfully."
}
finally {
    $env:MYSQL_PWD = $previousMysqlPassword
}
