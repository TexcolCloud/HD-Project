param([Parameter(Mandatory)][string]$Database, [Parameter(Mandatory)][string]$Uploads)
$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $PSScriptRoot
if ($Database -notmatch '^dormlink_recovery_[a-z0-9_]+_test$') { throw 'Use an isolated recovery database.' }
$uploadsPath = [IO.Path]::GetFullPath($Uploads)
$allowed = [IO.Path]::GetFullPath((Join-Path $projectRoot "data/recovery/$Database/uploads"))
if ($uploadsPath -ne $allowed) { throw 'Uploads must be the paired isolated recovery directory.' }
$names = @('OG_HOST', 'OG_PORT', 'OG_USER', 'OG_PASSWORD')
$previous = @{}
foreach ($name in $names) { $previous[$name] = [Environment]::GetEnvironmentVariable($name, 'Process') }
try {
    foreach ($line in Get-Content -LiteralPath (Join-Path $projectRoot 'deploy/.env.opengauss')) {
        if ($line -match '^(OG_HOST|OG_PORT|OG_USER|OG_PASSWORD)=(.*)$') {
            [Environment]::SetEnvironmentVariable($Matches[1], $Matches[2].Trim().Trim('"').Trim("'"), 'Process')
        }
    }
    $classpath = (Get-Content -LiteralPath (Join-Path $projectRoot 'backend/target/migration-classpath.txt') -Raw).Trim()
    & java '-Dfile.encoding=UTF-8' --class-path $classpath --source 21 (Join-Path $PSScriptRoot 'RestoreReadCheck.java') $Database $uploadsPath
    if ($LASTEXITCODE -ne 0) { throw 'Application-role recovery verification failed.' }
} finally {
    foreach ($name in $names) { [Environment]::SetEnvironmentVariable($name, $previous[$name], 'Process') }
}
