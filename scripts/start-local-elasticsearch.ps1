[CmdletBinding()]
param(
    [string]$ElasticsearchHome,
    [int]$Port = 9200
)

$ErrorActionPreference = "Stop"
$projectRoot = Split-Path -Parent $PSScriptRoot
$runtimeRoot = Join-Path $projectRoot ".runtime"

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

if (Test-TcpPort -HostName "127.0.0.1" -TargetPort $Port) {
    Write-Output "Elasticsearch is already running on port $Port."
    exit 0
}

if (-not $ElasticsearchHome) {
    $ElasticsearchHome = Get-ChildItem `
        -LiteralPath (Join-Path $runtimeRoot "elasticsearch") `
        -Directory `
        -Filter "elasticsearch-*" |
        Sort-Object Name -Descending |
        Select-Object -First 1 -ExpandProperty FullName
}
if (-not $ElasticsearchHome) {
    throw "Elasticsearch is not installed under .runtime\elasticsearch."
}

$launcher = Join-Path $ElasticsearchHome "bin\elasticsearch.bat"
if (-not (Test-Path -LiteralPath $launcher)) {
    throw "Elasticsearch launcher was not found: $launcher"
}

$logDir = Join-Path $runtimeRoot "logs"
New-Item -ItemType Directory -Force -Path $logDir | Out-Null
Start-Process `
    -FilePath $launcher `
    -WorkingDirectory $ElasticsearchHome `
    -WindowStyle Hidden `
    -RedirectStandardOutput (Join-Path $logDir "elasticsearch.stdout.log") `
    -RedirectStandardError (Join-Path $logDir "elasticsearch.stderr.log") | Out-Null

for ($attempt = 0; $attempt -lt 90; $attempt++) {
    if (Test-TcpPort -HostName "127.0.0.1" -TargetPort $Port) {
        Write-Output "Elasticsearch started on 127.0.0.1:$Port."
        exit 0
    }
    Start-Sleep -Seconds 1
}

throw "Elasticsearch did not open port $Port. Check .runtime\logs\elasticsearch.stderr.log."
