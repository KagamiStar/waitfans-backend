[CmdletBinding()]
param()

$ErrorActionPreference = "Stop"
Set-StrictMode -Version Latest

$projectRoot = Split-Path -Parent $PSScriptRoot
$workspaceRoot = Split-Path -Parent $projectRoot
$envPath = Join-Path $projectRoot ".env.local"

if (Test-Path -LiteralPath $envPath) {
    foreach ($line in Get-Content -LiteralPath $envPath) {
        $trimmed = $line.Trim()
        if (-not $trimmed -or $trimmed.StartsWith("#")) {
            continue
        }
        $separator = $trimmed.IndexOf("=")
        if ($separator -gt 0) {
            Set-Item `
                -Path "Env:$($trimmed.Substring(0, $separator).Trim())" `
                -Value $trimmed.Substring($separator + 1).Trim()
        }
    }
}

function Get-JavaMajorVersion {
    param([string]$JavaExecutable)

    $previousErrorActionPreference = $ErrorActionPreference
    try {
        $ErrorActionPreference = "Continue"
        $versionOutput = @(& $JavaExecutable -version 2>&1)
    }
    finally {
        $ErrorActionPreference = $previousErrorActionPreference
    }
    $firstLine = [string]($versionOutput | Select-Object -First 1)
    if ($firstLine -match '"1\.(\d+)\.') {
        return [int]$matches[1]
    }
    if ($firstLine -match '"(\d+)(?:\.|")') {
        return [int]$matches[1]
    }
    return $null
}

function Get-CommandMajorVersion {
    param(
        [System.Management.Automation.CommandInfo]$Command,
        [string[]]$Arguments
    )

    if (-not $Command) {
        return $null
    }
    $versionText = [string](& $Command.Source @Arguments)
    if ($versionText.Trim() -match '^v?(\d+)') {
        return [int]$matches[1]
    }
    return $null
}

function Test-TcpPort {
    param(
        [string]$HostName,
        [int]$Port,
        [int]$TimeoutMilliseconds = 1000
    )

    $client = New-Object System.Net.Sockets.TcpClient
    try {
        $task = $client.ConnectAsync($HostName, $Port)
        if (-not $task.Wait($TimeoutMilliseconds)) {
            return $false
        }
        return $client.Connected
    }
    catch {
        return $false
    }
    finally {
        $client.Dispose()
    }
}

$javaCandidates = @(
    $env:WAITFANS_JAVA_HOME,
    $env:JAVA_HOME
) | Where-Object { $_ } | Select-Object -Unique

$javaHome = $javaCandidates |
    Where-Object { Test-Path -LiteralPath (Join-Path $_ "bin\java.exe") } |
    Select-Object -First 1
$javaExecutable = if ($javaHome) { Join-Path $javaHome "bin\java.exe" } else { $null }
$javaMajor = if ($javaExecutable) { Get-JavaMajorVersion -JavaExecutable $javaExecutable } else { $null }

$mavenWrapper = Join-Path $projectRoot "mvnw.cmd"
$configuredMaven = if ($env:WAITFANS_MAVEN_HOME) {
    Join-Path $env:WAITFANS_MAVEN_HOME "bin\mvn.cmd"
} else {
    $null
}
$mavenAvailable = if ($configuredMaven) {
    Test-Path -LiteralPath $configuredMaven
} else {
    Test-Path -LiteralPath $mavenWrapper
}
$mavenDetail = if ($configuredMaven) {
    $configuredMaven
} else {
    "Maven Wrapper 3.8.7"
}

$nodeCommand = Get-Command "node.exe" -ErrorAction SilentlyContinue
$npmCommand = Get-Command "npm.cmd" -ErrorAction SilentlyContinue
$nodeMajor = Get-CommandMajorVersion -Command $nodeCommand -Arguments @("--version")
$npmMajor = Get-CommandMajorVersion -Command $npmCommand -Arguments @("--version")

$mysqlHost = "127.0.0.1"
$mysqlPort = 3307
if ($env:WAITFANS_DB_URL -match '^jdbc:mysql://([^:/?]+):(\d+)/') {
    $mysqlHost = $matches[1]
    $mysqlPort = [int]$matches[2]
}
$redisHost = if ($env:WAITFANS_REDIS_HOST) { $env:WAITFANS_REDIS_HOST } else { "127.0.0.1" }
$redisPort = if ($env:WAITFANS_REDIS_PORT) { [int]$env:WAITFANS_REDIS_PORT } else { 6379 }
$esHost = if ($env:WAITFANS_ES_HOST) { $env:WAITFANS_ES_HOST } else { "127.0.0.1" }
$esPort = if ($env:WAITFANS_ES_PORT) { [int]$env:WAITFANS_ES_PORT } else { 9200 }

$checks = @(
    [pscustomobject]@{
        Item = "Local config"
        Available = Test-Path -LiteralPath $envPath
        Detail = if (Test-Path -LiteralPath $envPath) { $envPath } else { "Create .env.local from .env.example" }
    },
    [pscustomobject]@{
        Item = "JDK 8"
        Available = $javaMajor -eq 8
        Detail = if ($javaHome) { "Java $javaMajor at $javaHome" } else { "Set WAITFANS_JAVA_HOME or JAVA_HOME" }
    },
    [pscustomobject]@{
        Item = "Maven"
        Available = $mavenAvailable
        Detail = $mavenDetail
    },
    [pscustomobject]@{
        Item = "Node.js 20+"
        Available = $nodeMajor -ge 20
        Detail = if ($nodeCommand) { "Node $nodeMajor at $($nodeCommand.Source)" } else { "Install Node.js 20 or newer" }
    },
    [pscustomobject]@{
        Item = "npm 10+"
        Available = $npmMajor -ge 10
        Detail = if ($npmCommand) { "npm $npmMajor at $($npmCommand.Source)" } else { "Install npm 10 or newer" }
    },
    [pscustomobject]@{
        Item = "Frontend repos"
        Available = (Test-Path -LiteralPath (Join-Path $workspaceRoot "waitfans-client\package.json")) -and
            (Test-Path -LiteralPath (Join-Path $workspaceRoot "waitfans-admin\package.json"))
        Detail = $workspaceRoot
    },
    [pscustomobject]@{
        Item = "MySQL"
        Available = Test-TcpPort -HostName $mysqlHost -Port $mysqlPort
        Detail = "${mysqlHost}:$mysqlPort"
    },
    [pscustomobject]@{
        Item = "Redis"
        Available = Test-TcpPort -HostName $redisHost -Port $redisPort
        Detail = "${redisHost}:$redisPort"
    },
    [pscustomobject]@{
        Item = "Elasticsearch"
        Available = Test-TcpPort -HostName $esHost -Port $esPort
        Detail = "${esHost}:$esPort"
    }
)

$checks | Format-Table -AutoSize

if ($checks.Where({ -not $_.Available }).Count -gt 0) {
    exit 1
}
