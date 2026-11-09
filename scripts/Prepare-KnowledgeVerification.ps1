# Prepare labeled metadata only in the project-owned development database.
. "$PSScriptRoot/Common.ps1"
Import-DevelopmentEnvironment
if ($env:DB_NAME -ne 'copper_phase2_dev' -or $env:DB_PORT -ne '13307' -or !(Test-Path (Join-Path $LocalRoot 'mysql-instance.json'))) {
    throw 'Requires the project-owned development database.'
}
$admin = Join-Path (Get-MySqlLocalAlias) 'database-admin.cnf'
$sql = @'
INSERT INTO dim_equipment(equipment_code,equipment_name,workshop_id,enabled)
SELECT 'KNOWLEDGE-VERIFY','Knowledge verification equipment',workshop_id,1 FROM dim_workshop
WHERE NOT EXISTS(SELECT 1 FROM dim_equipment WHERE equipment_code='KNOWLEDGE-VERIFY') LIMIT 1;
INSERT IGNORE INTO p2_equipment_profile(equipment_id,type_code,technical_parameters,created_at,updated_at)
SELECT equipment_id,'OTHER',JSON_ARRAY(),NOW(3),NOW(3) FROM dim_equipment WHERE equipment_code='KNOWLEDGE-VERIFY';
'@
Invoke-MySql $admin $sql $env:DB_NAME
Write-Host 'Knowledge verification fixture is ready.'
