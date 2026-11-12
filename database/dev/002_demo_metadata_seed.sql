-- DEVELOPMENT DEMO METADATA ONLY / 仅供隔离空库开发演示。
-- Apply once AFTER 001_phase1_schema_snapshot.sql in the same NEW EMPTY schema.
-- Codes/names/ownership below are copied from phase1 V2 metadata, not field verification.
-- No actual readings, energy meters, tariffs, orders, production, stock, or finished goods.
-- INSERT deliberately fails on duplicate codes; do not use to refresh an existing database.
SET NAMES utf8mb4;

START TRANSACTION;

INSERT INTO dim_workshop
  (workshop_code, workshop_name, workshop_type, description)
VALUES
  ('WS-ROD', '车间1 · 铜杆生产', '熔炼', 'DEV_DEMO：一期编码的开发演示元数据，非现场核验'),
  ('WS-WIRE', '车间2 · 铜丝拉制', '拉丝', 'DEV_DEMO：一期编码的开发演示元数据，非现场核验');

INSERT INTO dim_equipment
  (equipment_code, equipment_name, workshop_id, responsible_person, equipment_usage)
SELECT x.code, x.name, w.workshop_id, NULL, 'PRODUCTION'
FROM dim_workshop w
JOIN (
  SELECT 'WS-ROD' AS workshop_code, 'SMELT-FUR-01' AS code, '1#熔化炉' AS name
  UNION ALL SELECT 'WS-ROD', 'HOLD-FUR-01', '1#保温炉'
  UNION ALL SELECT 'WS-ROD', 'CAST-01', '1#结晶牵引机'
  UNION ALL SELECT 'WS-ROD', 'ROLL-01', '1#多道轧机组'
  UNION ALL SELECT 'WS-WIRE', 'DRAW-01', '1#拉丝机'
  UNION ALL SELECT 'WS-WIRE', 'DRAW-02', '2#拉丝机'
  UNION ALL SELECT 'WS-WIRE', 'DRAW-03', '3#拉丝机'
  UNION ALL SELECT 'WS-WIRE', 'GAUGE-02', '2#测径仪'
) x ON x.workshop_code = w.workshop_code;

-- Sensor parameter units only; these rows are definitions, not measurement values.
INSERT INTO dim_unit
  (unit_code, unit_name, unit_category, base_unit_code, conversion_factor)
VALUES
  ('C', '摄氏度', 'TEMPERATURE', 'C', 1),
  ('Pa', '帕', 'PRESSURE', 'Pa', 1),
  ('PCT', '百分比', 'RATIO', 'PCT', 1),
  ('M_PER_MIN', '米每分钟', 'SPEED', 'M_PER_MIN', 1),
  ('M_PER_SEC', '米每秒', 'SPEED', 'M_PER_MIN', 60),
  ('N', '牛', 'FORCE', 'N', 1),
  ('MM', '毫米', 'LENGTH', 'MM', 1);

-- Preserve phase1 source ownership. In particular TEMP-003/LEVEL-001 stay on SMELT-FUR-01.
-- The phase1 process-display mapping is NOT evidence for moving these sensors.
INSERT INTO dim_sensor
  (sensor_code, sensor_name, equipment_id, sensor_type, data_source)
