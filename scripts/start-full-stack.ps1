[CmdletBinding()]
param(
    [string]$RedisWslDistribution,
    [string]$MinioHome,
    [switch]$Bootstrap,
    [switch]$Rebuild,
    [switch]$SkipMinio,
    [switch]$OpenBrowser,
    [switch]$ValidateOnly,
    [switch]$Stop
)

$ErrorActionPreference = "Stop"
Set-StrictMode -Version Latest

$backendRoot = Split-Path -Parent $PSScriptRoot
$workspaceRoot = Split-Path -Parent $backendRoot
$clientRoot = Join-Path $workspaceRoot "waitfans-client"
$adminRoot = Join-Path $workspaceRoot "waitfans-admin"
$runtimeRoot = Join-Path $backendRoot ".runtime"
$stackRuntime = Join-Path $runtimeRoot "stack"
$logRoot = Join-Path $runtimeRoot "logs"
$envPath = Join-Path $backendRoot ".env.local"
$envExamplePath = Join-Path $backendRoot ".env.example"

function Write-Stage {
    param([string]$Message)
    Write-Host ""
    Write-Host "==> $Message" -ForegroundColor Cyan
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
        return $task.Wait($TimeoutMilliseconds) -and $client.Connected
    }
    catch {
        return $false
    }
    finally {
        $client.Dispose()
    }
}

function Wait-TcpPort {
    param(
        [string]$Name,
        [string]$HostName,
        [int]$Port,
        [int]$Attempts = 60
    )

    for ($attempt = 0; $attempt -lt $Attempts; $attempt++) {
        if (Test-TcpPort -HostName $HostName -Port $Port) {
            return
        }
        Start-Sleep -Seconds 1
    }
    throw "$Name did not open ${HostName}:$Port within $Attempts seconds."
}

function Import-LocalEnvironment {
    if (-not (Test-Path -LiteralPath $envPath)) {
        throw "Missing $envPath. Run this script with -Bootstrap once."
    }

    foreach ($line in Get-Content -LiteralPath $envPath) {
        $trimmed = $line.Trim()
        if (-not $trimmed -or $trimmed.StartsWith("#")) {
            continue
        }

        $separator = $trimmed.IndexOf("=")
        if ($separator -lt 1) {
            throw "Invalid environment entry in .env.local: $line"
        }

        $name = $trimmed.Substring(0, $separator).Trim()
        $value = $trimmed.Substring($separator + 1).Trim()
        Set-Item -Path "Env:$name" -Value $value
    }
}

function Save-ManagedProcess {
    param(
        [string]$Name,
        [System.Diagnostics.Process]$Process,
        [int]$Port
    )

    $Process.Refresh()
    $record = [ordered]@{
        pid = $Process.Id
        processName = $Process.ProcessName
        path = [string]$Process.Path
        startTimeUtcTicks = $Process.StartTime.ToUniversalTime().Ticks
        port = $Port
    }
    $recordPath = Join-Path $stackRuntime "$Name.json"
    $record |
        ConvertTo-Json |
        Set-Content -LiteralPath $recordPath -Encoding UTF8
}

function Stop-ManagedProcess {
    param([string]$Name)

    $recordPath = Join-Path $stackRuntime "$Name.json"
    if (-not (Test-Path -LiteralPath $recordPath)) {
        Write-Output "$Name was not started by this script."
        return
    }

    $record = Get-Content -Raw -LiteralPath $recordPath | ConvertFrom-Json
    $process = Get-Process -Id ([int]$record.pid) -ErrorAction SilentlyContinue
    if (-not $process) {
        Remove-Item -LiteralPath $recordPath
        Write-Output "$Name was already stopped; removed stale process record."
        return
    }

    $currentPath = [string]$process.Path
    $currentStartTicks = $process.StartTime.ToUniversalTime().Ticks
    if ($process.ProcessName -ne [string]$record.processName -or
        ($record.path -and $currentPath -ne [string]$record.path) -or
        $currentStartTicks -ne [long]$record.startTimeUtcTicks) {
        throw "PID $($record.pid) no longer matches the managed $Name process; refusing to stop it."
    }

    Stop-Process -Id $process.Id
    Remove-Item -LiteralPath $recordPath
    Write-Output "$Name stopped (PID $($process.Id))."
}

