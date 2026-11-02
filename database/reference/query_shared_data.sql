-- 一期已有表只读查询示例。先选择开发库；源码结构需与实际结构核对。
-- 不连接服务器、不建新表、不修改任何数据；数据库连接由开发者自行配置。

-- 1. 生产设备及车间：不把8台当作上限，不复制设备身份。
SELECT e.equipment_id, e.equipment_code, e.equipment_name,
       e.responsible_person, e.enabled, w.workshop_code, w.workshop_name
FROM dim_equipment AS e
JOIN dim_workshop AS w ON w.workshop_id = e.workshop_id
WHERE e.equipment_usage = 'PRODUCTION'
ORDER BY w.workshop_code, e.equipment_code;

-- 2. 指定测点的参数、最新值及质量。TEMP-002为源码中的演示编码，可替换。
SELECT s.sensor_id, s.sensor_code, s.sensor_name,
       p.parameter_id, p.parameter_code, p.parameter_name,
       p.unit_code, p.decimal_places,
       a.latest_value, a.latest_value_text, a.quality_status,
       a.limit_status, a.latest_collect_time
FROM dim_sensor AS s
JOIN dim_sensor_parameter AS p ON p.sensor_id = s.sensor_id
LEFT JOIN ads_parameter_monitor AS a
  ON a.sensor_id = p.sensor_id AND a.parameter_id = p.parameter_id
WHERE s.sensor_code = 'TEMP-002'
ORDER BY p.parameter_code;

-- 3. 车间一总表日消耗。日期为查询示例，需按已有演示数据替换。
-- 不读取production_quantity当订单产量，不读取旧cost当订单成本。
SELECT s.sensor_id, s.sensor_code, t.energy_code,
       d.statistic_date, d.consumption, d.quality_status,
       d.calculation_method, d.refresh_time
FROM dim_sensor AS s
JOIN dim_energy_type AS t ON t.energy_type_id = s.energy_type_id
LEFT JOIN dws_energy_day AS d
  ON d.sensor_id = s.sensor_id
 AND d.statistic_date BETWEEN '2026-11-01' AND '2026-11-02'
WHERE s.sensor_code IN ('EM-E-ROD','EM-W-ROD','EM-G-ROD')
ORDER BY s.sensor_code, d.statistic_date;

-- LEFT JOIN保留测点存在而所选日期无数据的情况；空值需标缺失，不自动转零。