SELECT x.code, x.name, e.equipment_id, x.sensor_type, 'DEV_DEMO_METADATA'
FROM dim_equipment e
JOIN (
  SELECT 'SMELT-FUR-01' AS equipment_code, 'TEMP-001' AS code, '炉膛上部温度' AS name, '温度' AS sensor_type
  UNION ALL SELECT 'SMELT-FUR-01', 'TEMP-002', '炉膛中部温度', '温度'
  UNION ALL SELECT 'SMELT-FUR-01', 'TEMP-003', '炉膛底部温度', '温度'
  UNION ALL SELECT 'SMELT-FUR-01', 'PRESS-001', '炉压', '压力'
  UNION ALL SELECT 'SMELT-FUR-01', 'LEVEL-001', '铜液液位', '液位'
  UNION ALL SELECT 'CAST-01', 'SPEED-001', '牵引速度', '速度'
  UNION ALL SELECT 'ROLL-01', 'DIAM-ROD-001', '轧制出口线径', '线径'
  UNION ALL SELECT 'DRAW-03', 'SPEED-003', '拉丝线速度', '速度'
  UNION ALL SELECT 'DRAW-03', 'TENSION-003', '拉丝张力', '张力'
  UNION ALL SELECT 'GAUGE-02', 'DIAM-WIRE-002', '出线线径', '线径'
  -- 补齐保温炉与 1#/2# 拉丝机的测点：原先三台设备一个测点都没有，
  -- 事件必须挂在参数上，导致这三台设备既报不了参数异常、也补录不了事件。
  -- 测点按同类设备同构配置（保温炉比照熔化炉的温度/液位，拉丝机比照 3# 拉丝机）。
  UNION ALL SELECT 'HOLD-FUR-01', 'TEMP-004', '保温温度', '温度'
  UNION ALL SELECT 'HOLD-FUR-01', 'LEVEL-002', '铜液液位', '液位'
  UNION ALL SELECT 'DRAW-01', 'SPEED-004', '拉丝线速度', '速度'
  UNION ALL SELECT 'DRAW-01', 'TENSION-004', '拉丝张力', '张力'
  UNION ALL SELECT 'DRAW-02', 'SPEED-005', '拉丝线速度', '速度'
  UNION ALL SELECT 'DRAW-02', 'TENSION-005', '拉丝张力', '张力'
) x ON x.equipment_code = e.equipment_code;

-- Known parameter identifiers/units/precision are reused. Industrial limits, sampling,
-- and process bindings intentionally remain NULL until configured/verified.
INSERT INTO dim_sensor_parameter
  (sensor_id, parameter_code, parameter_name, data_type, unit_code, decimal_places)
SELECT s.sensor_id, x.parameter_code, x.parameter_name, 'DECIMAL', x.unit_code, x.decimal_places
FROM dim_sensor s
JOIN (
  SELECT 'TEMP-001' AS sensor_code, 'temperature' AS parameter_code, '上部温度' AS parameter_name, 'C' AS unit_code, 0 AS decimal_places
  UNION ALL SELECT 'TEMP-002', 'temperature', '中部温度', 'C', 0
  UNION ALL SELECT 'TEMP-003', 'temperature', '底部温度', 'C', 0
  UNION ALL SELECT 'PRESS-001', 'pressure', '炉压', 'Pa', 0
  UNION ALL SELECT 'LEVEL-001', 'level', '铜液液位', 'PCT', 1
  UNION ALL SELECT 'SPEED-001', 'speed', '牵引速度', 'M_PER_MIN', 1
  UNION ALL SELECT 'DIAM-ROD-001', 'diameter', '铜杆线径', 'MM', 2
  UNION ALL SELECT 'SPEED-003', 'speed', '拉丝线速度', 'M_PER_SEC', 1
  UNION ALL SELECT 'TENSION-003', 'tension', '拉丝张力', 'N', 1
  UNION ALL SELECT 'DIAM-WIRE-002', 'diameter', '出线线径', 'MM', 3
  UNION ALL SELECT 'TEMP-004', 'temperature', '保温温度', 'C', 0
  UNION ALL SELECT 'LEVEL-002', 'level', '铜液液位', 'PCT', 1
  UNION ALL SELECT 'SPEED-004', 'speed', '拉丝线速度', 'M_PER_SEC', 1
  UNION ALL SELECT 'TENSION-004', 'tension', '拉丝张力', 'N', 1
  UNION ALL SELECT 'SPEED-005', 'speed', '拉丝线速度', 'M_PER_SEC', 1
  UNION ALL SELECT 'TENSION-005', 'tension', '拉丝张力', 'N', 1
) x ON x.sensor_code = s.sensor_code;

COMMIT;
