param([int]$Port = 8080, [switch]$BuildOnly, [switch]$SkipBuild)
$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $PSScriptRoot
$envFile = Join-Path $projectRoot 'deploy/.env'
if (-not (Test-Path -LiteralPath $envFile)) { throw 'Run scripts/start-database.ps1 first.' }
foreach ($line in Get-Content -LiteralPath $envFile) {
    if ($line -match '^([A-Z][A-Z0-9_]*)=(.*)$') { [Environment]::SetEnvironmentVariable($Matches[1], $Matches[2], 'Process') }
}
$env:DB_URL = "jdbc:mysql://127.0.0.1:$($env:MYSQL_PORT)/$($env:MYSQL_DATABASE)?useUnicode=true&characterEncoding=UTF-8&serverTimezone=UTC&allowPublicKeyRetrieval=true&useSSL=false"
$env:DB_USER = $env:MYSQL_USER
$env:DB_PASSWORD = $env:MYSQL_PASSWORD
$env:SPRING_PROFILES_ACTIVE = 'local'
$env:PORT = "$Port"
$backendRoot = Join-Path $projectRoot 'backend'
Push-Location $backendRoot
try {
    if (-not $SkipBuild) {
        & .\mvnw.cmd -B package
        if ($LASTEXITCODE -ne 0) { throw 'Backend build or tests failed.' }
    }
    if (-not $BuildOnly) {
        $env:SPRING_PROFILES_ACTIVE = 'mysql'
        $artifact = Join-Path $backendRoot 'target/dormlink-backend-1.0.0.jar'
        if (-not (Test-Path -LiteralPath $artifact)) { throw 'Build the backend before using -SkipBuild.' }
        # Keep target/ free for subsequent Maven builds while Windows holds the running JAR open.
        $runtimeFolder = Join-Path $projectRoot 'output/runtime'
        New-Item -ItemType Directory -Path $runtimeFolder -Force | Out-Null
        $runtimeJar = Join-Path $runtimeFolder ("backend-$Port-$(Get-Date -Format 'yyyyMMddHHmmssfff').jar")
        Copy-Item -LiteralPath $artifact -Destination $runtimeJar
        & java -jar $runtimeJar
        if ($LASTEXITCODE -ne 0) { throw 'Backend process stopped with an error.' }
    }
} finally { Pop-Location }
