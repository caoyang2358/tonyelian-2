-- T02/T03 契约：质保与维保提前量默认各 30 天。
-- p2_reminder_setting 是单行配置表（CHECK setting_id = 1），V3 建表时没有写入初始行。
-- 首次读取会拿到空表，页面就只能靠内存默认值，与"返回真实维护资料"的约定不符。
-- 这里补一行初始配置；WHERE NOT EXISTS 让本脚本对已有数据的库保持幂等。
INSERT INTO p2_reminder_setting (setting_id, warranty_lead_days, maintenance_lead_days, version, updated_at)
SELECT 1, 30, 30, 1, NOW(3) FROM DUAL
WHERE NOT EXISTS (SELECT 1 FROM p2_reminder_setting WHERE setting_id = 1);
