param(
    [switch]$Down,
    [switch]$NoBuild,
    [ValidateRange(1, 65535)][int]$BackendPort = 8080,
    [ValidateRange(1, 65535)][int]$WebPort = 5173
)
$ErrorActionPreference = 'Stop'
$deploy = Join-Path (Split-Path -Parent $PSScriptRoot) 'deploy'
$envFile = Join-Path $deploy '.env.opengauss'
$compose = @('compose', '--env-file', $envFile, '-f', (Join-Path $deploy 'compose.yaml'), '--profile', 'app')

if ($Down) {
    if (-not (Test-Path -LiteralPath $envFile)) { throw 'The stack environment file deploy/.env.opengauss is missing.' }
    & docker @compose down web backend opengauss
    if ($LASTEXITCODE -ne 0) { throw 'Could not stop the container stack.' }
    return
}

if (Test-Path -LiteralPath $envFile) {
    $hostLine = Get-Content -LiteralPath $envFile | Where-Object { $_ -match '^OG_HOST=' } | Select-Object -Last 1
    if ($hostLine -and $hostLine -notin @('OG_HOST=localhost', 'OG_HOST=127.0.0.1', 'OG_HOST=your-opengauss-host', 'OG_HOST=')) {
        throw 'Full-stack mode uses the local Docker database. For an external OG_HOST run start-database.ps1 and run-backend.ps1.'
    }
}
& (Join-Path $PSScriptRoot 'start-database.ps1')
$previousBackend = $env:BACKEND_PORT
$previousWeb = $env:WEB_PORT
try {
    $env:BACKEND_PORT = "$BackendPort"
    $env:WEB_PORT = "$WebPort"
    $up = @('up', '-d', '--wait')
    if ($NoBuild) { $up += '--no-build' } else { $up += '--build' }
    & docker @compose @up
    if ($LASTEXITCODE -ne 0) { throw 'The application stack did not reach healthy status.' }
    $databasePort = & docker @compose port opengauss 5432
    Write-Host "Web: http://127.0.0.1:$WebPort"
    Write-Host "API: http://127.0.0.1:$BackendPort/api/v1/health"
    Write-Host "openGauss: $databasePort"
} finally {
    $env:BACKEND_PORT = $previousBackend
    $env:WEB_PORT = $previousWeb
}
