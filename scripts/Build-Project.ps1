param([switch]$SkipInstall)
. "$PSScriptRoot/Common.ps1"
if ((Get-OwnedProcess 'backend') -or (Get-OwnedProcess 'frontend')) {
    throw 'Stop this project''s frontend and backend before building, so Windows does not lock the JAR or native Node dependencies. Use Stop-Dev.ps1 -KeepDatabase.'
}
foreach ($tool in @('node','npm.cmd','java','mvn.cmd')) {
    if (-not (Get-Command $tool -ErrorAction SilentlyContinue)) { throw "Missing tool: $tool. See README.md prerequisites." }
}
Push-Location (Join-Path $ProjectRoot 'frontend')
try {
    if (-not $SkipInstall) {
        & npm.cmd ci --offline=false --registry=https://registry.npmjs.org --cache (Join-Path $LocalRoot 'npm-cache')
        if ($LASTEXITCODE -ne 0) { throw 'npm ci failed.' }
    }
    & npm.cmd test
    if ($LASTEXITCODE -ne 0) { throw 'Frontend tests failed.' }
    & npm.cmd run build
    if ($LASTEXITCODE -ne 0) { throw 'Frontend build failed.' }
} finally { Pop-Location }
Push-Location (Join-Path $ProjectRoot 'backend')
try {
    & mvn.cmd -gs (Join-Path $PSScriptRoot 'maven-settings.xml') -s (Join-Path $PSScriptRoot 'maven-settings.xml') --batch-mode clean verify
    if ($LASTEXITCODE -ne 0) { throw 'Backend verification failed.' }
} finally { Pop-Location }
Write-Host 'Frontend and backend tests/builds passed.'
