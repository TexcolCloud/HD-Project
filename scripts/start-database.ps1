$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $PSScriptRoot
$envFile = Join-Path $projectRoot 'deploy/.env'
if (-not (Test-Path -LiteralPath $envFile)) {
    $password = [Guid]::NewGuid().ToString('N') + [Guid]::NewGuid().ToString('N')
    $rootPassword = [Guid]::NewGuid().ToString('N') + [Guid]::NewGuid().ToString('N')
    @("MYSQL_DATABASE=dormlink", "MYSQL_USER=dormlink", "MYSQL_PASSWORD=$password", "MYSQL_ROOT_PASSWORD=$rootPassword", "MYSQL_PORT=13306") | Set-Content -LiteralPath $envFile -Encoding utf8
}
& docker compose --env-file $envFile -f (Join-Path $projectRoot 'deploy/compose.yaml') up -d --wait mysql
if ($LASTEXITCODE -ne 0) { throw 'MySQL container failed to start; inspect docker compose logs.' }
Write-Host 'MySQL ready. Credentials are in the ignored deploy/.env file.'
