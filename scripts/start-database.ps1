param([ValidateSet('opengauss', 'mysql')][string]$Database = 'opengauss')
$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $PSScriptRoot
if ($Database -eq 'opengauss') {
    $openGaussEnvironment = Join-Path $projectRoot 'deploy/.env.opengauss'
    if (-not (Test-Path -LiteralPath $openGaussEnvironment)) { throw 'Configure deploy/.env.opengauss from deploy/opengauss.env.example first.' }
    $settings = @{}
    foreach ($line in Get-Content -LiteralPath $openGaussEnvironment) {
        if ($line -match '^(OG_HOST|OG_PORT)=(.*)$') { $settings[$Matches[1]] = $Matches[2] }
    }
    if (-not $settings.OG_HOST -or -not $settings.OG_PORT) { throw 'OG_HOST and OG_PORT are required.' }
    $probe = [System.Net.Sockets.TcpClient]::new()
    try {
        $connection = $probe.ConnectAsync($settings.OG_HOST, [int]$settings.OG_PORT)
        if (-not $connection.Wait(5000)) { throw 'openGauss TCP connection timed out.' }
        $connection.GetAwaiter().GetResult() | Out-Null
        Write-Host 'openGauss TCP port is reachable. Start the API and check /api/v1/health to verify database login and SQL.'
    } finally { $probe.Dispose() }
    exit 0
}
$envFile = Join-Path $projectRoot 'deploy/.env'
if (-not (Test-Path -LiteralPath $envFile)) {
    $password = [Guid]::NewGuid().ToString('N') + [Guid]::NewGuid().ToString('N')
    $rootPassword = [Guid]::NewGuid().ToString('N') + [Guid]::NewGuid().ToString('N')
    @("MYSQL_DATABASE=dormlink", "MYSQL_USER=dormlink", "MYSQL_PASSWORD=$password", "MYSQL_ROOT_PASSWORD=$rootPassword", "MYSQL_PORT=13306") | Set-Content -LiteralPath $envFile -Encoding utf8
}
& docker compose --env-file $envFile -f (Join-Path $projectRoot 'deploy/compose.yaml') up -d --wait mysql
if ($LASTEXITCODE -ne 0) { throw 'MySQL container failed to start; inspect docker compose logs.' }
Write-Host 'MySQL ready. Credentials are in the ignored deploy/.env file.'
