param([switch]$KeepDatabase)
. "$PSScriptRoot/Common.ps1"
foreach ($name in @('frontend','backend')) {
    $owned = Get-OwnedProcess $name
    if ($owned) {
        Stop-Process -Id $owned.Id -ErrorAction Stop
        [void]$owned.WaitForExit(10000)
        Remove-Item -LiteralPath (Join-Path $LocalRoot "$name.process.json")
        Write-Host "Stopped owned $name process."
    }
}
if (-not $KeepDatabase) {
    $owned = Get-OwnedProcess 'mysql'
    if ($owned) {
        # Administrative credentials exist only for the isolated project-owned instance.
        $mysqlAlias = Get-MySqlLocalAlias
        $adminPath = Join-Path $mysqlAlias 'database-admin.cnf'
        $expectedData = [System.IO.Path]::GetFullPath((Join-Path $mysqlAlias 'mysql-data'))
        $actualData = Invoke-MySql $adminPath 'SELECT @@datadir;'
        if ([System.IO.Path]::GetFullPath($actualData.TrimEnd('\','/')) -ne $expectedData) { throw 'Refusing to shut down an unrecognized database.' }
        [void](Invoke-MySql $adminPath 'SHUTDOWN;')
        [void]$owned.WaitForExit(10000)
        Remove-Item -LiteralPath (Join-Path $LocalRoot 'mysql.process.json')
        Write-Host 'Stopped project-owned MySQL. Existing MySQL services are untouched; local data is retained.'
    }
}
