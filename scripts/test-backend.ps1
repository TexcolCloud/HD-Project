param([ValidateSet('local', 'opengauss')][string]$Database = 'local')
$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $PSScriptRoot
$names = @('OPENGAUSS_TEST_URL', 'OPENGAUSS_TEST_USER', 'OPENGAUSS_TEST_PASSWORD')
$previous = @{}
foreach ($name in $names) { $previous[$name] = [Environment]::GetEnvironmentVariable($name, 'Process') }
try {
    foreach ($name in $names) { [Environment]::SetEnvironmentVariable($name, $null, 'Process') }
    if ($Database -eq 'opengauss') {
        $settings = @{}
        $environmentFile = Join-Path $projectRoot 'deploy/.env.opengauss'
        if (-not (Test-Path -LiteralPath $environmentFile)) { throw 'Create ignored deploy/.env.opengauss from its example first.' }
        foreach ($line in Get-Content -LiteralPath $environmentFile) {
            if ($line -match '^(OG_[A-Z0-9_]+)=(.*)$') { $settings[$Matches[1]] = $Matches[2] }
        }
        foreach ($name in @('OG_HOST', 'OG_PORT', 'OG_USER', 'OG_PASSWORD')) {
            if (-not $settings[$name]) { throw "Missing $name in the local database environment file." }
        }
        # Tests validate the template and only use isolated dormlink_*_test databases.
        $env:OPENGAUSS_TEST_URL = "jdbc:opengauss://$($settings.OG_HOST):$($settings.OG_PORT)/dormlink_{suite}_test?stringtype=unspecified&use_boolean=true&currentSchema=public"
        $env:OPENGAUSS_TEST_USER = $settings.OG_USER
        $env:OPENGAUSS_TEST_PASSWORD = $settings.OG_PASSWORD
    }
    Push-Location (Join-Path $projectRoot 'backend')
    try {
        & .\mvnw.cmd -B verify
        if ($LASTEXITCODE -ne 0) { throw "Backend verification failed for $Database; see target/surefire-reports." }
    } finally { Pop-Location }
} finally {
    foreach ($name in $names) { [Environment]::SetEnvironmentVariable($name, $previous[$name], 'Process') }
}
