param(
    [switch]$CheckOnly,
    [switch]$SelfTest,
    [string]$ReportPath = 'output/acceptance/opengauss-migration-results.json'
)
$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $PSScriptRoot
$backendRoot = Join-Path $projectRoot 'backend'
$classpathFile = Join-Path $backendRoot 'target/migration-classpath.txt'

# Maven resolves only the already-declared runtime drivers; no credentials are passed as arguments.
Push-Location $backendRoot
try {
    & .\mvnw.cmd -B -q dependency:build-classpath '-Dmdep.includeScope=runtime' '-Dmdep.outputFile=target/migration-classpath.txt'
    if ($LASTEXITCODE -ne 0) { throw 'Cannot resolve the backend runtime drivers.' }
} finally { Pop-Location }
$runtimeClasspath = (Get-Content -LiteralPath $classpathFile -Raw).Trim()
$program = Join-Path $PSScriptRoot 'MigrateDatabase.java'

if ($SelfTest) {
    Push-Location $projectRoot
    try {
        & java '-Dfile.encoding=UTF-8' --class-path $runtimeClasspath --source 21 $program --self-test
        if ($LASTEXITCODE -ne 0) { throw 'Migration JDBC self-test failed.' }
    } finally { Pop-Location }
    return
}

foreach ($name in @('deploy/.env', 'deploy/.env.opengauss')) {
    $file = Join-Path $projectRoot $name
    if (-not (Test-Path -LiteralPath $file)) { throw "Missing ignored local configuration: $name" }
    foreach ($line in Get-Content -LiteralPath $file) {
        if ($line -match '^((?:MYSQL_|OG_)[A-Z0-9_]+|FILE_STORAGE)=(.*)$') {
            $value = $Matches[2].Trim()
            if ($value.Length -ge 2 -and (($value.StartsWith('"') -and $value.EndsWith('"')) -or ($value.StartsWith("'") -and $value.EndsWith("'")))) { $value = $value.Substring(1, $value.Length - 2) }
            [Environment]::SetEnvironmentVariable($Matches[1], $value, 'Process')
        }
    }
}
if (-not [IO.Path]::IsPathRooted($ReportPath)) { $ReportPath = Join-Path $projectRoot $ReportPath }
$javaArguments = @('-Dfile.encoding=UTF-8', '--class-path', $runtimeClasspath, '--source', '21', $program)
if ($CheckOnly) { $javaArguments += '--check-only' }
$javaArguments += @($projectRoot, $ReportPath)
if (-not $CheckOnly) { Write-Host 'Migrate only with the source API stopped. Target business tables must be empty; existing rows are never deleted.' }
& java @javaArguments
if ($LASTEXITCODE -ne 0) { throw "Migration refused or failed; inspect $ReportPath. Source data was not modified." }
Write-Host "Value-free migration report: $ReportPath"
