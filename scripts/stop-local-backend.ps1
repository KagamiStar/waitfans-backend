[CmdletBinding()]
param()

$ErrorActionPreference = "Stop"
$projectRoot = Split-Path -Parent $PSScriptRoot
$runtimeRoot = Join-Path $projectRoot ".runtime"
$pidPath = Join-Path $runtimeRoot "backend.pid"
$envPath = Join-Path $projectRoot ".env.local"

if (-not (Test-Path -LiteralPath $pidPath)) {
    Write-Output "No Waitfans backend PID file was found."
    exit 0
}

$processId = [int](Get-Content -Raw -LiteralPath $pidPath).Trim()
$backendProcess = Get-Process -Id $processId -ErrorAction SilentlyContinue
if (-not $backendProcess) {
    Remove-Item -LiteralPath $pidPath
    Write-Output "Waitfans backend is not running; stale PID file removed."
    exit 0
}

$expectedJava = $null
if (Test-Path -LiteralPath $envPath) {
    foreach ($line in Get-Content -LiteralPath $envPath) {
        if ($line -match '^\s*WAITFANS_JAVA_HOME\s*=\s*(.+?)\s*$') {
            $expectedJava = Join-Path $matches[1] "bin\java.exe"
            break
        }
    }
}
if (-not $expectedJava -or
    $backendProcess.ProcessName -ne "java" -or
    $backendProcess.Path -ne $expectedJava) {
    throw "PID $processId is not the Waitfans backend; refusing to stop it."
}

Stop-Process -Id $processId
Remove-Item -LiteralPath $pidPath
Write-Output "Waitfans backend stopped (PID $processId)."
