[CmdletBinding()]
param()

$ErrorActionPreference = "Stop"
$projectRoot = Split-Path -Parent $PSScriptRoot
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
    $env:JAVA_HOME,
    "C:\Users\victory\.jdks\dragonwell-1.8.0_492"
) | Where-Object { $_ } | Select-Object -Unique

$javaHome = $javaCandidates |
    Where-Object { Test-Path -LiteralPath (Join-Path $_ "bin\java.exe") } |
    Select-Object -First 1

$mavenCandidates = @(
    $env:WAITFANS_MAVEN_HOME,
    "C:\Users\victory\.m2\wrapper\dists\apache-maven-3.8.7-bin\1ktonn2lleg549uah6ngl1r74r\apache-maven-3.8.7"
) | Where-Object { $_ } | Select-Object -Unique

$mavenHome = $mavenCandidates |
    Where-Object { Test-Path -LiteralPath (Join-Path $_ "bin\mvn.cmd") } |
    Select-Object -First 1

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
        Item = "JDK"
        Available = [bool]$javaHome
        Detail = if ($javaHome) { $javaHome } else { "Set WAITFANS_JAVA_HOME" }
    },
    [pscustomobject]@{
        Item = "Maven"
        Available = [bool]$mavenHome -or (Test-Path -LiteralPath (Join-Path $projectRoot "mvnw.cmd"))
        Detail = if ($mavenHome) { $mavenHome } else { "Maven Wrapper (may download Maven on first use)" }
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
