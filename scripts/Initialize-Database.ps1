# Creates only an isolated, project-owned MySQL instance. Never connects to the existing 3306 service.
. "$PSScriptRoot/Common.ps1"
$statePath = Join-Path $LocalRoot 'mysql-instance.json'
$dataPath = Join-Path $LocalRoot 'mysql-data'
$configPath = Join-Path $LocalRoot 'my.cnf'
$adminPath = Join-Path $LocalRoot 'database-admin.cnf'
$seedMarker = Join-Path $LocalRoot 'database-seeded.json'
$initPath = Join-Path $LocalRoot 'mysql-bootstrap.sql'
$mysqlAlias = Get-MySqlLocalAlias
$nativeDataPath = Join-Path $mysqlAlias 'mysql-data'
$nativeConfigPath = Join-Path $mysqlAlias 'my.cnf'
$nativeAdminPath = Join-Path $mysqlAlias 'database-admin.cnf'
$nativeInitPath = Join-Path $mysqlAlias 'mysql-bootstrap.sql'
$mysqldPath = Get-MySqlExecutable 'mysqld'
$logPath = Join-Path $LocalRoot 'logs'
New-Item -ItemType Directory -Path $logPath -Force | Out-Null

if (-not (Test-Path -LiteralPath $statePath)) {
    if (Test-LocalPort 13307) { throw 'Port 13307 is already occupied. No database changes made.' }
    if (Test-Path -LiteralPath (Join-Path $ProjectRoot '.env')) { throw 'Existing .env found. Use Start-Dev.ps1 -ExternalDatabase, or move that configuration aside before creating a managed local database.' }
    if (Test-Path -LiteralPath $dataPath) { throw 'Unrecognized mysql-data directory. Refusing to initialize or overwrite it.' }
    $appPassword = [guid]::NewGuid().ToString('N') + [guid]::NewGuid().ToString('N')
    $adminPassword = [guid]::NewGuid().ToString('N') + [guid]::NewGuid().ToString('N')
    $forwardData = $nativeDataPath.Replace('\','/')
    $forwardLog = (Join-Path $mysqlAlias 'logs/mysql-error.log').Replace('\','/')
    $forwardPid = (Join-Path $mysqlAlias 'mysql.pid').Replace('\','/')
    Write-Utf8File $configPath @"
[mysqld]
datadir="$forwardData"
port=13307
bind-address=127.0.0.1
mysqlx=0
skip-name-resolve=ON
character-set-server=utf8mb4
collation-server=utf8mb4_unicode_ci
default-time-zone=+08:00
log-error="$forwardLog"
pid-file="$forwardPid"
secure-file-priv=NULL
"@
    Write-Host 'Initializing an isolated MySQL data directory (existing MySQL service is untouched)...'
    $initializer = Start-Process -FilePath $mysqldPath -ArgumentList @(('"--defaults-file=' + $nativeConfigPath + '"'),'--initialize-insecure','--console') -Wait -PassThru -WindowStyle Hidden -RedirectStandardOutput (Join-Path $logPath 'mysql-initialize.log') -RedirectStandardError (Join-Path $logPath 'mysql-initialize-error.log')
    if ($initializer.ExitCode -ne 0) { throw 'MySQL initialization failed. See .local/logs/mysql-initialize-error.log.' }
    Write-Utf8File $adminPath @"
[client]
host=127.0.0.1
port=13307
protocol=TCP
user=root
password=$adminPassword
"@
    Write-Utf8File (Join-Path $ProjectRoot '.env') @"
DB_HOST=127.0.0.1
DB_PORT=13307
DB_NAME=copper_phase2_dev
DB_USER=phase2_app
DB_PASSWORD=$appPassword
SERVER_PORT=18280
"@
    # init-file runs before accepting connections; the freshly initialized root is secured immediately.
    Write-Utf8File $initPath @"
ALTER USER 'root'@'localhost' IDENTIFIED BY '$adminPassword';
CREATE USER IF NOT EXISTS 'root'@'127.0.0.1' IDENTIFIED BY '$adminPassword';
GRANT ALL PRIVILEGES ON *.* TO 'root'@'127.0.0.1' WITH GRANT OPTION;
CREATE DATABASE IF NOT EXISTS copper_phase2_dev CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
CREATE USER IF NOT EXISTS 'phase2_app'@'127.0.0.1' IDENTIFIED BY '$appPassword';
GRANT ALL PRIVILEGES ON copper_phase2_dev.* TO 'phase2_app'@'127.0.0.1';
"@
    Write-Utf8File $statePath (@{ dataDirectory = $dataPath; port = 13307; schema = 'copper_phase2_dev'; createdAt = [datetime]::UtcNow.ToString('o') } | ConvertTo-Json)
}

Import-DevelopmentEnvironment
if ($env:DB_HOST -ne '127.0.0.1' -or $env:DB_PORT -ne '13307' -or $env:DB_NAME -ne 'copper_phase2_dev' -or $env:DB_USER -ne 'phase2_app') {
    throw 'Managed mode only allows 127.0.0.1:13307/copper_phase2_dev as phase2_app. Use -ExternalDatabase for other settings.'
}
$state = [System.IO.File]::ReadAllText($statePath) | ConvertFrom-Json
if ($state.dataDirectory -ne $dataPath -or $state.port -ne 13307) { throw 'Database ownership metadata mismatch.' }
$owned = Get-OwnedProcess 'mysql'
if (-not $owned) {
    if (Test-LocalPort 13307) { throw 'Port 13307 belongs to an unrecognized process. Refusing to connect.' }
    $mysqlArguments = @('"--defaults-file=' + $nativeConfigPath + '"')
    if (Test-Path -LiteralPath $initPath) { $mysqlArguments += '"--init-file=' + $nativeInitPath + '"' }
    $dbProcess = Start-Process -FilePath $mysqldPath -ArgumentList $mysqlArguments -PassThru -WindowStyle Hidden -RedirectStandardOutput (Join-Path $logPath 'mysql-console.log') -RedirectStandardError (Join-Path $logPath 'mysql-console-error.log')
    Save-OwnedProcess 'mysql' $dbProcess
}
$deadline = [datetime]::UtcNow.AddSeconds(45)
$connected = $false
while ([datetime]::UtcNow -lt $deadline) {
    try {
        $identity = Invoke-MySql $nativeAdminPath 'SELECT @@datadir;'
        $actual = [System.IO.Path]::GetFullPath($identity.TrimEnd('\','/'))
        if ($actual -ne [System.IO.Path]::GetFullPath($nativeDataPath)) { throw 'Connected database data directory is not owned by this project.' }
        $connected = $true; break
    } catch { Start-Sleep -Milliseconds 500 }
}
if (-not $connected) { throw 'Owned MySQL did not become ready. See .local/logs/mysql-error.log. No existing service was changed.' }
# Windows MySQL can launch a child server. Record the actual PID written by the authenticated owned instance.
$serverProcessId = [int]([System.IO.File]::ReadAllText((Join-Path $LocalRoot 'mysql.pid')).Trim())
$serverProcess = Get-Process -Id $serverProcessId -ErrorAction Stop
if ($serverProcess.Path -ne $mysqldPath) { throw 'MySQL server executable does not match the configured installation.' }
Save-OwnedProcess 'mysql' $serverProcess
if (Test-Path -LiteralPath $initPath) { Remove-Item -LiteralPath $initPath }
if (-not (Test-Path -LiteralPath $seedMarker)) {
    $tableCount = Invoke-MySql $nativeAdminPath "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema='copper_phase2_dev';"
    if ($tableCount -ne '0') { throw 'Development schema is not empty and has no completed seed marker. Refusing to import over existing tables.' }
    $schema = [System.IO.File]::ReadAllText((Join-Path $ProjectRoot 'database/dev/001_phase1_schema_snapshot.sql'))
    $seed = [System.IO.File]::ReadAllText((Join-Path $ProjectRoot 'database/dev/002_demo_metadata_seed.sql'))
    [void](Invoke-MySql $nativeAdminPath $schema 'copper_phase2_dev')
    [void](Invoke-MySql $nativeAdminPath $seed 'copper_phase2_dev')
    Write-Utf8File $seedMarker (@{ source = 'independent-development-fixture'; importedAt = [datetime]::UtcNow.ToString('o') } | ConvertTo-Json)
    Write-Host 'Imported the development-only phase-one schema snapshot and labeled demo equipment.'
}
Write-Host 'Development MySQL is ready at 127.0.0.1:13307/copper_phase2_dev. Password is stored only in ignored local configuration.'
