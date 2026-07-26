[CmdletBinding()]
param([string]$EnvFile = ".env.local")

$ErrorActionPreference = "Stop"
$projectRoot = Split-Path -Parent $PSScriptRoot
$runtimeRoot = Join-Path $projectRoot ".runtime"
$envPath = Join-Path $projectRoot $EnvFile

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

if (-not (Test-Path -LiteralPath $envPath)) {
    throw "Local environment file not found. Run scripts\setup-local-mysql.ps1 first."
}
foreach ($line in Get-Content -LiteralPath $envPath) {
    $trimmed = $line.Trim()
    if (-not $trimmed -or $trimmed.StartsWith("#")) {
        continue
    }
    $separator = $trimmed.IndexOf("=")
    if ($separator -lt 1) {
        throw "Invalid environment entry: $line"
    }
    Set-Item `
        -Path "Env:$($trimmed.Substring(0, $separator).Trim())" `
        -Value $trimmed.Substring($separator + 1).Trim()
}

$serverPort = if ($env:WAITFANS_SERVER_PORT) { [int]$env:WAITFANS_SERVER_PORT } else { 7070 }
if (Test-TcpPort -HostName "127.0.0.1" -TargetPort $serverPort) {
    Write-Output "Waitfans backend is already running on port $serverPort."
    exit 0
}

$javaHome = if ($env:WAITFANS_JAVA_HOME) { $env:WAITFANS_JAVA_HOME } else { $env:JAVA_HOME }
$java = if ($javaHome) { Join-Path $javaHome "bin\java.exe" } else { $null }
if (-not $java -or -not (Test-Path -LiteralPath $java)) {
    throw "Set WAITFANS_JAVA_HOME in .env.local to a JDK 8 installation."
}

$jar = Get-ChildItem `
    -LiteralPath (Join-Path $projectRoot "target") `
    -Filter "waitfans-backend-*.jar" `
    -File |
    Where-Object { $_.Name -notlike "*.original" } |
    Sort-Object LastWriteTime -Descending |
    Select-Object -First 1
if (-not $jar) {
    throw "Backend jar not found. Build the project first."
}

$logDir = Join-Path $runtimeRoot "logs"
New-Item -ItemType Directory -Force -Path $logDir | Out-Null
$process = Start-Process `
    -FilePath $java `
    -ArgumentList @("-jar", $jar.FullName) `
    -WorkingDirectory $projectRoot `
    -WindowStyle Hidden `
    -RedirectStandardOutput (Join-Path $logDir "backend.stdout.log") `
    -RedirectStandardError (Join-Path $logDir "backend.stderr.log") `
    -PassThru
[System.IO.File]::WriteAllText(
    (Join-Path $runtimeRoot "backend.pid"),
    [string]$process.Id,
    (New-Object System.Text.UTF8Encoding($false))
)

for ($attempt = 0; $attempt -lt 90; $attempt++) {
    if (Test-TcpPort -HostName "127.0.0.1" -TargetPort $serverPort) {
        Write-Output "Waitfans backend started (PID $($process.Id), port $serverPort)."
        exit 0
    }
    if ($process.HasExited) {
        throw "Waitfans backend exited during startup. Check .runtime\logs\backend.stdout.log."
    }
    Start-Sleep -Seconds 1
}

throw "Waitfans backend did not open port $serverPort within 90 seconds."
