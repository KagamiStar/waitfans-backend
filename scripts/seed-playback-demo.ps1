[CmdletBinding()]
param(
    [string]$EnvFile = ".env.local",
    [string]$MinioHome = "D:\MinIO",
    [string]$SampleVideo
)

$ErrorActionPreference = "Stop"
$projectRoot = Split-Path -Parent $PSScriptRoot
$workspaceRoot = Split-Path -Parent $projectRoot
$envPath = Join-Path $projectRoot $EnvFile
if (-not $SampleVideo) {
    $SampleVideo = Join-Path $workspaceRoot "waitfans-client\src\assets\video\BadApple.mp4"
}
$SampleVideo = [System.IO.Path]::GetFullPath($SampleVideo)

if (-not (Test-Path -LiteralPath $envPath)) {
    throw "Environment file not found: $envPath"
}
if (-not (Test-Path -LiteralPath $SampleVideo)) {
    throw "Sample video not found: $SampleVideo"
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

$dbUrl = if ($env:WAITFANS_DB_URL) {
    $env:WAITFANS_DB_URL
} else {
    "jdbc:mysql://127.0.0.1:3306/waitfans"
}
if ($dbUrl -notmatch '^jdbc:mysql://(?<host>[^:/?]+)(:(?<port>\d+))?/(?<database>[A-Za-z0-9_]+)') {
    throw "Unsupported WAITFANS_DB_URL: $dbUrl"
}
$dbHost = $Matches.host
$dbPort = if ($Matches.port) { [int]$Matches.port } else { 3306 }
$dbName = $Matches.database
if ($dbHost -notin @("127.0.0.1", "localhost", "::1")) {
    throw "Playback demo seeding is restricted to localhost."
}

$mysqlCommand = Get-Command mysql -ErrorAction SilentlyContinue
$mysql = if ($mysqlCommand) {
    $mysqlCommand.Source
} else {
    "C:\Program Files\MySQL\MySQL Server 8.0\bin\mysql.exe"
}
$mc = Join-Path $MinioHome "mc.exe"
if (-not (Test-Path -LiteralPath $mysql)) {
    throw "MySQL client not found."
}
if (-not (Test-Path -LiteralPath $mc)) {
    throw "MinIO client not found: $mc"
}
$mcConfigDir = Join-Path $projectRoot ".runtime\mc"
New-Item -ItemType Directory -Path $mcConfigDir -Force | Out-Null

$endpoint = if ($env:WAITFANS_OSS_ENDPOINT) {
    $env:WAITFANS_OSS_ENDPOINT
} else {
    "http://127.0.0.1:9000"
}
$bucket = if ($env:WAITFANS_OSS_BUCKET) { $env:WAITFANS_OSS_BUCKET } else { "waitfans-local" }
$bucketUrl = if ($env:WAITFANS_OSS_BUCKET_URL) {
    $env:WAITFANS_OSS_BUCKET_URL.TrimEnd("/") + "/"
} else {
    "$endpoint/$bucket/"
}
$accessKey = if ($env:WAITFANS_OSS_KEY_ID) { $env:WAITFANS_OSS_KEY_ID } else { "local-development" }
$secretKey = if ($env:WAITFANS_OSS_KEY_SECRET) { $env:WAITFANS_OSS_KEY_SECRET } else { "local-development" }
$objectName = "demo/playback-verification.mp4"
$objectUrl = $bucketUrl + $objectName

& $mc --config-dir $mcConfigDir alias set waitfans-demo $endpoint $accessKey $secretKey | Out-Null
if ($LASTEXITCODE -ne 0) {
    throw "Unable to connect to MinIO."
}
& $mc --config-dir $mcConfigDir mb --ignore-existing "waitfans-demo/$bucket" | Out-Null
& $mc --config-dir $mcConfigDir cp $SampleVideo "waitfans-demo/$bucket/$objectName" | Out-Null
if ($LASTEXITCODE -ne 0) {
    throw "Unable to upload the playback demo video."
}

$demoPasswordHash = '$2a$10$7EqJtq98hPqEX7fNZaFWoO5z8h5A0.ZNq5Y6f8bVv9QxQ7Qp2W2eK'
$seedSql = @"
START TRANSACTION;

INSERT INTO waitfans.user
    (username, password, nickname, gender, description, exp, coin, vip, state, role, auth, create_date)
VALUES
    ('waitfans_playback_demo', '$demoPasswordHash', 'Waitfans Playback Demo', 2,
     'Local playback verification account', 0, 0, 0, 0, 0, 0, NOW())
ON DUPLICATE KEY UPDATE nickname = VALUES(nickname), description = VALUES(description);

SET @demo_uid = (
    SELECT uid FROM waitfans.user WHERE username = 'waitfans_playback_demo' LIMIT 1
);
DELETE locator
FROM waitfans.video_locator AS locator
LEFT JOIN waitfans_video_music.video AS music_video ON music_video.vid = locator.vid
WHERE locator.uid = @demo_uid
  AND locator.mc_id = 'music'
  AND music_video.vid IS NULL;

SET @demo_vid = (
    SELECT vid FROM waitfans_video_music.video
    WHERE video_url = '$objectUrl'
    LIMIT 1
);
INSERT INTO waitfans.video_locator (mc_id, uid, status, upload_date)
SELECT 'music', @demo_uid, 1, NOW()
WHERE @demo_vid IS NULL;
SET @demo_vid = COALESCE(@demo_vid, LAST_INSERT_ID());

INSERT INTO waitfans_video_music.video
    (vid, uid, title, type, auth, duration, mc_id, sc_id, tags, descr,
     cover_url, video_url, status, upload_date, delete_date)
VALUES
    (@demo_vid, @demo_uid, 'Waitfans Local Playback Verification', 1, 0, 219,
     'music', 'other', 'playback,MinIO,Range',
     'Playback integration verification',
     '/assets/bilibili-home/25730189dbdc345f.avif',
     '$objectUrl', 1, NOW(), NULL)
ON DUPLICATE KEY UPDATE
    title = VALUES(title),
    cover_url = VALUES(cover_url),
    video_url = VALUES(video_url),
    status = 1,
    delete_date = NULL;

INSERT INTO waitfans_video_music.video_stats
    (vid, play, danmu, good, bad, coin, collect, share, comment)
VALUES (@demo_vid, 0, 0, 0, 0, 0, 0, 0, 0)
ON DUPLICATE KEY UPDATE vid = VALUES(vid);

COMMIT;
SELECT @demo_vid;
"@

$dbUser = if ($env:WAITFANS_DB_USERNAME) { $env:WAITFANS_DB_USERNAME } else { "root" }
$previousMysqlPassword = $env:MYSQL_PWD
$previousOutputEncoding = $OutputEncoding
$env:MYSQL_PWD = $env:WAITFANS_DB_PASSWORD
$OutputEncoding = [System.Text.UTF8Encoding]::new($false)
try {
    $videoId = $seedSql | & $mysql `
        --protocol=TCP `
        "--host=$dbHost" `
        "--port=$dbPort" `
        "--user=$dbUser" `
        "--database=$dbName" `
        --default-character-set=utf8mb4 `
        --batch `
        --skip-column-names
    if ($LASTEXITCODE -ne 0) {
        throw "Unable to seed the playback demo database rows."
    }
}
finally {
    $env:MYSQL_PWD = $previousMysqlPassword
    $OutputEncoding = $previousOutputEncoding
}

Write-Output "Playback demo ready: video ID $videoId"
Write-Output "Restart the backend if it was already running, then open http://127.0.0.1:8787/video/$videoId"
