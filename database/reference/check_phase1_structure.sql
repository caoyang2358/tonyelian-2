-- 只读结构核验：先在数据库工具中选中获准核对的库，再执行。
-- 本文件不建表、不删表、不修改数据。输出仅用于核对结构，不代表已自动执行。
SELECT DATABASE() AS current_database,
       VERSION() AS mysql_version,
       @@session.time_zone AS session_time_zone,
       @@system_time_zone AS system_time_zone;

SELECT TABLE_NAME, ENGINE, TABLE_COLLATION
FROM information_schema.TABLES
WHERE TABLE_SCHEMA = DATABASE()
  AND TABLE_NAME IN (
    'dim_workshop','dim_equipment','dim_process','dim_unit',
    'dim_sensor','dim_sensor_parameter','ads_parameter_monitor',
    'ods_sensor_data','dim_energy_type','dws_energy_hour',
    'dws_energy_day','dim_energy_tariff',
    'flyway_schema_history','p2_flyway_schema_history'
  )
ORDER BY TABLE_NAME;

SELECT TABLE_NAME, COLUMN_NAME, COLUMN_TYPE, IS_NULLABLE,
       COLUMN_KEY, EXTRA, COLLATION_NAME
FROM information_schema.COLUMNS
WHERE TABLE_SCHEMA = DATABASE()
  AND TABLE_NAME IN (
    'dim_workshop','dim_equipment','dim_process','dim_unit',
    'dim_sensor','dim_sensor_parameter','ads_parameter_monitor',
    'ods_sensor_data','dim_energy_type','dws_energy_hour',
    'dws_energy_day','dim_energy_tariff'
  )
ORDER BY TABLE_NAME, ORDINAL_POSITION;

SHOW CREATE TABLE dim_workshop;
SHOW CREATE TABLE dim_equipment;
SHOW CREATE TABLE dim_process;
SHOW CREATE TABLE dim_unit;
SHOW CREATE TABLE dim_sensor;
SHOW CREATE TABLE dim_sensor_parameter;
SHOW CREATE TABLE ads_parameter_monitor;
SHOW CREATE TABLE ods_sensor_data;
SHOW CREATE TABLE dim_energy_type;
SHOW CREATE TABLE dws_energy_hour;
SHOW CREATE TABLE dws_energy_day;
SHOW CREATE TABLE dim_energy_tariff;
