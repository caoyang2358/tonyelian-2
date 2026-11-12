<#
.SYNOPSIS
    手工补一条异常事件（演示/联调用，直连本地开发库）。

.DESCRIPTION
    本系统没有「新增异常事件」的界面入口：异常事件是采集侧的事实，
    由实时读数越过阈值产生，页面只负责处理、报修和留痕。要演示或补数据，
    只能从数据侧造——这个脚本就是把造数据的步骤固定下来。

    为什么不能只插一条事件：
      p2_alert_event.rule_version_id 是非空外键，指向
      (parameter_id, rule_version_id)，且唯一索引 uk_p2_alert_active_parameter
      限制同一参数只能有一条未结束事件。脚本会自动补齐规则版本，
      并在该参数已有进行中事件时提示（加 -EndPrevious 才结束旧的）。

.EXAMPLE
    # 给 1#熔化炉炉膛底部（TEMP-003）补一条 97.2℃ 的进行中事件
    powershell -ExecutionPolicy Bypass -File .\scripts\Add-AlertEvent.ps1 -SensorCode TEMP-003 -Value 97.2

.EXAMPLE
    # 只打印将要执行的 SQL，不写库
    .\scripts\Add-AlertEvent.ps1 -SensorCode TEMP-003 -Value 97.2 -DryRun

.EXAMPLE
    # 先结束该参数上未结束的事件，再插新的
    .\scripts\Add-AlertEvent.ps1 -SensorCode TEMP-001 -Value 99.1 -EndPrevious
#>
[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)][string]$SensorCode,
    [string]$ParameterCode = '',
    [Parameter(Mandatory = $true)][decimal]$Value,
    [string]$StartedAt = '',
    [string]$TriggeredAt = '',
    [string]$Operator = '系统管理员',
    # 来源：MANUAL（默认，界面显示「人工补录」）或 REALTIME（模拟采集判定，用于演示自动报警）
    [ValidateSet('MANUAL', 'REALTIME')][string]$Source = 'MANUAL',
    # 阈值不填就沿用该参数上一条规则版本（再退化到设备档案），没有就留空。
    [Nullable[decimal]]$AlarmLower = $null,
    [Nullable[decimal]]$AlarmUpper = $null,
    [Nullable[decimal]]$NormalLower = $null,
    [Nullable[decimal]]$NormalUpper = $null,
    [switch]$EndPrevious,
    [switch]$DryRun
)

$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path $PSScriptRoot -Parent
$utf8 = New-Object System.Text.UTF8Encoding($false)

function Read-DotEnv([string]$Path) {
    if (-not (Test-Path -LiteralPath $Path)) { throw "找不到配置文件：$Path" }
    $map = @{}
    foreach ($line in [System.IO.File]::ReadAllLines($Path)) {
        if ($line -match '^\s*(#|$)') { continue }
        if ($line -notmatch '^([A-Za-z_][A-Za-z0-9_]*)\s*=\s*(.*)$') { continue }
        $map[$matches[1].ToUpperInvariant()] = $matches[2].Trim()
    }
    foreach ($key in @('DB_HOST', 'DB_PORT', 'DB_NAME', 'DB_USER', 'DB_PASSWORD')) {
        if ([string]::IsNullOrWhiteSpace($map[$key])) { throw "配置缺少 $key：$Path" }
    }
    return $map
}

function Get-MysqlClient {
    $command = Get-Command 'mysql.exe' -ErrorAction SilentlyContinue
    if ($command) { return $command.Source }
    $candidates = @()
    if ($env:MYSQL_HOME) { $candidates += (Join-Path $env:MYSQL_HOME 'bin/mysql.exe') }
    $candidates += 'C:\mysql-8.0.42-winx64\bin\mysql.exe'
    $candidates += (Join-Path $env:ProgramFiles 'MySQL/MySQL Server 8.0/bin/mysql.exe')
    foreach ($candidate in $candidates) {
        if ($candidate -and (Test-Path -LiteralPath $candidate)) { return $candidate }
    }
    throw '找不到 mysql.exe，请安装 MySQL 8 客户端，或用 MYSQL_HOME 指向安装目录。'
}

$config = Read-DotEnv (Join-Path $projectRoot '.env')
$mysqlClient = Get-MysqlClient

