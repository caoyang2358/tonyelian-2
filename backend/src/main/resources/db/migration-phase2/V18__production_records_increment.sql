-- 生产记录模块增量迁移：计划外归集组、任务称重份额、日录全状态，以及既有事实表的可空任务关联。
-- 原 V5 迁移保持原样；本迁移只做加列、加表与约束放宽，不重写历史结构。
-- 注意：新 CHECK 引用的列必须先加列再换约束，否则会以 1054 失败。

-- 1. 计划外生产归集组：同一实际过程的关联身份，事实仍以行为单位过账。
CREATE TABLE p2_production_group (
    production_group_id BIGINT NOT NULL AUTO_INCREMENT,
    origin VARCHAR(24) NOT NULL,
    workshop_id BIGINT NOT NULL,
    material_id BIGINT NOT NULL,
    anchor_actual_date DATE NOT NULL,
    operator_name VARCHAR(100) NOT NULL,
    reason VARCHAR(1000) NOT NULL,
    recorded_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    version BIGINT NOT NULL DEFAULT 0,
    PRIMARY KEY (production_group_id),
    CONSTRAINT fk_p2_group_workshop FOREIGN KEY (workshop_id) REFERENCES dim_workshop (workshop_id),
    CONSTRAINT fk_p2_group_material FOREIGN KEY (material_id) REFERENCES p2_material (material_id),
    CONSTRAINT ck_p2_group_origin CHECK (origin IN ('PLANNED', 'UNPLANNED')),
    CONSTRAINT ck_p2_group_version CHECK (version >= 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- 2. 铜板投料：任务改为可空，计划外补录必须落到归集组。
ALTER TABLE p2_plate_input
    MODIFY task_id BIGINT NULL,
    ADD COLUMN production_group_id BIGINT NULL AFTER production_group_key,
    ADD CONSTRAINT fk_p2_input_group FOREIGN KEY (production_group_id)
        REFERENCES p2_production_group (production_group_id),
    ADD CONSTRAINT ck_p2_input_owner CHECK (task_id IS NOT NULL OR production_group_id IS NOT NULL),
    ADD KEY ix_p2_input_group_id (production_group_id);

-- 3. 领杆交接：同样允许计划外组，车间与规格沿组取得。
ALTER TABLE p2_rod_handover
    MODIFY task_id BIGINT NULL,
    ADD COLUMN production_group_id BIGINT NULL AFTER receiving_references,
    ADD CONSTRAINT fk_p2_handover_group FOREIGN KEY (production_group_id)
        REFERENCES p2_production_group (production_group_id),
    ADD CONSTRAINT ck_p2_handover_owner CHECK (task_id IS NOT NULL OR production_group_id IS NOT NULL),
    ADD KEY ix_p2_handover_group_id (production_group_id);

-- 4. 产出事实：先加列，再放宽 EXECUTION 分支约束（允许计划外执行沿归集组登记），
--    最后补归集组外键与 NO_OUTPUT 作废标记。
ALTER TABLE p2_output_record
    ADD COLUMN production_group_id BIGINT NULL AFTER production_group_key,
    ADD COLUMN voided_at DATETIME(3) NULL AFTER version,
    ADD CONSTRAINT fk_p2_output_group FOREIGN KEY (production_group_id)
        REFERENCES p2_production_group (production_group_id),
    ADD CONSTRAINT ck_p2_output_voided_scope CHECK (voided_at IS NULL OR record_type = 'NO_OUTPUT'),
    ADD KEY ix_p2_output_group_id (production_group_id);
ALTER TABLE p2_output_record
    DROP CHECK p2_output_record_chk_1;
ALTER TABLE p2_output_record
    ADD CONSTRAINT ck_p2_output_facts CHECK (
        (record_type = 'QUALIFIED_OUTPUT' AND material_id IS NOT NULL
            AND movement_id IS NOT NULL AND qualified_quantity_ton IS NOT NULL
            AND qualified_quantity_ton > 0
            AND (task_id IS NOT NULL OR production_group_id IS NOT NULL
                 OR production_group_key IS NOT NULL))
        OR (record_type = 'NO_OUTPUT' AND movement_id IS NULL
            AND qualified_quantity_ton IS NOT NULL AND qualified_quantity_ton = 0)
        OR (record_type = 'EXECUTION' AND movement_id IS NULL
            AND qualified_quantity_ton IS NULL
            AND (task_id IS NOT NULL OR production_group_id IS NOT NULL)
            AND (started_at IS NOT NULL OR stopped_at IS NOT NULL)));

-- 5. 跨任务共用称重的任务份额：产出 ID 与任务 ID 唯一，份额之和受服务端校验。
CREATE TABLE p2_output_task_share (
    output_task_share_id BIGINT NOT NULL AUTO_INCREMENT,
    output_id BIGINT NOT NULL,
    task_id BIGINT NOT NULL,
    qualified_quantity_ton DECIMAL(18,3) NOT NULL,
    version BIGINT NOT NULL DEFAULT 0,
    PRIMARY KEY (output_task_share_id),
    UNIQUE KEY uk_p2_output_task_share (output_id, task_id),
    KEY ix_p2_output_task_share_task (task_id),
    CONSTRAINT fk_p2_output_task_share_output FOREIGN KEY (output_id)
        REFERENCES p2_output_record (output_id),
    CONSTRAINT fk_p2_output_task_share_task FOREIGN KEY (task_id)
        REFERENCES p2_plan_task (task_id),
    CONSTRAINT ck_p2_output_task_share_qty CHECK (qualified_quantity_ton > 0 AND version >= 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- 6. 车间日录全状态：事实写入或更正同事务推进来源修订并使确认失效。
CREATE TABLE p2_production_day_status (
    workshop_id BIGINT NOT NULL,
    actual_date DATE NOT NULL,
    source_revision BIGINT NOT NULL DEFAULT 0,
    confirmed_source_revision BIGINT NULL,
    records_complete BOOLEAN NOT NULL DEFAULT FALSE,
    confirmed_by VARCHAR(100) NULL,
    confirmed_at DATETIME(3) NULL,
    version BIGINT NOT NULL DEFAULT 0,
    updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    PRIMARY KEY (workshop_id, actual_date),
    CONSTRAINT fk_p2_day_status_workshop FOREIGN KEY (workshop_id) REFERENCES dim_workshop (workshop_id),
    CONSTRAINT ck_p2_day_status_consistency CHECK (
        (confirmed_source_revision IS NULL AND confirmed_by IS NULL AND confirmed_at IS NULL AND records_complete = FALSE)
        OR (confirmed_source_revision IS NOT NULL AND confirmed_by IS NOT NULL AND confirmed_at IS NOT NULL)
    ),
    CONSTRAINT ck_p2_day_status_version CHECK (version >= 0 AND source_revision >= 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
