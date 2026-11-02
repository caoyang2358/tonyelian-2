$ErrorActionPreference = 'Stop'
$script:ProjectRoot = Split-Path $PSScriptRoot -Parent
$script:LocalRoot = Join-Path $ProjectRoot '.local'
New-Item -ItemType Directory -Path $LocalRoot -Force | Out-Null
$script:Utf8 = New-Object System.Text.UTF8Encoding($false)

function Write-Utf8File([string]$Path, [string]$Value) {
    [System.IO.File]::WriteAllText($Path, $Value, $script:Utf8)
}

function Import-DevelopmentEnvironment {
    $envPath = Join-Path $ProjectRoot '.env'
    if (-not (Test-Path -LiteralPath $envPath)) { throw 'Missing .env. Run Initialize-Database.ps1 first, or copy .env.example for an external development database.' }
    foreach ($line in [System.IO.File]::ReadAllLines($envPath)) {
        if ($line -match '^\s*(#|$)') { continue }
        if ($line -notmatch '^([A-Z][A-Z0-9_]*)=(.*)$') { throw 'Invalid .env line. Use KEY=value; do not use shell commands.' }
        $key = $matches[1]; $value = $matches[2].Trim()
        if ($key -notin @('DB_HOST','DB_PORT','DB_NAME','DB_USER','DB_PASSWORD','SERVER_PORT')) { throw "Unsupported .env key: $key" }
        [Environment]::SetEnvironmentVariable($key, $value, 'Process')
    }
    foreach ($key in @('DB_HOST','DB_PORT','DB_NAME','DB_USER','DB_PASSWORD')) {
        if ([string]::IsNullOrWhiteSpace([Environment]::GetEnvironmentVariable($key))) { throw "Missing setting: $key" }
    }
    if (-not $env:SERVER_PORT) { $env:SERVER_PORT = '18280' }
    if ($env:SERVER_PORT -ne '18280') { throw 'This starter uses backend port 18280; update the Vite proxy before changing it.' }
}

function Test-LocalPort([int]$Port) {
    $client = New-Object System.Net.Sockets.TcpClient
    try {
        $connect = $client.ConnectAsync('127.0.0.1', $Port)
        if (-not $connect.Wait(400)) { return $false }
        return $client.Connected
    } catch { return $false } finally { $client.Dispose() }
}

function Get-MySqlExecutable([string]$Name) {
    $command = Get-Command "$Name.exe" -ErrorAction SilentlyContinue
    if ($command) { return $command.Source }
    $candidates = @()
    if ($env:MYSQL_HOME) { $candidates += Join-Path $env:MYSQL_HOME "bin/$Name.exe" }
    $candidates += Join-Path $env:ProgramFiles "MySQL/MySQL Server 8.0/bin/$Name.exe"
    $candidates += Join-Path $env:ProgramFiles "MySQL/MySQL Server 8.4/bin/$Name.exe"
    foreach ($candidate in $candidates) { if (Test-Path -LiteralPath $candidate) { return $candidate } }
    throw "Cannot find $Name.exe. Install MySQL 8.x server/client or set MYSQL_HOME to its installation directory."
}

function Get-MySqlLocalAlias {
    # MySQL 8.0 for Windows cannot reliably use a non-ASCII data directory.
    # The junction stores no data; all database files remain under this project's .local.
    $hasher = [System.Security.Cryptography.SHA256]::Create()
    try { $hash = [BitConverter]::ToString($hasher.ComputeHash($script:Utf8.GetBytes($LocalRoot.ToLowerInvariant()))).Replace('-','').Substring(0,16) }
    finally { $hasher.Dispose() }
    $alias = Join-Path ([System.IO.Path]::GetTempPath()) ("copper-phase2-" + $hash)
    if ($alias -match '[^\x00-\x7F]') { throw 'MySQL requires an ASCII TEMP path. Set TEMP/TMP to an ASCII directory and retry.' }
    if (Test-Path -LiteralPath $alias) {
        $item = Get-Item -LiteralPath $alias -Force
        if ($item.LinkType -ne 'Junction' -or [System.IO.Path]::GetFullPath([string]$item.Target) -ne [System.IO.Path]::GetFullPath($LocalRoot)) {
            throw 'MySQL temporary path exists and is not the expected project junction.'
        }
    } else {
        New-Item -ItemType Junction -Path $alias -Target $LocalRoot | Out-Null
    }
    return $alias
}