function Invoke-Sql([string]$Sql) {
    $info = New-Object System.Diagnostics.ProcessStartInfo
    $info.FileName = $mysqlClient
    $info.Arguments = "--host=$($config['DB_HOST']) --port=$($config['DB_PORT']) " +
                      "--user=$($config['DB_USER']) --database=$($config['DB_NAME']) " +
                      '--default-character-set=utf8mb4 --batch --raw --skip-column-names'
    $info.UseShellExecute = $false
    $info.CreateNoWindow = $true
    $info.RedirectStandardInput = $true
    $info.RedirectStandardOutput = $true
    $info.RedirectStandardError = $true
    $info.StandardOutputEncoding = $utf8
    $info.StandardErrorEncoding = $utf8
    $process = New-Object System.Diagnostics.Process
    $process.StartInfo = $info
    # Windows PowerShell 5.1 下 ProcessStartInfo.EnvironmentVariables 取不到实例，
    # 改走进程环境变量：子进程启动时继承，口令不会出现在命令行里。
    $previousPassword = $env:MYSQL_PWD
    $env:MYSQL_PWD = $config['DB_PASSWORD']
    try {
        [void]$process.Start()
    } finally {
        $env:MYSQL_PWD = $previousPassword
    }
    $stdout = $process.StandardOutput.ReadToEndAsync()
    $stderr = $process.StandardError.ReadToEndAsync()
    $bytes = $utf8.GetBytes($Sql)
    $process.StandardInput.BaseStream.Write($bytes, 0, $bytes.Length)
    $process.StandardInput.Close()
    if (-not $process.WaitForExit(30000)) { $process.Kill(); throw 'MySQL 命令超时。' }
    $out = $stdout.GetAwaiter().GetResult()
    $err = $stderr.GetAwaiter().GetResult()
    if ($process.ExitCode -ne 0) {
        $safe = $err -replace '(?i)(password[=: ]+)\S+', '$1[已隐藏]'
        throw "数据库执行失败：$safe"
    }
    return $out.Trim()
}

