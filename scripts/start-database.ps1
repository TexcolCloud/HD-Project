param([ValidateSet('opengauss', 'mysql')][string]$Database = 'opengauss')
$ErrorActionPreference = 'Stop'
$deploy = Join-Path (Split-Path -Parent $PSScriptRoot) 'deploy'

function New-DatabasePassword([string]$Prefix) {
    $bytes = New-Object byte[] 12
    $rng = [Security.Cryptography.RandomNumberGenerator]::Create()
    try { $rng.GetBytes($bytes) } finally { $rng.Dispose() }
    return $Prefix + '_' + [BitConverter]::ToString($bytes).Replace('-', '').ToLowerInvariant() + '!9Aa'
}
function Read-Settings($Lines) {
    $values = @{}
    foreach ($line in $Lines) {
        if ($line -match '^([A-Z_][A-Z0-9_]*)=(.*)$') { $values[$Matches[1]] = $Matches[2] }
    }
    return $values
}

if ($Database -eq 'mysql') {
    $envFile = Join-Path $deploy '.env'
    if (-not (Test-Path -LiteralPath $envFile)) {
        @('MYSQL_DATABASE=dormlink', 'MYSQL_USER=dormlink', "MYSQL_PASSWORD=$(New-DatabasePassword 'MyApp')",
          "MYSQL_ROOT_PASSWORD=$(New-DatabasePassword 'MyAdm')", 'MYSQL_PORT=13306') | Set-Content -LiteralPath $envFile -Encoding ascii
    }
    $settings = Read-Settings (Get-Content -LiteralPath $envFile)
    if (-not $settings.MYSQL_PASSWORD -or -not $settings.MYSQL_ROOT_PASSWORD) { throw 'Set both MySQL passwords in deploy/.env.' }
    # Compose interpolates disabled profiles too. Use the same standalone legacy service without loading openGauss secrets.
    & docker compose --env-file $envFile -f (Join-Path $deploy 'compose.mysql.yaml') --profile legacy-mysql up -d --wait mysql
    if ($LASTEXITCODE -ne 0) { throw 'MySQL failed to start.' }
    Write-Host 'MySQL ready.'
    return
}

$envFile = Join-Path $deploy '.env.opengauss'
$exists = Test-Path -LiteralPath $envFile
$sourceFile = if ($exists) { $envFile } else { Join-Path $deploy 'opengauss.env.example' }
$lines = @(Get-Content -Encoding utf8 -LiteralPath $sourceFile)
$settings = Read-Settings $lines
if ($settings.OG_HOST -and $settings.OG_HOST -notin @('localhost', '127.0.0.1', 'your-opengauss-host')) {
    $probe = [Net.Sockets.TcpClient]::new()
    try {
        $connection = $probe.ConnectAsync($settings.OG_HOST, [int]$settings.OG_PORT)
        if (-not $connection.Wait(5000)) { throw 'openGauss TCP connection timed out.' }
        $connection.GetAwaiter().GetResult() | Out-Null
        Write-Host 'External openGauss TCP port is reachable. Check API health to verify SQL login.'
    } finally { $probe.Dispose() }
    return
}

$defaults = [ordered]@{ OG_HOST = '127.0.0.1'; OG_PORT = '15432'; OG_DATABASE = 'dormlink'; OG_USER = 'dormlink'
    OG_PASSWORD = (New-DatabasePassword 'Ap'); OG_ADMIN_PASSWORD = (New-DatabasePassword 'Ad') }
$changed = -not $exists
foreach ($key in $defaults.Keys) {
    if (-not $settings[$key] -or $settings[$key] -eq 'your-opengauss-host' -or $settings[$key] -like 'replace-with-*') {
        $entry = "$key=$($defaults[$key])"
        if ($settings.ContainsKey($key)) { $lines = @($lines -replace "^$key=.*$", $entry) } else { $lines += $entry }
        $settings[$key] = $defaults[$key]
        $changed = $true
    }
}
# The pinned image interpolates GS_PASSWORD in a shell command during gs_initdb.
if ($settings.OG_ADMIN_PASSWORD -cnotmatch '^[A-Za-z0-9_!@%.,:+-]{8,32}$') {
    throw 'Use an 8-32 character shell-safe OG_ADMIN_PASSWORD as described in deploy/opengauss.env.example.'
}
foreach ($key in @('OG_PASSWORD', 'OG_ADMIN_PASSWORD')) {
    $value = $settings[$key]
    if ($value.Length -notin 8..32 -or $value -cnotmatch '[a-z]' -or $value -cnotmatch '[A-Z]' -or
        $value -notmatch '[0-9]' -or $value -notmatch '[^A-Za-z0-9]' -or
        $value -match [regex]::Escape($settings.OG_USER) -or $value -match 'omm') {
        throw "$key must meet openGauss password complexity and omit the account names."
    }
}
if ($settings.OG_ADMIN_PASSWORD -notmatch '[!@%\-]' -or $settings.OG_ADMIN_PASSWORD -ceq $settings.OG_PASSWORD) {
    throw 'Use a separate administrator password with a supported symbol (!, @, % or -).'
}
if ($changed) {
    $temp = Join-Path $deploy ('.env.opengauss.' + [Guid]::NewGuid().ToString('N'))
    try {
        [IO.File]::WriteAllLines($temp, [string[]]$lines, [Text.UTF8Encoding]::new($false))
        if ($exists) { Move-Item -LiteralPath $temp -Destination $envFile -Force }
        else { [IO.File]::Move($temp, $envFile) }
    } finally { if (Test-Path -LiteralPath $temp) { Remove-Item -LiteralPath $temp } }
}

$compose = @('compose', '--env-file', $envFile, '-f', (Join-Path $deploy 'compose.yaml'))
& docker @compose up -d opengauss
if ($LASTEXITCODE -ne 0) { throw 'openGauss failed to start.' }
# Existing volumes skip image initialization. Reconcile before waiting for application-role health.
& docker @compose exec -T --user omm opengauss bash /docker-entrypoint-initdb.d/01-init-dormlink.sh --reconcile
if ($LASTEXITCODE -ne 0) { throw 'openGauss database or grant verification failed. Existing data was preserved.' }
& docker @compose up -d --wait opengauss
if ($LASTEXITCODE -ne 0) { throw 'openGauss application login or PG compatibility check failed.' }
Write-Host 'Local openGauss and isolated test databases are ready.'
