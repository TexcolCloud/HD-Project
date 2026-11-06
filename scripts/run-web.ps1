$ErrorActionPreference = 'Stop'
$webRoot = Join-Path (Split-Path -Parent $PSScriptRoot) 'web'
Push-Location $webRoot
try {
    if (-not (Test-Path -LiteralPath 'node_modules')) {
        & npm.cmd ci
        if ($LASTEXITCODE -ne 0) { throw 'Web dependency installation failed.' }
    }
    & npm.cmd run dev
    if ($LASTEXITCODE -ne 0) { throw 'Web server stopped with an error.' }
} finally { Pop-Location }
