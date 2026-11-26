param(
    [switch]$Down,
    [switch]$NoBuild,
    [switch]$Lan,
    [ValidateRange(1, 65535)][int]$BackendPort = 8080,
    [ValidateRange(1, 65535)][int]$WebPort = 5173
)
$ErrorActionPreference = 'Stop'
$repoRoot = Split-Path -Parent $PSScriptRoot
$deploy = Join-Path $repoRoot 'deploy'
$envFile = Join-Path $deploy '.env.opengauss'
$pidDir = Join-Path $repoRoot 'output'
$pidFile = Join-Path $pidDir 'lan-relay.pid'
$compose = @('compose', '--env-file', $envFile, '-f', (Join-Path $deploy 'compose.yaml'), '--profile', 'app')

function Stop-LanRelay {
    if (Test-Path -LiteralPath $pidFile) {
        $oldPid = (Get-Content -LiteralPath $pidFile -ErrorAction SilentlyContinue | Select-Object -First 1)
        if ($oldPid -match '^\d+$') {
            Stop-Process -Id ([int]$oldPid) -Force -ErrorAction SilentlyContinue
        }
        Remove-Item -LiteralPath $pidFile -Force -ErrorAction SilentlyContinue
    }
}

if ($Down) {
    Stop-LanRelay
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

function Get-LanAddresses {
    try {
        return @(
            Get-NetIPAddress -AddressFamily IPv4 -ErrorAction Stop |
                Where-Object {
                    $_.IPAddress -notlike '127.*' -and
                    $_.IPAddress -notlike '169.254.*' -and
                    $_.IPAddress -notlike '198.18.*' -and
                    $_.IPAddress -notlike '198.19.*' -and
                    $_.InterfaceAlias -notmatch 'VMware|VMnet|vEthernet|WSL|Loopback|FlClash|Clash|Tailscale|ZeroTier'
                } |
                Select-Object -ExpandProperty IPAddress
        )
    } catch {
        return @()
    }
}

Stop-LanRelay
& (Join-Path $PSScriptRoot 'start-database.ps1')

# In WSL2 Mirrored mode with Hyper-V Firewall, bind containers to loopback upstream ports
# and expose 0.0.0.0:$BackendPort / $WebPort on the Windows host via lan-relay.mjs.
$containerBackendPort = if ($Lan) { if ($BackendPort -eq 18080) { 28080 } else { 18080 } } else { $BackendPort }
$containerWebPort = if ($Lan) { if ($WebPort -eq 15173) { 25173 } else { 15173 } } else { $WebPort }
$lanCors = 'http://localhost:*,http://127.0.0.1:*,http://192.168.*:*,http://10.*:*,http://172.16.*:*,http://172.17.*:*,http://172.18.*:*,http://172.19.*:*,http://172.2*.*:*,http://172.30.*:*,http://172.31.*:*'

$previousBackend = $env:BACKEND_PORT
$previousWeb = $env:WEB_PORT
$previousBind = $env:BIND_HOST
$previousCors = $env:CORS_ALLOWED_ORIGIN_PATTERNS
try {
    $env:BACKEND_PORT = "$containerBackendPort"
    $env:WEB_PORT = "$containerWebPort"
    $env:BIND_HOST = '127.0.0.1'
    if ($Lan) {
        $env:CORS_ALLOWED_ORIGIN_PATTERNS = $lanCors
    }
    $up = @('up', '-d', '--wait')
    if ($NoBuild) { $up += '--no-build' } else { $up += '--build' }
    & docker @compose @up
    if ($LASTEXITCODE -ne 0) { throw 'The application stack did not reach healthy status.' }

    if ($Lan) {
        New-Item -ItemType Directory -Force -Path $pidDir | Out-Null
        $cfgFile = Join-Path $pidDir 'lan-relay.json'
        @{
            pidFile  = $pidFile
            mappings = @(
                @{ listenHost = '0.0.0.0'; listenPort = $BackendPort; targetHost = '127.0.0.1'; targetPort = $containerBackendPort },
                @{ listenHost = '0.0.0.0'; listenPort = $WebPort; targetHost = '127.0.0.1'; targetPort = $containerWebPort }
            )
        } | ConvertTo-Json -Depth 4 | Set-Content -LiteralPath $cfgFile -Encoding utf8
        $relayScript = Join-Path $PSScriptRoot 'lan-relay.mjs'
        Start-Process -FilePath 'node' -ArgumentList @("`"$relayScript`"", "`"$cfgFile`"") -WindowStyle Hidden | Out-Null
        Start-Sleep -Milliseconds 800
    }

    $databasePort = & docker @compose port opengauss 5432
    Write-Host "Web (Local): http://127.0.0.1:$WebPort"
    Write-Host "API (Local): http://127.0.0.1:$BackendPort/api/v1/health"
    if ($Lan) {
        foreach ($ip in (Get-LanAddresses)) {
            Write-Host "Web (LAN):     http://${ip}:$WebPort"
            Write-Host "Harmony (LAN): http://${ip}:$BackendPort"
        }
    }
    Write-Host "openGauss (Local-only): $databasePort"
} finally {
    $env:BACKEND_PORT = $previousBackend
    $env:WEB_PORT = $previousWeb
    $env:BIND_HOST = $previousBind
    $env:CORS_ALLOWED_ORIGIN_PATTERNS = $previousCors
}