function Assert-Workspace {
    foreach ($path in @(
        (Join-Path $backendRoot "pom.xml"),
        (Join-Path $clientRoot "package.json"),
        (Join-Path $adminRoot "package.json")
    )) {
        if (-not (Test-Path -LiteralPath $path)) {
            throw "Required project file not found: $path"
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
    throw "Unable to determine the Java version from: $firstLine"
}

function Assert-Java {
    $javaHome = if ($env:WAITFANS_JAVA_HOME) {
        $env:WAITFANS_JAVA_HOME
    } else {
        $env:JAVA_HOME
    }
    if (-not $javaHome) {
        throw "JDK 8 was not found. Set JAVA_HOME or WAITFANS_JAVA_HOME in .env.local."
    }

    $java = Join-Path $javaHome "bin\java.exe"
    if (-not (Test-Path -LiteralPath $java)) {
        throw "JDK executable not found: $java"
    }
    $javaMajor = Get-JavaMajorVersion -JavaExecutable $java
    if ($javaMajor -ne 8) {
        throw "Waitfans backend requires JDK 8, but Java $javaMajor was found at $javaHome."
    }

    $env:JAVA_HOME = $javaHome
    $env:Path = "$(Join-Path $javaHome 'bin');$env:Path"
}

function Assert-Node {
    $node = Get-Command "node.exe" -ErrorAction SilentlyContinue
    $npm = Get-Command "npm.cmd" -ErrorAction SilentlyContinue
    if (-not $node -or -not $npm) {
        throw "Node.js and npm were not found. Install Node.js 20 or newer with npm 10 or newer."
    }

    $nodeVersion = [string](& $node.Source --version)
    $npmVersion = [string](& $npm.Source --version)
    if ($nodeVersion.Trim() -notmatch '^v(\d+)' -or [int]$matches[1] -lt 20) {
        throw "Waitfans frontends require Node.js 20 or newer; found $($nodeVersion.Trim())."
    }
    if ($npmVersion.Trim() -notmatch '^(\d+)' -or [int]$matches[1] -lt 10) {
        throw "Waitfans frontends require npm 10 or newer; found $($npmVersion.Trim())."
    }
}

function Resolve-RedisWslDistribution {
    $wsl = Get-Command "wsl.exe" -ErrorAction SilentlyContinue
    if (-not $wsl) {
        throw "WSL is not installed. Install WSL Ubuntu and redis-server first."
    }

    $distributionOutput = & wsl.exe --list --quiet 2>$null
    if ($LASTEXITCODE -ne 0) {
        throw "WSL is installed but its service is unavailable. Start WSL or run this terminal with sufficient permission."
    }
    $distributions = @(
        $distributionOutput |
            ForEach-Object { ([string]$_ -replace [char]0, "").Trim() } |
            Where-Object { $_ }
    )
    if ($distributions.Count -eq 0) {
        throw "No WSL distributions were found."
    }
    if ($RedisWslDistribution) {
        if ($distributions -notcontains $RedisWslDistribution) {
            throw "WSL distribution '$RedisWslDistribution' was not found. Available: $($distributions -join ', ')"
        }
        return $RedisWslDistribution
    }

    foreach ($preferred in @("Ubuntu-D", "Ubuntu")) {
        if ($distributions -contains $preferred) {
            return $preferred
        }
    }
    $ubuntu = $distributions |
        Where-Object { $_ -like "Ubuntu*" } |
        Select-Object -First 1
    if ($ubuntu) {
        return $ubuntu
    }
    return $distributions[0]
}

function Assert-RedisWsl {
    & wsl.exe -d $RedisWslDistribution -u root -- bash -lc "command -v redis-server >/dev/null 2>&1"
    if ($LASTEXITCODE -eq 0) {
        return
    }
    if (-not $Bootstrap) {
        throw "redis-server is missing in '$RedisWslDistribution'. Run this script once with -Bootstrap."
    }

    Write-Stage "Installing Redis in WSL '$RedisWslDistribution'"
    & wsl.exe -d $RedisWslDistribution -u root -- bash -lc `
        "apt-get update && DEBIAN_FRONTEND=noninteractive apt-get install -y redis-server"
    if ($LASTEXITCODE -ne 0) {
        throw "Failed to install redis-server in '$RedisWslDistribution'."
    }
}

function Ensure-LocalConfiguration {
    if (-not (Test-Path -LiteralPath $envPath)) {
        if (-not $Bootstrap) {
            throw "Missing .env.local. Run this script once with -Bootstrap."
        }
        Copy-Item -LiteralPath $envExamplePath -Destination $envPath
        Write-Output "Created ignored local configuration: $envPath"
    }

    Import-LocalEnvironment
    Assert-Java

    $mysqlConfig = Join-Path $runtimeRoot "mysql\my.ini"
    if (-not (Test-Path -LiteralPath $mysqlConfig)) {
        if (-not $Bootstrap) {
            throw "Local MySQL is not initialized. Run this script once with -Bootstrap."
        }
        Write-Stage "Initializing isolated MySQL on port 3307"
        & (Join-Path $PSScriptRoot "setup-local-mysql.ps1")
        if ($LASTEXITCODE -ne 0) {
            throw "MySQL initialization failed."
        }
        Import-LocalEnvironment
    }
}

function Ensure-ElasticsearchRuntime {
    $elasticsearchRoot = Join-Path $runtimeRoot "elasticsearch"
    $existing = Get-ChildItem `
        -LiteralPath $elasticsearchRoot `
        -Directory `
        -Filter "elasticsearch-*" `
        -ErrorAction SilentlyContinue |
        Where-Object {
            Test-Path -LiteralPath (Join-Path $_.FullName "bin\elasticsearch.bat")
        } |
        Sort-Object Name -Descending |
        Select-Object -First 1
    if ($existing) {
        return
    }
    if (-not $Bootstrap) {
        throw "Elasticsearch is missing under $elasticsearchRoot. Run this script once with -Bootstrap."
    }

    Write-Stage "Downloading Elasticsearch 7.17.16"
    $version = "7.17.16"
    $archive = Join-Path $runtimeRoot "downloads\elasticsearch-$version.zip"
    New-Item -ItemType Directory -Force -Path (Split-Path -Parent $archive) | Out-Null
    New-Item -ItemType Directory -Force -Path $elasticsearchRoot | Out-Null
    if (-not (Test-Path -LiteralPath $archive)) {
        Invoke-WebRequest `
            -UseBasicParsing `
            -Uri "https://artifacts.elastic.co/downloads/elasticsearch/elasticsearch-$version-windows-x86_64.zip" `
            -OutFile $archive
    }
    Expand-Archive -LiteralPath $archive -DestinationPath $elasticsearchRoot -Force
}

function Resolve-MinioRuntime {
    if ($MinioHome) {
        return [System.IO.Path]::GetFullPath($MinioHome)
    }
    if (Test-Path -LiteralPath "D:\MinIO\minio.exe") {
        return "D:\MinIO"
    }
    return (Join-Path $runtimeRoot "minio")
}

function Ensure-MinioRuntime {
    param([string]$MinioRuntimePath)

    $minio = Join-Path $MinioRuntimePath "minio.exe"
    $mc = Join-Path $MinioRuntimePath "mc.exe"
    if ((Test-Path -LiteralPath $minio) -and (Test-Path -LiteralPath $mc)) {
        return
    }
    if (-not $Bootstrap) {
        throw "MinIO or mc is missing under $MinioRuntimePath. Run with -Bootstrap, specify -MinioHome, or use -SkipMinio."
    }

    Write-Stage "Downloading MinIO server and client"
    New-Item -ItemType Directory -Force -Path $MinioRuntimePath | Out-Null
    if (-not (Test-Path -LiteralPath $minio)) {
        Invoke-WebRequest `
            -UseBasicParsing `
            -Uri "https://dl.min.io/server/minio/release/windows-amd64/minio.exe" `
            -OutFile $minio
    }
    if (-not (Test-Path -LiteralPath $mc)) {
        Invoke-WebRequest `
            -UseBasicParsing `
            -Uri "https://dl.min.io/client/mc/release/windows-amd64/mc.exe" `
            -OutFile $mc
    }
}

function Start-Minio {
    param([string]$MinioRuntimePath)

    $endpointText = if ($env:WAITFANS_OSS_ENDPOINT) {
        $env:WAITFANS_OSS_ENDPOINT
    } else {
        "http://127.0.0.1:9000"
    }
    $endpoint = [Uri]$endpointText
    if ($endpoint.Host -notin @("127.0.0.1", "localhost")) {
        throw "Refusing to manage non-local MinIO endpoint: $endpointText"
    }

    $port = $endpoint.Port
    $bucket = if ($env:WAITFANS_OSS_BUCKET) { $env:WAITFANS_OSS_BUCKET } else { "waitfans-local" }
    $accessKey = if ($env:WAITFANS_OSS_KEY_ID) { $env:WAITFANS_OSS_KEY_ID } else { "local-development" }
    $secretKey = if ($env:WAITFANS_OSS_KEY_SECRET) { $env:WAITFANS_OSS_KEY_SECRET } else { "local-development" }
    $minio = Join-Path $MinioRuntimePath "minio.exe"
    $mc = Join-Path $MinioRuntimePath "mc.exe"
    $data = Join-Path $MinioRuntimePath "data"
    $mcConfig = Join-Path $runtimeRoot "minio-mc"
    New-Item -ItemType Directory -Force -Path $data, $mcConfig, $logRoot, $stackRuntime | Out-Null

    if (-not (Test-TcpPort -HostName "127.0.0.1" -Port $port)) {
        Write-Stage "Starting MinIO on port $port"
        $previousUser = $env:MINIO_ROOT_USER
        $previousPassword = $env:MINIO_ROOT_PASSWORD
        $env:MINIO_ROOT_USER = $accessKey
        $env:MINIO_ROOT_PASSWORD = $secretKey
        try {
            $process = Start-Process `
                -FilePath $minio `
                -ArgumentList @(
                    "server",
                    "`"$data`"",
                    "--address",
                    "127.0.0.1:$port",
                    "--console-address",
                    "127.0.0.1:9001"
                ) `
                -WorkingDirectory $MinioRuntimePath `
                -WindowStyle Hidden `
                -RedirectStandardOutput (Join-Path $logRoot "minio.stdout.log") `
                -RedirectStandardError (Join-Path $logRoot "minio.stderr.log") `
                -PassThru
            Save-ManagedProcess -Name "minio" -Process $process -Port $port
        }
        finally {
            $env:MINIO_ROOT_USER = $previousUser
            $env:MINIO_ROOT_PASSWORD = $previousPassword
        }
        Wait-TcpPort -Name "MinIO" -HostName "127.0.0.1" -Port $port
    } else {
        Write-Output "MinIO is already running on port $port."
    }

    Write-Stage "Ensuring MinIO bucket '$bucket'"
    & $mc --config-dir $mcConfig alias set waitfans $endpointText $accessKey $secretKey | Out-Null
    if ($LASTEXITCODE -ne 0) {
        throw "Failed to configure the local MinIO alias."
    }
    & $mc --config-dir $mcConfig mb --ignore-existing "waitfans/$bucket" | Out-Null
    if ($LASTEXITCODE -ne 0) {
        throw "Failed to create MinIO bucket '$bucket'."
    }
    & $mc --config-dir $mcConfig anonymous set public "waitfans/$bucket" | Out-Null
    if ($LASTEXITCODE -ne 0) {
        throw "Failed to configure public read access for MinIO bucket '$bucket'."
    }
}

function Get-BackendJar {
    return Get-ChildItem `
        -LiteralPath (Join-Path $backendRoot "target") `
        -Filter "waitfans-backend-*.jar" `
        -File `
        -ErrorAction SilentlyContinue |
        Where-Object { $_.Name -notlike "*.original" } |
        Sort-Object LastWriteTimeUtc -Descending |
        Select-Object -First 1
}

function Test-BackendBuildRequired {
    $jar = Get-BackendJar
    if ($Rebuild -or -not $jar) {
        return $true
    }

    $sourceFiles = @(
        Get-Item -LiteralPath (Join-Path $backendRoot "pom.xml")
        Get-ChildItem -LiteralPath (Join-Path $backendRoot "src\main") -File -Recurse
    )
    $latestSource = $sourceFiles |
        Sort-Object LastWriteTimeUtc -Descending |
        Select-Object -First 1
    return $latestSource.LastWriteTimeUtc -gt $jar.LastWriteTimeUtc
}

function Build-BackendIfNeeded {
    if (-not (Test-BackendBuildRequired)) {
        Write-Output "Backend jar is up to date."
        return
    }

    if (Test-TcpPort -HostName "127.0.0.1" -Port 7070) {
        $backendPidPath = Join-Path $runtimeRoot "backend.pid"
        if (-not (Test-Path -LiteralPath $backendPidPath)) {
            throw "Port 7070 is active but no Waitfans backend PID file exists; refusing to replace that process."
        }
        & (Join-Path $PSScriptRoot "stop-local-backend.ps1")
    }

    Write-Stage "Building backend"
    Push-Location $backendRoot
    try {
        & (Join-Path $backendRoot "mvnw.cmd") -DskipTests package
        if ($LASTEXITCODE -ne 0) {
            throw "Backend Maven build failed."
        }
    }
    finally {
        Pop-Location
    }
}

function Ensure-FrontendDependencies {
    param(
        [string]$Name,
        [string]$ProjectRoot
    )

    $vite = Join-Path $ProjectRoot "node_modules\vite\bin\vite.js"
    if (Test-Path -LiteralPath $vite) {
        return
    }

    Write-Stage "Installing $Name dependencies"
    $npm = Get-Command "npm.cmd" -ErrorAction SilentlyContinue
    if (-not $npm) {
        throw "npm.cmd was not found. Install Node.js 20 or newer."
    }
    Push-Location $ProjectRoot
    try {
        & $npm.Source ci
        if ($LASTEXITCODE -ne 0) {
            throw "$Name npm ci failed."
        }
    }
    finally {
        Pop-Location
    }
}

function Test-FrontendIdentity {
    param(
        [int]$Port,
        [string]$ExpectedTitle
    )

    try {
        $response = Invoke-WebRequest `
            -UseBasicParsing `
            -Uri "http://127.0.0.1:$Port/" `
            -TimeoutSec 5
        return $response.StatusCode -eq 200 -and $response.Content.Contains("<title>$ExpectedTitle</title>")
    }
    catch {
        return $false
    }
}

function Start-Frontend {
    param(
        [string]$Name,
        [string]$ProjectRoot,
        [int]$Port,
        [string]$ExpectedTitle,
        [string]$RecordName
    )

    if (Test-TcpPort -HostName "127.0.0.1" -Port $Port) {
        if (-not (Test-FrontendIdentity -Port $Port -ExpectedTitle $ExpectedTitle)) {
            throw "Port $Port is occupied by a different service."
        }
        Write-Output "$Name is already running on port $Port."
        return
    }

    $node = Get-Command "node.exe" -ErrorAction SilentlyContinue
    if (-not $node) {
        throw "node.exe was not found. Install Node.js 20 or newer."
    }
    $vite = Join-Path $ProjectRoot "node_modules\vite\bin\vite.js"
    if (-not (Test-Path -LiteralPath $vite)) {
        throw "Vite entry point was not found after dependency installation: $vite"
    }

    Write-Stage "Starting $Name on port $Port"
    New-Item -ItemType Directory -Force -Path $stackRuntime, $logRoot | Out-Null
    $process = Start-Process `
        -FilePath $node.Source `
        -ArgumentList @(
            "`"$vite`"",
            "--host",
            "127.0.0.1",
            "--port",
            [string]$Port,
            "--strictPort"
        ) `
        -WorkingDirectory $ProjectRoot `
        -WindowStyle Hidden `
        -RedirectStandardOutput (Join-Path $logRoot "$RecordName.stdout.log") `
        -RedirectStandardError (Join-Path $logRoot "$RecordName.stderr.log") `
        -PassThru
    Save-ManagedProcess -Name $RecordName -Process $process -Port $Port
    Wait-TcpPort -Name $Name -HostName "127.0.0.1" -Port $Port
    if (-not (Test-FrontendIdentity -Port $Port -ExpectedTitle $ExpectedTitle)) {
        throw "$Name opened port $Port but did not return the expected page."
    }
}

function Test-Api {
    param([string]$Url)

    $response = Invoke-RestMethod -Uri $Url -TimeoutSec 10
    if ($response.code -ne 200) {
        throw "Health check failed: $Url returned business code $($response.code)."
    }
    return @($response.data).Count
}

function Stop-FullStack {
    Write-Stage "Stopping frontends and MinIO managed by this script"
    Stop-ManagedProcess -Name "client"
    Stop-ManagedProcess -Name "admin"
    Stop-ManagedProcess -Name "minio"

    Write-Stage "Stopping backend, Elasticsearch, MySQL and Redis"
    & (Join-Path $PSScriptRoot "stop-local-services.ps1") `
        -RedisWslDistribution $RedisWslDistribution
    Write-Host ""
    Write-Host "Waitfans full stack stopped." -ForegroundColor Green
}

New-Item -ItemType Directory -Force -Path $runtimeRoot, $stackRuntime, $logRoot | Out-Null
Assert-Workspace

if ($ValidateOnly) {
    Import-LocalEnvironment
    Assert-Java
    Assert-Node
    Write-Host "Waitfans toolchain configuration is valid." -ForegroundColor Green
    exit 0
}

$RedisWslDistribution = Resolve-RedisWslDistribution
Write-Output "Using WSL distribution: $RedisWslDistribution"

if ($Stop) {
    Stop-FullStack
    exit 0
}

try {
    Write-Stage "Preparing local configuration"
    Ensure-LocalConfiguration
    Assert-Node
    Assert-RedisWsl
    Ensure-ElasticsearchRuntime

    $resolvedMinioHome = Resolve-MinioRuntime
    if (-not $SkipMinio) {
        Ensure-MinioRuntime -MinioRuntimePath $resolvedMinioHome
    }

    Write-Stage "Starting MySQL, Redis and Elasticsearch"
    & (Join-Path $PSScriptRoot "start-local-services.ps1") `
        -RedisWslDistribution $RedisWslDistribution

    if (-not $SkipMinio) {
        Start-Minio -MinioRuntimePath $resolvedMinioHome
    } else {
        Write-Warning "MinIO was skipped. Upload, avatar and cover storage features will be unavailable."
    }

    Build-BackendIfNeeded

    Write-Stage "Starting backend"
    & (Join-Path $PSScriptRoot "start-local-backend.ps1")

    Ensure-FrontendDependencies -Name "waitfans-client" -ProjectRoot $clientRoot
    Ensure-FrontendDependencies -Name "waitfans-admin" -ProjectRoot $adminRoot
    Start-Frontend `
        -Name "waitfans-client" `
        -ProjectRoot $clientRoot `
        -Port 8787 `
        -ExpectedTitle "waitfans-client" `
        -RecordName "client"
    Start-Frontend `
        -Name "waitfans-admin" `
        -ProjectRoot $adminRoot `
        -Port 8788 `
        -ExpectedTitle "waitfans-admin" `
        -RecordName "admin"

    Write-Stage "Running health checks"
    $categoryCount = Test-Api -Url "http://127.0.0.1:7070/category/getall"
    $clientCategoryCount = Test-Api -Url "http://127.0.0.1:8787/api/category/getall"
    $adminCategoryCount = Test-Api -Url "http://127.0.0.1:8788/api/category/getall"

    Write-Host ""
    Write-Host "Waitfans full stack is ready." -ForegroundColor Green
    Write-Host "  Backend:  http://127.0.0.1:7070"
    Write-Host "  IM:       ws://127.0.0.1:7071/im"
    Write-Host "  Client:   http://127.0.0.1:8787"
    Write-Host "  Admin:    http://127.0.0.1:8788"
    if (-not $SkipMinio) {
        Write-Host "  MinIO:    http://127.0.0.1:9001"
    }
    Write-Host "  Categories: backend=$categoryCount client=$clientCategoryCount admin=$adminCategoryCount"
    Write-Host "  Logs: $logRoot"
    Write-Host ""
    Write-Host "Stop everything with:"
    Write-Host "  .\start-all.ps1 -Stop"

    if ($OpenBrowser) {
        Start-Process "http://127.0.0.1:8787/"
        Start-Process "http://127.0.0.1:8788/"
    }
}
catch {
    Write-Host ""
    Write-Error "Waitfans startup failed: $($_.Exception.Message)"
    Write-Host "Logs are available under: $logRoot"
    exit 1
}
