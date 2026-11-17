-- 种子：演示工序（dim_process 原为 0 行，设备档案的 process_id 有外键无法直接写）。
-- 口径与 docs/数据来源说明.md 第 6 节一致：车间1 铜杆生产四道工序、车间2 铜丝拉制两道。
-- 回滚：DELETE FROM dim_process WHERE process_code LIKE 'PC-%';
INSERT INTO dim_process (process_code, process_name, workshop_id, sequence_no, description, enabled) VALUES
('PC-RONG',    '熔炼',     1, 1, '阴极铜熔化与成分调整', 1),
('PC-BAOWEN',  '保温精炼', 1, 2, '铜液保温静置与除气精炼', 1),
('PC-LIANZHU', '连铸牵引', 1, 3, '铜杆连铸与牵引成型', 1),
('PC-ZHAZHI',  '轧制',     1, 4, '铜杆轧制成材', 1),
('PC-LASI',    '拉丝',     2, 1, '铜杆拉制成铜丝', 1),
('PC-JIANCE',  '在线检测', 2, 2, '铜丝线径在线检测', 1);
