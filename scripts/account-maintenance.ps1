param(
    [ValidateSet('status', 'reset-password', 'create-manager', 'set-enabled', 'set-buildings')][string]$Action = 'status',
    [string]$Username,
    [int]$ExpectedVersion = -1,
    [string]$Reason,
    [string]$DisplayName,
    [string]$BuildingIds,
    [ValidateSet('true', 'false')][string]$Enabled,
    [string]$CredentialFile,
    [string]$Database,
    [switch]$SelfTest
)
$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $PSScriptRoot
$classpathPath = Join-Path $projectRoot 'backend/target/migration-classpath.txt'
if (-not (Test-Path -LiteralPath $classpathPath)) { throw 'Run scripts/migrate-database.ps1 -SelfTest once to resolve the declared runtime classpath.' }
$classpath = (Get-Content -LiteralPath $classpathPath -Raw).Trim()
$program = Join-Path $PSScriptRoot 'AccountMaintenance.java'
if ($SelfTest) {
    & java '-Dfile.encoding=UTF-8' --class-path $classpath --source 21 $program --self-test
    if ($LASTEXITCODE -ne 0) { throw 'Account maintenance safeguards failed.' }
    return
}
if (-not $Username) { throw 'Specify -Username. Inspect status before changes.' }
if ($Action -ne 'status' -and -not $Reason) { throw 'A maintenance reason is required.' }
if ($Action -notin @('status', 'create-manager') -and $ExpectedVersion -lt 0) { throw 'Specify the AccountVersion returned by status.' }
$names = @('OG_USER', 'OG_PASSWORD', 'ACCOUNT_DB_URL')
$previous = @{}
foreach ($name in $names) { $previous[$name] = [Environment]::GetEnvironmentVariable($name, 'Process') }
try {
    $settings = @{}
    foreach ($line in Get-Content -LiteralPath (Join-Path $projectRoot 'deploy/.env.opengauss')) {
        if ($line -match '^(OG_[A-Z0-9_]+)=(.*)$') { $settings[$Matches[1]] = $Matches[2].Trim().Trim('"').Trim("'") }
    }
    foreach ($name in @('OG_HOST', 'OG_PORT', 'OG_DATABASE', 'OG_USER', 'OG_PASSWORD')) {
        if (-not $settings[$name]) { throw "Missing $name in local configuration." }
    }
    $env:OG_USER = $settings.OG_USER
    $env:OG_PASSWORD = $settings.OG_PASSWORD
    $targetDatabase = $settings.OG_DATABASE
    if ($Database) {
        if ($Database -notmatch '^dormlink_[a-z0-9_]+_test$') { throw 'An override must be an isolated dormlink_*_test database.' }
        $targetDatabase = $Database
    }
    $env:ACCOUNT_DB_URL = "jdbc:opengauss://$($settings.OG_HOST):$($settings.OG_PORT)/$targetDatabase`?stringtype=unspecified&use_boolean=true&currentSchema=public"
    $arguments = @('-Dfile.encoding=UTF-8', '--class-path', $classpath, '--source', '21', $program,
        '--action', $Action, '--username', $Username, '--project-root', $projectRoot)
    if ($Action -ne 'status') { $arguments += @('--reason', $Reason) }
    if ($ExpectedVersion -ge 0) { $arguments += @('--expected-version', "$ExpectedVersion") }
    if ($Action -in @('reset-password', 'create-manager')) {
        if (-not $CredentialFile) { $CredentialFile = Join-Path $projectRoot ("data/credentials/delivery-$(Get-Date -Format 'yyyyMMddHHmmssfff').json") }
        $arguments += @('--credential-file', [IO.Path]::GetFullPath($CredentialFile))
    }
    if ($Action -eq 'create-manager') { $arguments += @('--display-name', $DisplayName) }
    if ($Action -in @('set-buildings', 'create-manager')) { $arguments += @('--building-ids', $BuildingIds) }
    if ($Action -eq 'set-enabled') { $arguments += @('--enabled', $Enabled) }
    & java @arguments
    if ($LASTEXITCODE -ne 0) { throw 'Account maintenance refused or failed. No credential is printed.' }
    if ($CredentialFile) { Write-Host "Private delivery file: $CredentialFile. Deliver securely, then delete the delivery file." }
} finally {
    foreach ($name in $names) { [Environment]::SetEnvironmentVariable($name, $previous[$name], 'Process') }
}