function Invoke-MySql([string]$DefaultsFile, [string]$Sql, [string]$Database = '') {
    $clientPath = Get-MySqlExecutable 'mysql'
    $info = New-Object System.Diagnostics.ProcessStartInfo
    $info.FileName = $clientPath
    $info.Arguments = '--defaults-file="' + $DefaultsFile + '" --default-character-set=utf8mb4 --batch --raw --skip-column-names'
    $info.EnvironmentVariables['MYSQL_TEST_LOGIN_FILE'] = Join-Path $LocalRoot 'unused-client-login.cnf'
    $info.EnvironmentVariables.Remove('MYSQL_PWD')
    if ($Database) {
        if ($Database -notmatch '^[a-zA-Z0-9_]+$') { throw 'Invalid database name.' }
        $info.Arguments += ' --database=' + $Database
    }
    $info.UseShellExecute = $false; $info.CreateNoWindow = $true
    $info.RedirectStandardInput = $true; $info.RedirectStandardOutput = $true; $info.RedirectStandardError = $true
    $info.StandardOutputEncoding = $script:Utf8
    $process = New-Object System.Diagnostics.Process
    $process.StartInfo = $info
    [void]$process.Start()
    $stdout = $process.StandardOutput.ReadToEndAsync()
    $stderr = $process.StandardError.ReadToEndAsync()
    $bytes = $script:Utf8.GetBytes($Sql)
    $process.StandardInput.BaseStream.Write($bytes, 0, $bytes.Length)
    $process.StandardInput.Close()
    if (-not $process.WaitForExit(30000)) { $process.Kill(); throw 'MySQL command timed out.' }
    $output = $stdout.GetAwaiter().GetResult(); $errorText = $stderr.GetAwaiter().GetResult()
    if ($process.ExitCode -ne 0) {
        # Do not echo SQL, configuration or passwords into the terminal.
        $safeError = $errorText -replace "(?i)(password[=: ]+)[^\s]+", '$1[REDACTED]'
        throw "MySQL command failed: $safeError"
    }
    return $output.Trim()
}

function Save-OwnedProcess([string]$Name, $Process, [string]$ExecutablePath) {
    # A Start-Process object can temporarily report a null Path on Windows.
    # Re-query the exact process and retain the known launch path as a fallback.
    if ([string]::IsNullOrWhiteSpace($ExecutablePath) -or -not [System.IO.Path]::IsPathRooted($ExecutablePath) -or -not (Test-Path -LiteralPath $ExecutablePath -PathType Leaf)) {
        throw "A known absolute executable path is required for $Name."
    }
    $Process.Refresh()
    if ($Process.HasExited) { throw "$Name exited before its process record could be saved. See .local/logs." }
    $startedAt = $Process.StartTime.ToUniversalTime()
    $actualPath = $null
    for ($attempt = 0; $attempt -lt 10; $attempt++) {
        $current = Get-Process -Id $Process.Id -ErrorAction SilentlyContinue
        if (-not $current -or $current.HasExited) { throw "$Name exited during startup. See .local/logs." }
        if ([Math]::Abs(($current.StartTime.ToUniversalTime() - $startedAt).TotalSeconds) -gt 1) { throw "Process identity changed during $Name startup." }
        $actualPath = $current.Path
        if (-not [string]::IsNullOrWhiteSpace($actualPath)) { break }
        Start-Sleep -Milliseconds 50
    }
    if ([string]::IsNullOrWhiteSpace($actualPath)) {
        $launchItem = Get-Item -LiteralPath $ExecutablePath
        $actualPath = $launchItem.FullName
        if ($launchItem.LinkType -eq 'SymbolicLink' -and $launchItem.Target) {
            $target = [string]$launchItem.Target
            if (-not [System.IO.Path]::IsPathRooted($target)) { $target = Join-Path $launchItem.DirectoryName $target }
            $actualPath = [System.IO.Path]::GetFullPath($target)
        }
    }
    $record = @{ id = $Process.Id; startTimeUtc = $startedAt.ToString('o'); executable = $actualPath; launchExecutable = $ExecutablePath }
    Write-Utf8File (Join-Path $LocalRoot "$Name.process.json") ($record | ConvertTo-Json)
}

function Get-OwnedProcess([string]$Name) {
    $recordPath = Join-Path $LocalRoot "$Name.process.json"
    if (-not (Test-Path -LiteralPath $recordPath)) { return $null }
    $record = [System.IO.File]::ReadAllText($recordPath) | ConvertFrom-Json
    if ([string]::IsNullOrWhiteSpace([string]$record.executable)) { return $null }
    $process = Get-Process -Id $record.id -ErrorAction SilentlyContinue
    if (-not $process -or $process.HasExited) { return $null }
    if ([string]::IsNullOrWhiteSpace([string]$process.Path)) { return $null }
    if ($record.startTimeUtc -is [datetime]) { $startTime = $record.startTimeUtc.ToUniversalTime() }
    else { $startTime = [datetime]::Parse($record.startTimeUtc, [System.Globalization.CultureInfo]::InvariantCulture, [System.Globalization.DateTimeStyles]::RoundtripKind).ToUniversalTime() }
    if ([Math]::Abs(($process.StartTime.ToUniversalTime() - $startTime).TotalSeconds) -gt 1 -or $process.Path -ne $record.executable) {
        Write-Verbose "Stale process record for $Name; this process will not be stopped or reused."
        return $null
    }
    return $process
}

function Wait-Http([string]$Url, [int]$TimeoutSeconds = 60) {
    $deadline = [datetime]::UtcNow.AddSeconds($TimeoutSeconds)
    while ([datetime]::UtcNow -lt $deadline) {
        try {
            $result = Invoke-WebRequest -Uri $Url -UseBasicParsing -TimeoutSec 3
            if ($result.StatusCode -eq 200) { return }
        } catch { }
        Start-Sleep -Milliseconds 500
    }
    throw "Service did not become ready: $Url. See .local/logs."
}
