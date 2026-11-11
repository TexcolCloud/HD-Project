param(
    [int]$Port = 8080,
    [switch]$BuildOnly,
    [switch]$SkipBuild,
    [ValidateSet('opengauss', 'mysql', 'local')][string]$Database = 'opengauss'
)
$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $PSScriptRoot
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
        $envFile = Join-Path $projectRoot $(if ($Database -eq 'opengauss') { 'deploy/.env.opengauss' } else { 'deploy/.env' })
        if ($Database -ne 'local' -and -not (Test-Path -LiteralPath $envFile)) {
            throw "Missing database environment file: $envFile. See deploy/opengauss.env.example."
        }
        if (Test-Path -LiteralPath $envFile) {
            foreach ($line in Get-Content -LiteralPath $envFile) {
                # SSH administrator credentials never propagate to the application process.
                if ($line -match '^((?:OG_|MYSQL_|AI_)[A-Z0-9_]+|FILE_STORAGE|COOKIE_SECURE|APP_SEED)=(.*)$') {
                    [Environment]::SetEnvironmentVariable($Matches[1], $Matches[2], 'Process')
                }
            }
        }
        if ($Database -eq 'opengauss') {
            foreach ($name in @('OG_HOST', 'OG_PORT', 'OG_DATABASE', 'OG_USER', 'OG_PASSWORD')) {
                if (-not [Environment]::GetEnvironmentVariable($name, 'Process')) { throw "Missing $name in deploy/.env.opengauss." }
            }
            $env:DB_URL = "jdbc:opengauss://$($env:OG_HOST):$($env:OG_PORT)/$($env:OG_DATABASE)?stringtype=unspecified&use_boolean=true&currentSchema=public"
            $env:DB_USER = $env:OG_USER
            $env:DB_PASSWORD = $env:OG_PASSWORD
        } elseif ($Database -eq 'mysql') {
            $env:DB_URL = "jdbc:mysql://127.0.0.1:$($env:MYSQL_PORT)/$($env:MYSQL_DATABASE)?useUnicode=true&characterEncoding=UTF-8&serverTimezone=UTC&allowPublicKeyRetrieval=true&useSSL=false"
            $env:DB_USER = $env:MYSQL_USER
            $env:DB_PASSWORD = $env:MYSQL_PASSWORD
        }
        $env:SPRING_PROFILES_ACTIVE = $Database
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
