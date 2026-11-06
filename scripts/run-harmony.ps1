param([switch]$BuildOnly, [string]$Target = '127.0.0.1:5555')
$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $PSScriptRoot
$ideRoot = 'C:\Program Files\Huawei\DevEco Studio'
$env:NODE_HOME = Join-Path $ideRoot 'tools/node'
$env:DEVECO_SDK_HOME = Join-Path $ideRoot 'sdk'
$env:PATH = "$env:NODE_HOME;$env:PATH"
$harmonyRoot = Join-Path $projectRoot 'harmony'
Push-Location $harmonyRoot
try {
    & python generate-models.py
    if ($LASTEXITCODE -ne 0) { throw 'ArkTS contract generation failed.' }
    if (-not (Test-Path -LiteralPath 'oh_modules')) {
        & (Join-Path $ideRoot 'tools/ohpm/bin/ohpm.bat') install
        if ($LASTEXITCODE -ne 0) { throw 'Harmony dependency installation failed.' }
    }
    & (Join-Path $ideRoot 'tools/hvigor/bin/hvigorw.bat') --mode module -p product=default assembleHap --no-daemon
    if ($LASTEXITCODE -ne 0) { throw 'Harmony build failed.' }
    if (-not $BuildOnly) {
        $hdc = Join-Path $ideRoot 'sdk/default/openharmony/toolchains/hdc.exe'
        $targets = & $hdc list targets
        if ($targets -notcontains $Target) { throw "Start the existing Pura 90 emulator first; target $Target is unavailable." }
        $forwarding = (& $hdc -t $Target fport ls | Out-String)
        $existing = [regex]::Escape($Target) + '\s+tcp:8080 tcp:8080\s+\[Reverse\]'
        if ($forwarding -notmatch $existing) {
            & $hdc -t $Target rport tcp:8080 tcp:8080
            if ($LASTEXITCODE -ne 0) { throw 'Device API port forwarding failed.' }
        }
        & $hdc -t $Target install entry/build/default/outputs/default/entry-default-unsigned.hap
        if ($LASTEXITCODE -ne 0) { throw 'HAP install failed.' }
        & $hdc -t $Target shell aa start -b com.dormlink.residence -a EntryAbility
        if ($LASTEXITCODE -ne 0) { throw 'Ability launch failed.' }
    }
} finally { Pop-Location }
