-- Actual facts use original record IDs with versioned corrections in the shared audit log.
CREATE TABLE p2_plate_input (
    input_id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    task_id BIGINT NOT NULL,
    material_id BIGINT NOT NULL,
    movement_id BIGINT NOT NULL,
    production_group_key VARCHAR(64) NULL,
    actual_date DATE NOT NULL,
    input_quantity_ton DECIMAL(18,3) NOT NULL,
    material_amount_yuan DECIMAL(20,2) NULL,
    operator_name VARCHAR(100) NOT NULL,
    recorded_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    version BIGINT NOT NULL DEFAULT 0,
    UNIQUE KEY uk_p2_input_movement (movement_id),
    KEY ix_p2_input_task_date (task_id, actual_date),
    KEY ix_p2_input_group (production_group_key),
    FOREIGN KEY (task_id) REFERENCES p2_plan_task(task_id),
    FOREIGN KEY (material_id) REFERENCES p2_material(material_id),
    FOREIGN KEY (movement_id) REFERENCES p2_stock_movement(movement_id),
    CHECK (input_quantity_ton > 0),
    CHECK (material_amount_yuan IS NULL OR material_amount_yuan >= 0),
    CHECK (version >= 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- One weighing per actual aggregate. JSON arrays preserve additional task/input links;
-- the service validates these references without cloning quantities across tasks.
CREATE TABLE p2_output_record (
    output_id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    record_type VARCHAR(24) NOT NULL,
    workshop_id BIGINT NOT NULL,
    task_id BIGINT NULL,
    material_id BIGINT NULL,
    movement_id BIGINT NULL,
    production_group_key VARCHAR(64) NULL,
    task_references JSON NULL,
    input_references JSON NULL,
    execution_references JSON NULL,
    actual_date DATE NOT NULL,
    started_at DATETIME(3) NULL,
    stopped_at DATETIME(3) NULL,
    qualified_quantity_ton DECIMAL(18,3) NULL,
    reason TEXT NULL,
    operator_name VARCHAR(100) NOT NULL,
    recorded_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    version BIGINT NOT NULL DEFAULT 0,
    UNIQUE KEY uk_p2_output_movement (movement_id),
    KEY ix_p2_output_shop_date (workshop_id, actual_date, record_type),
    KEY ix_p2_output_task_date (task_id, actual_date),
    KEY ix_p2_output_group (production_group_key),
    FOREIGN KEY (workshop_id) REFERENCES dim_workshop(workshop_id),
    FOREIGN KEY (task_id) REFERENCES p2_plan_task(task_id),
    FOREIGN KEY (material_id) REFERENCES p2_material(material_id),
    FOREIGN KEY (movement_id) REFERENCES p2_stock_movement(movement_id),
    CHECK ((record_type = 'QUALIFIED_OUTPUT' AND material_id IS NOT NULL
            AND movement_id IS NOT NULL AND qualified_quantity_ton IS NOT NULL
            AND qualified_quantity_ton > 0 AND (task_id IS NOT NULL OR production_group_key IS NOT NULL))
        OR (record_type = 'NO_OUTPUT' AND movement_id IS NULL
            AND qualified_quantity_ton IS NOT NULL AND qualified_quantity_ton = 0)
        OR (record_type = 'EXECUTION' AND task_id IS NOT NULL
            AND movement_id IS NULL AND qualified_quantity_ton IS NULL
            AND (started_at IS NOT NULL OR stopped_at IS NOT NULL))),
    CHECK (started_at IS NULL OR stopped_at IS NULL OR stopped_at >= started_at),
    CHECK (version >= 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE p2_rod_handover (
    handover_id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    task_id BIGINT NOT NULL,
    order_id BIGINT NULL,
    movement_id BIGINT NOT NULL,
    receiving_references JSON NULL,
    sent_quantity_ton DECIMAL(18,3) NOT NULL,
    received_quantity_ton DECIMAL(18,3) NULL,
    sent_at DATETIME(3) NOT NULL,
    received_at DATETIME(3) NULL,
    release_reason TEXT NULL,
    released_by VARCHAR(100) NULL,
    released_at DATETIME(3) NULL,
    operator_name VARCHAR(100) NOT NULL,
    recorded_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    version BIGINT NOT NULL DEFAULT 0,
    UNIQUE KEY uk_p2_handover_movement (movement_id),
    KEY ix_p2_handover_task_time (task_id, sent_at),
    KEY ix_p2_handover_order_time (order_id, sent_at),
    FOREIGN KEY (task_id) REFERENCES p2_plan_task(task_id),
    FOREIGN KEY (order_id) REFERENCES p2_customer_order(order_id),
    FOREIGN KEY (movement_id) REFERENCES p2_stock_movement(movement_id),
    CHECK (sent_quantity_ton > 0),
    CHECK ((received_quantity_ton IS NULL AND received_at IS NULL)
        OR (received_quantity_ton IS NOT NULL AND received_quantity_ton >= 0
            AND received_at IS NOT NULL AND received_at >= sent_at)),
    CHECK (received_quantity_ton IS NULL
        OR ABS(received_quantity_ton - sent_quantity_ton) * 100 <= sent_quantity_ton * 5
        OR (release_reason IS NOT NULL AND CHAR_LENGTH(TRIM(release_reason)) > 0
            AND released_by IS NOT NULL AND released_at IS NOT NULL)),
    CHECK (version >= 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE p2_finished_allocation (
    finished_allocation_id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    order_id BIGINT NOT NULL,
    material_id BIGINT NOT NULL,
    output_id BIGINT NULL,
    reservation_id BIGINT NOT NULL,
    source_type VARCHAR(24) NOT NULL,
    original_quantity_ton DECIMAL(18,3) NOT NULL,
    allocated_quantity_ton DECIMAL(18,3) NOT NULL,
    released_quantity_ton DECIMAL(18,3) NOT NULL DEFAULT 0,
    operator_name VARCHAR(100) NOT NULL,
    recorded_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    version BIGINT NOT NULL DEFAULT 0,
    UNIQUE KEY uk_p2_allocation_reservation (reservation_id),
    KEY ix_p2_allocation_order_material (order_id, material_id),
    KEY ix_p2_allocation_output_order (output_id, order_id),
    FOREIGN KEY (order_id) REFERENCES p2_customer_order(order_id),
    FOREIGN KEY (material_id) REFERENCES p2_material(material_id),
    FOREIGN KEY (output_id) REFERENCES p2_output_record(output_id),
    FOREIGN KEY (reservation_id) REFERENCES p2_stock_reservation(reservation_id),
    CHECK ((source_type = 'DIRECT_OUTPUT' AND output_id IS NOT NULL) OR source_type = 'PUBLIC_STOCK'),
    CHECK (original_quantity_ton > 0 AND allocated_quantity_ton >= 0
        AND released_quantity_ton >= 0 AND released_quantity_ton <= allocated_quantity_ton),
    CHECK (version >= 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE p2_delivery (
    delivery_id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    order_id BIGINT NOT NULL,
    movement_id BIGINT NOT NULL,
    business_date DATE NOT NULL,
    quantity_ton DECIMAL(18,3) NOT NULL,
    operator_name VARCHAR(100) NOT NULL,
    recorded_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    version BIGINT NOT NULL DEFAULT 0,
    UNIQUE KEY uk_p2_delivery_movement (movement_id),
    KEY ix_p2_delivery_order_date (order_id, business_date),
    FOREIGN KEY (order_id) REFERENCES p2_customer_order(order_id),
    FOREIGN KEY (movement_id) REFERENCES p2_stock_movement(movement_id),
    CHECK (quantity_ton > 0),
    CHECK (version >= 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE p2_delivery_item (
    delivery_item_id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    delivery_id BIGINT NOT NULL,
    finished_allocation_id BIGINT NOT NULL,
    quantity_ton DECIMAL(18,3) NOT NULL,
    UNIQUE KEY uk_p2_delivery_item_share (delivery_id, finished_allocation_id),
    FOREIGN KEY (delivery_id) REFERENCES p2_delivery(delivery_id),
    FOREIGN KEY (finished_allocation_id) REFERENCES p2_finished_allocation(finished_allocation_id),
    CHECK (quantity_ton > 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
