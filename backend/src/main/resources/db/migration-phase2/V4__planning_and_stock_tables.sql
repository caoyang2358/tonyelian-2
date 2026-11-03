-- 二期订单、已确认计划、库存及未来供料。草稿保留在浏览器，确认事务才写入。
CREATE TABLE p2_material (
    material_id BIGINT NOT NULL AUTO_INCREMENT,
    material_code VARCHAR(64) NOT NULL,
    material_name VARCHAR(128) NOT NULL,
    material_type VARCHAR(24) NOT NULL,
    specification VARCHAR(100) NOT NULL,
    enabled BOOLEAN NOT NULL DEFAULT TRUE,
    version BIGINT NOT NULL DEFAULT 0,
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    PRIMARY KEY (material_id),
    UNIQUE KEY uk_p2_material_code (material_code),
    UNIQUE KEY uk_p2_material_spec (material_type, specification),
    CONSTRAINT ck_p2_material_type_spec CHECK (
        (material_type = 'COPPER_PLATE' AND specification = '')
        OR (material_type = 'COPPER_ROD' AND specification = 'Φ8')
        OR (material_type = 'FINISHED_WIRE' AND CHAR_LENGTH(TRIM(specification)) > 0)
    ),
    CONSTRAINT ck_p2_material_version CHECK (version >= 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE p2_customer_order (
    order_id BIGINT NOT NULL AUTO_INCREMENT,
    order_no VARCHAR(64) NOT NULL,
    customer_name VARCHAR(160) NOT NULL,
    wire_material_id BIGINT NOT NULL,
    required_quantity_ton DECIMAL(18,3) NOT NULL,
    due_date DATE NOT NULL,
    manual_planned_rod_ton DECIMAL(18,3),
    production_scope_version BIGINT NOT NULL DEFAULT 0,
    rod_demand_scope JSON,
    contract_amount_yuan DECIMAL(20,2),
    agreed_amount_yuan DECIMAL(20,2),
    amount_confirmed BOOLEAN NOT NULL DEFAULT FALSE,
    manual_priority BOOLEAN NOT NULL DEFAULT FALSE,
    production_result VARCHAR(32) NOT NULL DEFAULT 'PENDING',
    delivery_result VARCHAR(32) NOT NULL DEFAULT 'PENDING',
    accounting_state VARCHAR(32) NOT NULL DEFAULT 'PENDING',
    completion_reason VARCHAR(1000),
    completed_at DATETIME(3),
    cancellation_reason VARCHAR(1000),
    cancelled_at DATETIME(3),
    operator_name VARCHAR(100) NOT NULL,
    version BIGINT NOT NULL DEFAULT 0,
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    PRIMARY KEY (order_id),
    UNIQUE KEY uk_p2_order_no (order_no),
    KEY ix_p2_order_due (due_date, created_at, order_id),
    CONSTRAINT fk_p2_order_material FOREIGN KEY (wire_material_id) REFERENCES p2_material (material_id),
    CONSTRAINT ck_p2_order_qty CHECK (required_quantity_ton > 0 AND (manual_planned_rod_ton IS NULL OR manual_planned_rod_ton >= 0)),
    CONSTRAINT ck_p2_order_amount CHECK (
        (contract_amount_yuan IS NULL OR contract_amount_yuan >= 0)
        AND (agreed_amount_yuan IS NULL OR agreed_amount_yuan >= 0)
        AND (NOT amount_confirmed OR COALESCE(agreed_amount_yuan, contract_amount_yuan) IS NOT NULL)
    ),
    CONSTRAINT ck_p2_order_versions CHECK (version >= 0 AND production_scope_version >= 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE p2_workshop_capacity (
    workshop_id BIGINT NOT NULL,
    max_daily_output_ton DECIMAL(18,3) NOT NULL DEFAULT 10.000,
    version BIGINT NOT NULL DEFAULT 0,
    updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    PRIMARY KEY (workshop_id),
    CONSTRAINT fk_p2_capacity_workshop FOREIGN KEY (workshop_id) REFERENCES dim_workshop (workshop_id),
    CONSTRAINT ck_p2_capacity_value CHECK (max_daily_output_ton > 0 AND version >= 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE p2_capacity_override (
    capacity_override_id BIGINT NOT NULL AUTO_INCREMENT,
    workshop_id BIGINT NOT NULL,
    capacity_date DATE NOT NULL,
    max_daily_output_ton DECIMAL(18,3) NOT NULL,
    active BOOLEAN NOT NULL DEFAULT TRUE,
    reason VARCHAR(1000) NOT NULL,
    operator_name VARCHAR(100) NOT NULL,
    version BIGINT NOT NULL DEFAULT 0,
    updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    PRIMARY KEY (capacity_override_id),
    UNIQUE KEY uk_p2_capacity_day (workshop_id, capacity_date),
    CONSTRAINT fk_p2_capacity_override_workshop FOREIGN KEY (workshop_id) REFERENCES dim_workshop (workshop_id),
    CONSTRAINT ck_p2_capacity_override_value CHECK (max_daily_output_ton >= 0 AND version >= 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE p2_plan (
    plan_id BIGINT NOT NULL AUTO_INCREMENT,
    previous_plan_id BIGINT,
    status VARCHAR(24) NOT NULL DEFAULT 'ACTIVE',
    request_id VARCHAR(128) NOT NULL,
    client_draft_id VARCHAR(128),
    confirmed_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    operator_name VARCHAR(100) NOT NULL,
    reason VARCHAR(1000) NOT NULL,
    -- 确认采用的人工总用杆量、范围、净补产和备料依据。
    demand_snapshot JSON NOT NULL,
    version BIGINT NOT NULL DEFAULT 0,
    PRIMARY KEY (plan_id),
    UNIQUE KEY uk_p2_plan_request (request_id),
    CONSTRAINT fk_p2_plan_previous FOREIGN KEY (previous_plan_id) REFERENCES p2_plan (plan_id),
    CONSTRAINT ck_p2_plan_status CHECK (status IN ('ACTIVE', 'SUPERSEDED', 'CANCELLED')),
    CONSTRAINT ck_p2_plan_version CHECK (version >= 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE p2_plan_task (
    task_id BIGINT NOT NULL AUTO_INCREMENT,
    plan_id BIGINT NOT NULL,
    workshop_id BIGINT NOT NULL,
    material_id BIGINT NOT NULL,
    production_date DATE NOT NULL,
    sequence_no INT NOT NULL,
    planned_qualified_ton DECIMAL(18,3) NOT NULL,
    planned_rod_use_ton DECIMAL(18,3),
    planned_plate_ton DECIMAL(18,3),
    status VARCHAR(24) NOT NULL DEFAULT 'PLANNED',
    superseded_by_plan_id BIGINT,
    version BIGINT NOT NULL DEFAULT 0,
    PRIMARY KEY (task_id),
    UNIQUE KEY uk_p2_task_plan_slot (plan_id, workshop_id, production_date, sequence_no),
    KEY ix_p2_task_workshop_day (workshop_id, production_date, status, sequence_no),
    CONSTRAINT fk_p2_task_plan FOREIGN KEY (plan_id) REFERENCES p2_plan (plan_id),
    CONSTRAINT fk_p2_task_superseded_plan FOREIGN KEY (superseded_by_plan_id) REFERENCES p2_plan (plan_id),
    CONSTRAINT fk_p2_task_workshop FOREIGN KEY (workshop_id) REFERENCES dim_workshop (workshop_id),
    CONSTRAINT fk_p2_task_material FOREIGN KEY (material_id) REFERENCES p2_material (material_id),
    CONSTRAINT ck_p2_task_quantities CHECK (planned_qualified_ton > 0 AND sequence_no > 0 AND version >= 0
        AND (planned_rod_use_ton IS NULL OR planned_rod_use_ton >= 0)
        AND (planned_plate_ton IS NULL OR planned_plate_ton >= 0))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE p2_task_order (
    task_order_id BIGINT NOT NULL AUTO_INCREMENT,
    task_id BIGINT NOT NULL,
    order_id BIGINT NOT NULL,
    demand_key VARCHAR(128) NOT NULL,
    quantity_kind VARCHAR(24) NOT NULL,
    quantity_ton DECIMAL(18,3) NOT NULL,
    PRIMARY KEY (task_order_id),
    UNIQUE KEY uk_p2_task_order_demand (task_id, order_id, demand_key, quantity_kind),
    KEY ix_p2_task_order_demand (demand_key),
    CONSTRAINT fk_p2_task_order_task FOREIGN KEY (task_id) REFERENCES p2_plan_task (task_id),
    CONSTRAINT fk_p2_task_order_order FOREIGN KEY (order_id) REFERENCES p2_customer_order (order_id),
    CONSTRAINT ck_p2_task_order_kind CHECK (quantity_kind IN ('ROD_DEMAND', 'WIRE_OUTPUT')),
    CONSTRAINT ck_p2_task_order_quantity CHECK (quantity_ton > 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE p2_stock_balance (
    material_id BIGINT NOT NULL,
    physical_quantity_ton DECIMAL(18,3) NOT NULL DEFAULT 0,
    version BIGINT NOT NULL DEFAULT 0,
    updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    PRIMARY KEY (material_id),
    CONSTRAINT fk_p2_balance_material FOREIGN KEY (material_id) REFERENCES p2_material (material_id),
    CONSTRAINT ck_p2_balance_value CHECK (physical_quantity_ton >= 0 AND version >= 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE p2_stock_reservation (
    reservation_id BIGINT NOT NULL AUTO_INCREMENT,
    material_id BIGINT NOT NULL,
    plan_id BIGINT,
    order_id BIGINT,
    task_id BIGINT,
    demand_key VARCHAR(128),
    source_type VARCHAR(40) NOT NULL,
    source_id VARCHAR(128) NOT NULL,
    source_line_no INT NOT NULL DEFAULT 1,
    reserved_quantity_ton DECIMAL(18,3) NOT NULL,
    consumed_quantity_ton DECIMAL(18,3) NOT NULL DEFAULT 0,
    released_quantity_ton DECIMAL(18,3) NOT NULL DEFAULT 0,
    version BIGINT NOT NULL DEFAULT 0,
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    PRIMARY KEY (reservation_id),
    UNIQUE KEY uk_p2_reservation_source (source_type, source_id, source_line_no),
    KEY ix_p2_reservation_material (material_id, reservation_id),
    KEY ix_p2_reservation_demand (demand_key),
    CONSTRAINT fk_p2_reservation_material FOREIGN KEY (material_id) REFERENCES p2_material (material_id),
    CONSTRAINT fk_p2_reservation_plan FOREIGN KEY (plan_id) REFERENCES p2_plan (plan_id),
    CONSTRAINT fk_p2_reservation_order FOREIGN KEY (order_id) REFERENCES p2_customer_order (order_id),
    CONSTRAINT fk_p2_reservation_task FOREIGN KEY (task_id) REFERENCES p2_plan_task (task_id),
    CONSTRAINT ck_p2_reservation_scope CHECK (order_id IS NOT NULL OR task_id IS NOT NULL OR demand_key IS NOT NULL),
    CONSTRAINT ck_p2_reservation_qty CHECK (reserved_quantity_ton > 0 AND consumed_quantity_ton >= 0
        AND released_quantity_ton >= 0 AND consumed_quantity_ton + released_quantity_ton <= reserved_quantity_ton
        AND source_line_no > 0 AND version >= 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE p2_stock_movement (
    movement_id BIGINT NOT NULL AUTO_INCREMENT,
    material_id BIGINT NOT NULL,
    order_id BIGINT,
    task_id BIGINT,
    movement_type VARCHAR(32) NOT NULL,
    source_type VARCHAR(40) NOT NULL,
    source_id VARCHAR(128) NOT NULL,
    source_line_no INT NOT NULL DEFAULT 1,
    request_id VARCHAR(128) NOT NULL,
    corrects_movement_id BIGINT,
    business_date DATE NOT NULL,
    quantity_delta_ton DECIMAL(18,3) NOT NULL,
    physical_after_ton DECIMAL(18,3) NOT NULL,
    opening_material_id BIGINT GENERATED ALWAYS AS
        (CASE WHEN movement_type = 'OPENING' THEN material_id ELSE NULL END) STORED,
    operator_name VARCHAR(100) NOT NULL,
    reason VARCHAR(1000),
    recorded_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    version BIGINT NOT NULL DEFAULT 0,
    PRIMARY KEY (movement_id),
    UNIQUE KEY uk_p2_movement_source (source_type, source_id, source_line_no),
    UNIQUE KEY uk_p2_movement_opening (opening_material_id),
    KEY ix_p2_movement_material_day (material_id, business_date, movement_id),
    KEY ix_p2_movement_request (request_id),
    CONSTRAINT fk_p2_movement_material FOREIGN KEY (material_id) REFERENCES p2_material (material_id),
    CONSTRAINT fk_p2_movement_order FOREIGN KEY (order_id) REFERENCES p2_customer_order (order_id),
    CONSTRAINT fk_p2_movement_task FOREIGN KEY (task_id) REFERENCES p2_plan_task (task_id),
    CONSTRAINT fk_p2_movement_corrects FOREIGN KEY (corrects_movement_id) REFERENCES p2_stock_movement (movement_id),
    CONSTRAINT ck_p2_movement_values CHECK (physical_after_ton >= 0 AND source_line_no > 0 AND version >= 0),
    CONSTRAINT ck_p2_movement_delta CHECK (
        (movement_type = 'OPENING' AND quantity_delta_ton >= 0 AND corrects_movement_id IS NULL)
        OR (movement_type IN ('PLATE_RECEIPT', 'PRODUCTION_RECEIPT') AND quantity_delta_ton > 0 AND corrects_movement_id IS NULL)
        OR (movement_type IN ('PRODUCTION_ISSUE', 'DELIVERY') AND quantity_delta_ton < 0 AND corrects_movement_id IS NULL)
        OR (movement_type = 'CORRECTION' AND corrects_movement_id IS NOT NULL)
    )
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- 尚未入库的承诺独立于实物库存。兑现量由生产事实事务更新。
CREATE TABLE p2_supply_commitment (
    supply_commitment_id BIGINT NOT NULL AUTO_INCREMENT,
    plan_id BIGINT NOT NULL,
    source_task_id BIGINT NOT NULL,
    target_task_id BIGINT NOT NULL,
    material_id BIGINT NOT NULL,
    demand_key VARCHAR(128) NOT NULL,
    committed_quantity_ton DECIMAL(18,3) NOT NULL,
    fulfilled_quantity_ton DECIMAL(18,3) NOT NULL DEFAULT 0,
    released_quantity_ton DECIMAL(18,3) NOT NULL DEFAULT 0,
    source_before_target BOOLEAN NOT NULL DEFAULT TRUE,
    version BIGINT NOT NULL DEFAULT 0,
    PRIMARY KEY (supply_commitment_id),
    UNIQUE KEY uk_p2_supply_link (plan_id, source_task_id, target_task_id, demand_key),
    KEY ix_p2_supply_source (source_task_id, supply_commitment_id),
    KEY ix_p2_supply_target (target_task_id, supply_commitment_id),
    CONSTRAINT fk_p2_supply_plan FOREIGN KEY (plan_id) REFERENCES p2_plan (plan_id),
    CONSTRAINT fk_p2_supply_source FOREIGN KEY (source_task_id) REFERENCES p2_plan_task (task_id),
    CONSTRAINT fk_p2_supply_target FOREIGN KEY (target_task_id) REFERENCES p2_plan_task (task_id),
    CONSTRAINT fk_p2_supply_material FOREIGN KEY (material_id) REFERENCES p2_material (material_id),
    CONSTRAINT ck_p2_supply_qty CHECK (source_task_id <> target_task_id AND committed_quantity_ton > 0
        AND fulfilled_quantity_ton >= 0 AND released_quantity_ton >= 0
        AND fulfilled_quantity_ton + released_quantity_ton <= committed_quantity_ton AND version >= 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

INSERT INTO p2_workshop_capacity (workshop_id, max_daily_output_ton)
SELECT workshop_id, 10.000
FROM dim_workshop
WHERE workshop_code IN ('WS-ROD', 'WS-WIRE');

INSERT INTO p2_material (material_code, material_name, material_type, specification)
VALUES ('COPPER-PLATE', '铜板', 'COPPER_PLATE', ''),
       ('COPPER-ROD-PHI8', 'Φ8铜杆', 'COPPER_ROD', 'Φ8');
