[CmdletBinding()]
param(
    [string]$EnvFile = ".env.local",
    [switch]$Offline
)

$ErrorActionPreference = "Stop"
$projectRoot = Split-Path -Parent $PSScriptRoot
$envPath = Join-Path $projectRoot $EnvFile

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

$javaHome = $env:WAITFANS_JAVA_HOME
if (-not $javaHome) {
    $javaHome = $env:JAVA_HOME
}
if (-not $javaHome -or -not (Test-Path -LiteralPath (Join-Path $javaHome "bin\java.exe"))) {
    throw "Set WAITFANS_JAVA_HOME in .env.local to a JDK 8 installation."
}

$env:JAVA_HOME = $javaHome
$env:Path = "$(Join-Path $javaHome 'bin');$env:Path"

$mavenArgs = @()
if ($Offline) {
    $mavenArgs += "-o"
}
$mavenArgs += "spring-boot:run"

$mavenCommand = Join-Path $projectRoot "mvnw.cmd"
if ($env:WAITFANS_MAVEN_HOME) {
    $cachedMaven = Join-Path $env:WAITFANS_MAVEN_HOME "bin\mvn.cmd"
    if (-not (Test-Path -LiteralPath $cachedMaven)) {
        throw "WAITFANS_MAVEN_HOME does not contain bin\mvn.cmd: $($env:WAITFANS_MAVEN_HOME)"
    }
    $mavenCommand = $cachedMaven
}

Push-Location $projectRoot
try {
    & $mavenCommand @mavenArgs
    exit $LASTEXITCODE
}
finally {
    Pop-Location
}
