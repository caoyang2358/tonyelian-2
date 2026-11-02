. "$PSScriptRoot/Common.ps1"
$frontend = Invoke-WebRequest -Uri 'http://127.0.0.1:5174/' -UseBasicParsing -TimeoutSec 5
if ($frontend.StatusCode -ne 200) { throw 'Frontend HTTP check failed.' }
$live = Invoke-RestMethod -Uri 'http://127.0.0.1:18280/api/v1/health/live' -TimeoutSec 5
$ready = Invoke-RestMethod -Uri 'http://127.0.0.1:18280/api/v1/health/ready' -TimeoutSec 5
$overview = Invoke-RestMethod -Uri 'http://127.0.0.1:18280/api/v1/bootstrap/overview' -TimeoutSec 5
$equipment = Invoke-RestMethod -Uri 'http://127.0.0.1:5174/api/v1/equipment' -TimeoutSec 5
if ($live.code -ne 'OK' -or $ready.code -ne 'OK' -or $ready.data.status -ne 'UP' -or $overview.code -ne 'OK' -or $equipment.code -ne 'OK') { throw 'Backend/database/proxy health check failed.' }
Write-Host ("PASS frontend=200 backend=UP mysql={0} tables(phase1/phase2)={1}/{2} equipment={3}" -f $overview.data.database.serverVersion, $overview.data.database.phase1TableCount, $overview.data.database.phase2TableCount, @($equipment.data.items).Count)
