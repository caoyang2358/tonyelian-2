param(
    [switch]$SkipBuild,
    [switch]$ExternalDatabase
)
. "$PSScriptRoot/Common.ps1"
New-Item -ItemType Directory -Path (Join-Path $LocalRoot 'logs') -Force | Out-Null
$runningBackend = Get-OwnedProcess 'backend'
$runningFrontend = Get-OwnedProcess 'frontend'
if ($ExternalDatabase) {
    Import-DevelopmentEnvironment
    # External databases are never initialized, seeded or migrated by this startup script.
    $env:PHASE2_RUN_MIGRATIONS = 'false'
} else {
    & "$PSScriptRoot/Initialize-Database.ps1"
    Import-DevelopmentEnvironment
    $env:PHASE2_RUN_MIGRATIONS = 'true'
}
$sessionPath = Join-Path $LocalRoot 'dev-session.json'
$hasher = [System.Security.Cryptography.SHA256]::Create()
try {
    $configuration = @($env:DB_HOST,$env:DB_PORT,$env:DB_NAME,$env:DB_USER,$env:DB_PASSWORD,$env:SERVER_PORT,[string][bool]$ExternalDatabase) -join '|'
    $fingerprint = [BitConverter]::ToString($hasher.ComputeHash($script:Utf8.GetBytes($configuration))).Replace('-','')
} finally { $hasher.Dispose() }
if ($runningBackend -or $runningFrontend) {
    if (-not (Test-Path -LiteralPath $sessionPath)) { throw 'Running session metadata is absent. Run Stop-Dev.ps1 before starting again.' }
    $session = [System.IO.File]::ReadAllText($sessionPath) | ConvertFrom-Json
    if ($session.configurationFingerprint -ne $fingerprint) { throw 'Database/startup configuration changed. Run Stop-Dev.ps1 before applying the new configuration.' }
    Write-Host 'Reusing this project''s running services; build skipped. Any missing service will be started.'
} elseif (-not $SkipBuild) {
    & "$PSScriptRoot/Build-Project.ps1"
}
$jarPath = Join-Path $ProjectRoot 'backend/target/copper-phase2-backend-0.1.0-SNAPSHOT.jar'
$vitePath = Join-Path $ProjectRoot 'frontend/node_modules/vite/bin/vite.js'
if (-not (Test-Path -LiteralPath $jarPath) -or -not (Test-Path -LiteralPath $vitePath)) { throw 'Build outputs or dependencies are absent. Run Build-Project.ps1 first, or omit -SkipBuild.' }
if (-not (Get-OwnedProcess 'backend')) {
    if (Test-LocalPort 18280) { throw 'Backend port 18280 is occupied by another process.' }
    $javaPath = (Get-Command java -ErrorAction Stop).Source
    Write-Utf8File $sessionPath (@{ configurationFingerprint = $fingerprint; externalDatabase = [bool]$ExternalDatabase } | ConvertTo-Json)
    $backendProcess = Start-Process -FilePath $javaPath -ArgumentList @('-jar',('"' + $jarPath + '"'),'--server.address=127.0.0.1') -WorkingDirectory (Join-Path $ProjectRoot 'backend') -PassThru -WindowStyle Hidden -RedirectStandardOutput (Join-Path $LocalRoot 'logs/backend.log') -RedirectStandardError (Join-Path $LocalRoot 'logs/backend-error.log')
    Save-OwnedProcess 'backend' $backendProcess $javaPath
}
Wait-Http 'http://127.0.0.1:18280/api/v1/health/ready'
if (-not (Get-OwnedProcess 'frontend')) {
    if (Test-LocalPort 5174) { throw 'Frontend port 5174 is occupied by another process.' }
    $nodePath = (Get-Command node -ErrorAction Stop).Source
    $frontendProcess = Start-Process -FilePath $nodePath -ArgumentList @(('"' + $vitePath + '"'),'--host','127.0.0.1','--port','5174','--strictPort') -WorkingDirectory (Join-Path $ProjectRoot 'frontend') -PassThru -WindowStyle Hidden -RedirectStandardOutput (Join-Path $LocalRoot 'logs/frontend.log') -RedirectStandardError (Join-Path $LocalRoot 'logs/frontend-error.log')
    Save-OwnedProcess 'frontend' $frontendProcess $nodePath
}
Wait-Http 'http://127.0.0.1:5174/'
& "$PSScriptRoot/Check-Dev.ps1"
Write-Host 'Open http://127.0.0.1:5174/ . Use Stop-Dev.ps1 to stop only this project.'