function ConvertTo-SqlText([string]$Text) {
    return "'" + $Text.Replace('\', '\\').Replace("'", "''") + "'"
}

function Format-SqlTime([datetime]$Time) {
    return $Time.ToString('yyyy-MM-dd HH:mm:ss.fff')
}

# ---------- 1. 解析输入时间 ----------
$culture = [System.Globalization.CultureInfo]::InvariantCulture
if ($TriggeredAt) {
    $triggered = [datetime]::Parse($TriggeredAt, $culture)
} else {
    $triggered = Get-Date
}
if ($StartedAt) {
    $started = [datetime]::Parse($StartedAt, $culture)
} else {
    $started = $triggered.AddMinutes(-10)
}
if ($triggered -lt $started) { throw '触发时间不能早于起始时间（表上有 CHECK 约束）。' }

# ---------- 2. 定位测点与参数 ----------
$sensorLiteral = ConvertTo-SqlText $SensorCode.Trim()
$filter = ''
if ($ParameterCode) { $filter = ' AND p.parameter_code = ' + (ConvertTo-SqlText $ParameterCode.Trim()) }

$resolveSql = @"
SELECT s.sensor_id, s.equipment_id, e.equipment_name, p.parameter_id, p.parameter_code,
       p.parameter_name, COALESCE(p.unit_code, ''), COALESCE(p.alarm_lower_limit, ''),
       COALESCE(p.alarm_upper_limit, ''), COALESCE(p.normal_lower_limit, ''),
       COALESCE(p.normal_upper_limit, '')
  FROM dim_sensor s
  JOIN dim_sensor_parameter p ON p.sensor_id = s.sensor_id
  JOIN dim_equipment e ON e.equipment_id = s.equipment_id
 WHERE s.sensor_code = $sensorLiteral$filter
 ORDER BY p.parameter_id;
"@

$rows = @(Invoke-Sql $resolveSql | Where-Object { $_ -ne '' })
if ($rows.Count -eq 0) {
    $known = Invoke-Sql "SELECT CONCAT(s.sensor_code, ' / ', p.parameter_code, ' / ', p.parameter_name) FROM dim_sensor s JOIN dim_sensor_parameter p ON p.sensor_id = s.sensor_id ORDER BY s.sensor_id;"
    throw "找不到测点 $SensorCode$(if ($ParameterCode) { ' / 参数 ' + $ParameterCode })。可用组合：`n$known"
}
if ($rows.Count -gt 1) {
    $list = ($rows | ForEach-Object { ($_ -split "`t")[3..5] -join ' / ' }) -join "`n"
    throw "测点 $SensorCode 有多个参数，请用 -ParameterCode 指定：`n$list"
}

$cols = $rows[0] -split "`t"
$sensorId, $equipmentId, $equipmentName, $parameterId, $parameterCodeDb, $parameterName = $cols[0..5]
$unitCode, $dimAlarmLower, $dimAlarmUpper, $dimNormalLower, $dimNormalUpper = $cols[6..10]

Write-Host "测点：$SensorCode（$equipmentName）"
Write-Host "参数：#$parameterId $parameterName（$parameterCodeDb），单位 $unitCode"
Write-Host "本次数值：$Value $unitCode，起始 $(Format-SqlTime $started)，触发 $(Format-SqlTime $triggered)"

if ($dimAlarmUpper -and $Value -le [decimal]$dimAlarmUpper -and (-not $dimAlarmLower -or $Value -ge [decimal]$dimAlarmLower)) {
    Write-Warning "该参数设备档案里的报警阈值为 $dimAlarmLower ~ $dimAlarmUpper，$Value 并未超限（真正生效的是规则版本里的阈值，下面会打印最终取值）。"
}

# ---------- 3. 检查是否已有进行中事件 ----------
$activeSql = "SELECT event_id, triggered_at FROM p2_alert_event WHERE parameter_id = $parameterId AND ended_at IS NULL;"
$active = Invoke-Sql $activeSql
if ($active) {
    $activeId = ($active -split "`t")[0]
    if (-not $EndPrevious) {
        throw "参数 #$parameterId 上还有未结束的事件 #$activeId（同一参数同时只能有一条）。`n加 -EndPrevious 会先把它按「自动恢复」结束，再写入新事件。"
    }
    Write-Host "先结束进行中事件 #$activeId（按自动恢复处理）"
    $closeSql = @"
UPDATE p2_alert_event
   SET ended_at = NOW(3), recovered_at = NOW(3), end_reason = 'RECOVERED',
       version = version + 1, updated_at = NOW(3)
 WHERE event_id = $activeId AND ended_at IS NULL;
"@
    if (-not $DryRun) { [void](Invoke-Sql $closeSql) } else { Write-Host $closeSql }
}

# ---------- 4. 规则版本 + 事件 ----------
# 阈值取值优先级：命令行显式给出 > 该参数最近一条规则版本 > 设备档案里的参数阈值。
# 三者都没有就写 NULL，页面上阈值会显示「—」，演示时容易让人以为判据丢了。
# 用单列 + 竖线拼接取回四个阈值：多列时若首列为空，Trim() 会吃掉开头的制表符，
# 后面的值就整体左移一格（正常/报警互串），单列不会。
$lastRule = Invoke-Sql ("SELECT CONCAT(COALESCE(alarm_lower, ''), '|', COALESCE(alarm_upper, ''), '|'," +
        " COALESCE(normal_lower, ''), '|', COALESCE(normal_upper, ''))" +
        " FROM p2_alert_rule_version WHERE parameter_id = $parameterId ORDER BY rule_version DESC LIMIT 1;")
$ruleAlarmLower = ''; $ruleAlarmUpper = ''; $ruleNormalLower = ''; $ruleNormalUpper = ''
if ($lastRule) {
    $lastCols = $lastRule -split '\|'
    $ruleAlarmLower = $lastCols[0]; $ruleAlarmUpper = $lastCols[1]
    $ruleNormalLower = $lastCols[2]; $ruleNormalUpper = $lastCols[3]
}

function Resolve-Limit($Explicit, [string]$FromRule, [string]$FromDim) {
    if ($null -ne $Explicit) { return [string]$Explicit }
    if ($FromRule) { return $FromRule }
    if ($FromDim) { return $FromDim }
    return 'NULL'
}

$normalLowerSql = Resolve-Limit $NormalLower $ruleNormalLower $dimNormalLower
$normalUpperSql = Resolve-Limit $NormalUpper $ruleNormalUpper $dimNormalUpper
$alarmLowerSql = Resolve-Limit $AlarmLower $ruleAlarmLower $dimAlarmLower
$alarmUpperSql = Resolve-Limit $AlarmUpper $ruleAlarmUpper $dimAlarmUpper
$unitSql = if ($unitCode) { ConvertTo-SqlText $unitCode } else { 'NULL' }

# 来源写进 source_reference.type：页面用这个字段区分「自动采集」与「人工补录」。
# 脚本默认按人工补录记，这样列表上不会把脚本造的数据当成自动报警。
$reference = if ($Source -eq 'MANUAL') {
    [ordered]@{ type = 'MANUAL'; operator = $Operator; reason = '命令行补录（演示数据）' } | ConvertTo-Json -Compress
} else {
    [ordered]@{ type = 'REALTIME'; window = 'PT1M'; sampleCount = 9 } | ConvertTo-Json -Compress
}
$referenceSql = ConvertTo-SqlText $reference

Write-Host "规则阈值：正常 $normalLowerSql ~ $normalUpperSql，报警 $alarmLowerSql ~ $alarmUpperSql"
if ($alarmUpperSql -eq 'NULL' -and $alarmLowerSql -eq 'NULL') {
    Write-Warning '该参数既没有历史规则版本、设备档案里也没配阈值，本次规则的报警上下限为空，页面上「报警阈值」会显示「—」。想让它看起来完整，加 -AlarmUpper 95 这类参数重跑。'
}

$ruleSql = @"
INSERT INTO p2_alert_rule_version
    (parameter_id, rule_version, previous_rule_version_id,
     normal_lower, normal_upper, alarm_lower, alarm_upper,
     unit_code, trigger_mode, duration_seconds, enabled, effective_at, reason, operator_name)
SELECT $parameterId,
       COALESCE(MAX(rule_version), 0) + 1, NULL,
       $normalLowerSql, $normalUpperSql, $alarmLowerSql, $alarmUpperSql,
       $unitSql, 'IMMEDIATE', NULL, 1,
       $(ConvertTo-SqlText (Format-SqlTime $started)), '演示事件录入', $(ConvertTo-SqlText $Operator)
  FROM p2_alert_rule_version
 WHERE parameter_id = $parameterId;
"@

$eventSql = @"
INSERT INTO p2_alert_event
    (equipment_id, sensor_id, parameter_id, rule_version_id,
     started_at, triggered_at, trigger_value, unit_code, source_reference, version, updated_at)
SELECT $equipmentId, $sensorId, $parameterId, r.rule_version_id,
       $(ConvertTo-SqlText (Format-SqlTime $started)), $(ConvertTo-SqlText (Format-SqlTime $triggered)),
       $Value, $unitSql,
       $referenceSql, 1,
       $(ConvertTo-SqlText (Format-SqlTime $triggered))
  FROM p2_alert_rule_version r
 WHERE r.parameter_id = $parameterId
 ORDER BY r.rule_version DESC
 LIMIT 1;
"@

if ($DryRun) {
    Write-Host ''
    Write-Host '--- DryRun：以下 SQL 未执行 ---'
    Write-Host $ruleSql
    Write-Host $eventSql
    return
}

[void](Invoke-Sql $ruleSql)
[void](Invoke-Sql $eventSql)

$resultSql = @"
SELECT e.event_id, p.parameter_name, e.trigger_value, COALESCE(e.unit_code, ''),
       DATE_FORMAT(e.started_at, '%Y-%m-%d %H:%i:%s'),
       IF(e.ended_at IS NULL, '进行中', '已结束'),
       COALESCE(e.end_reason, '')
  FROM p2_alert_event e
  JOIN dim_sensor_parameter p ON p.parameter_id = e.parameter_id
 WHERE e.parameter_id = $parameterId
 ORDER BY e.event_id DESC
 LIMIT 1;
"@
$line = (Invoke-Sql $resultSql) -split "`t"
Write-Host ''
Write-Host "已写入事件 #$($line[0])：$($line[1]) $($line[2])$($line[3])，起始 $($line[4])，$($line[5])"
Write-Host '刷新运维页面即可看到；未结束 + 未处理的事件会显示为「紧急 / 进行中 / 待处理」。'
