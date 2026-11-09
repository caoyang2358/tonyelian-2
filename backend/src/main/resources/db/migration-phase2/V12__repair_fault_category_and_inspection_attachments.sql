-- 老师评审意见（2026-11-09）带来的两处结构扩展：
--   1) 报修工单需要"故障类别"，用于分类统计与派工判断；
--   2) 巡检需要现场照片凭证，因此附件归属新增 INSPECTION 一种。
-- 两处都是加列/放宽约束，对既有数据无破坏：fault_category 允许为空（历史工单显示"未分类"），
-- inspection_id 为空时不影响原四类归属。

ALTER TABLE p2_repair_order
    ADD COLUMN fault_category VARCHAR(32) NULL AFTER supplement,
    ADD CONSTRAINT chk_p2_repair_fault_category CHECK (
        fault_category IS NULL OR fault_category IN
            ('MECHANICAL', 'ELECTRICAL', 'INSTRUMENT', 'PROCESS', 'OTHER')
    );

ALTER TABLE p2_attachment
    ADD COLUMN inspection_id BIGINT NULL AFTER repair_order_id,
    ADD CONSTRAINT fk_p2_attachment_inspection
        FOREIGN KEY (inspection_id) REFERENCES p2_inspection (inspection_id);

-- 原约束要求"四个归属里恰好一个"；加入巡检后改为五个里恰好一个。
-- 约束名由 V3 建表时的首个匿名 CHECK 自动生成，MySQL 8 固定为 p2_attachment_chk_1。
ALTER TABLE p2_attachment DROP CHECK p2_attachment_chk_1;

ALTER TABLE p2_attachment
    ADD CONSTRAINT chk_p2_attachment_owner CHECK (
        (equipment_id IS NOT NULL) + (contract_id IS NOT NULL) + (article_id IS NOT NULL)
        + (repair_order_id IS NOT NULL) + (inspection_id IS NOT NULL) = 1
    );
