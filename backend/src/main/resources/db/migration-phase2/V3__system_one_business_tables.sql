-- E01–E15. Business instants are stored as Asia/Shanghai local DATETIME(3).
-- Shared dimension identifiers retain the signed BIGINT types used by phase one.

CREATE TABLE p2_equipment_profile (
    equipment_id BIGINT NOT NULL,
    type_code VARCHAR(64) NOT NULL,
    model VARCHAR(150),
    process_id BIGINT,
    installation_location VARCHAR(255),
    manufacturer VARCHAR(150),
    factory_serial_no VARCHAR(100),
    purchased_on DATE,
    commissioned_on DATE,
    expected_life_years INT,
    warranty_expires_on DATE,
    management_status VARCHAR(16),
    technical_parameters JSON NOT NULL,
    version BIGINT NOT NULL DEFAULT 1,
    created_at DATETIME(3) NOT NULL,
    updated_at DATETIME(3) NOT NULL,
    PRIMARY KEY (equipment_id),
    KEY ix_p2_equip_type_status (type_code, management_status),
    CONSTRAINT fk_p2_equip_profile_equipment FOREIGN KEY (equipment_id) REFERENCES dim_equipment (equipment_id),
    CONSTRAINT fk_p2_equip_profile_process FOREIGN KEY (process_id) REFERENCES dim_process (process_id),
    CHECK (expected_life_years IS NULL OR expected_life_years > 0),
    CHECK (management_status IS NULL OR management_status IN ('IN_USE','STOPPED','SCRAPPED')),
    CHECK (JSON_TYPE(technical_parameters) = 'ARRAY'),
    CHECK (version >= 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE p2_sensor_profile (
    sensor_id BIGINT NOT NULL,
    location VARCHAR(255),
    purpose VARCHAR(255),
    version BIGINT NOT NULL DEFAULT 1,
    created_at DATETIME(3) NOT NULL,
    updated_at DATETIME(3) NOT NULL,
    PRIMARY KEY (sensor_id),
    CONSTRAINT fk_p2_sensor_profile_sensor FOREIGN KEY (sensor_id) REFERENCES dim_sensor (sensor_id),
    CHECK (version >= 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- E02 support. Configuration concurrency version is distinct from the rule revision.
CREATE TABLE p2_sensor_parameter_profile (
    parameter_id BIGINT NOT NULL,
    version BIGINT NOT NULL DEFAULT 1,
    updated_at DATETIME(3) NOT NULL,
    PRIMARY KEY (parameter_id),
    CONSTRAINT fk_p2_param_profile_param FOREIGN KEY (parameter_id) REFERENCES dim_sensor_parameter (parameter_id),
    CHECK (version >= 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE p2_supplier (
    supplier_id BIGINT NOT NULL AUTO_INCREMENT,
    name VARCHAR(150) NOT NULL,
    contact_name VARCHAR(100),
    phone VARCHAR(50),
    remark TEXT,
    version BIGINT NOT NULL DEFAULT 1,
    created_at DATETIME(3) NOT NULL,
    updated_at DATETIME(3) NOT NULL,
    PRIMARY KEY (supplier_id),
    KEY ix_p2_supplier_name (name),
    CHECK (version >= 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE p2_equipment_supplier (
    equipment_id BIGINT NOT NULL,
    supplier_id BIGINT NOT NULL,
    PRIMARY KEY (equipment_id, supplier_id),
    CONSTRAINT fk_p2_equip_supplier_equipment FOREIGN KEY (equipment_id) REFERENCES dim_equipment (equipment_id),
    CONSTRAINT fk_p2_equip_supplier_supplier FOREIGN KEY (supplier_id) REFERENCES p2_supplier (supplier_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE p2_maintenance_contract (
    contract_id BIGINT NOT NULL AUTO_INCREMENT,
    service_organization VARCHAR(150) NOT NULL,
    contract_number VARCHAR(100),
    start_date DATE,
    end_date DATE,
    remark TEXT,
    version BIGINT NOT NULL DEFAULT 1,
    created_at DATETIME(3) NOT NULL,
    updated_at DATETIME(3) NOT NULL,
    PRIMARY KEY (contract_id),
    KEY ix_p2_contract_end (end_date),
    CHECK (start_date IS NULL OR end_date IS NULL OR end_date >= start_date),
    CHECK (version >= 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE p2_contract_equipment (
    contract_id BIGINT NOT NULL,
    equipment_id BIGINT NOT NULL,
    PRIMARY KEY (contract_id, equipment_id),
    CONSTRAINT fk_p2_contract_equip_contract FOREIGN KEY (contract_id) REFERENCES p2_maintenance_contract (contract_id),
    CONSTRAINT fk_p2_contract_equip_equipment FOREIGN KEY (equipment_id) REFERENCES dim_equipment (equipment_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE p2_reminder_setting (
    setting_id BIGINT NOT NULL,
    warranty_lead_days INT NOT NULL DEFAULT 30,
    maintenance_lead_days INT NOT NULL DEFAULT 30,
    version BIGINT NOT NULL DEFAULT 1,
    updated_at DATETIME(3) NOT NULL,
    PRIMARY KEY (setting_id),
    CHECK (setting_id = 1),
    CHECK (warranty_lead_days >= 0 AND maintenance_lead_days >= 0),
    CHECK (version >= 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE p2_alert_rule_version (
    rule_version_id BIGINT NOT NULL AUTO_INCREMENT,
    parameter_id BIGINT NOT NULL,
    rule_version BIGINT NOT NULL,
    previous_rule_version_id BIGINT,
    normal_lower DECIMAL(20,6),
    normal_upper DECIMAL(20,6),
    alarm_lower DECIMAL(20,6),
    alarm_upper DECIMAL(20,6),
    unit_code VARCHAR(32),
    trigger_mode VARCHAR(16),
    duration_seconds INT,
    enabled BOOLEAN NOT NULL,
    effective_at DATETIME(3) NOT NULL,
    reason TEXT,
    operator_name VARCHAR(100) NOT NULL,
    PRIMARY KEY (rule_version_id),
    UNIQUE KEY uk_p2_rule_parameter_version (parameter_id, rule_version),
    UNIQUE KEY uk_p2_rule_parameter_id (parameter_id, rule_version_id),
    CONSTRAINT fk_p2_rule_parameter FOREIGN KEY (parameter_id) REFERENCES dim_sensor_parameter (parameter_id),
    CONSTRAINT fk_p2_rule_previous FOREIGN KEY (parameter_id, previous_rule_version_id)
        REFERENCES p2_alert_rule_version (parameter_id, rule_version_id),
    CHECK (rule_version >= 0),
    CHECK (normal_lower IS NULL OR normal_upper IS NULL OR normal_lower <= normal_upper),
    CHECK (alarm_lower IS NULL OR alarm_upper IS NULL OR alarm_lower <= alarm_upper),
    CHECK (trigger_mode IS NULL OR trigger_mode IN ('SUSTAINED','IMMEDIATE')),
    CHECK (duration_seconds IS NULL OR duration_seconds > 0),
    CHECK (trigger_mode <> 'IMMEDIATE' OR duration_seconds IS NULL)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE p2_alert_event (
    event_id BIGINT NOT NULL AUTO_INCREMENT,
    equipment_id BIGINT NOT NULL,
    sensor_id BIGINT NOT NULL,
    parameter_id BIGINT NOT NULL,
    rule_version_id BIGINT NOT NULL,
    started_at DATETIME(3) NOT NULL,
    triggered_at DATETIME(3) NOT NULL,
    trigger_value DECIMAL(20,6),
    trigger_value_text VARCHAR(500),
    unit_code VARCHAR(32),
    source_reference JSON,
    recovered_at DATETIME(3),
    ended_at DATETIME(3),
    end_reason VARCHAR(32),
    active_parameter_id BIGINT GENERATED ALWAYS AS (IF(ended_at IS NULL, parameter_id, NULL)) STORED,
    version BIGINT NOT NULL DEFAULT 1,
    updated_at DATETIME(3) NOT NULL,
    PRIMARY KEY (event_id),
    UNIQUE KEY uk_p2_alert_active_parameter (active_parameter_id),
    KEY ix_p2_alert_equipment_trigger (equipment_id, triggered_at),
    KEY ix_p2_alert_sensor_trigger (sensor_id, triggered_at),
    CONSTRAINT fk_p2_alert_equipment FOREIGN KEY (equipment_id) REFERENCES dim_equipment (equipment_id),
    CONSTRAINT fk_p2_alert_sensor FOREIGN KEY (sensor_id) REFERENCES dim_sensor (sensor_id),
    CONSTRAINT fk_p2_alert_rule FOREIGN KEY (parameter_id, rule_version_id)
        REFERENCES p2_alert_rule_version (parameter_id, rule_version_id),
    CHECK (triggered_at >= started_at),
    CHECK ((ended_at IS NULL AND end_reason IS NULL) OR
           (ended_at IS NOT NULL AND ended_at >= triggered_at AND end_reason IS NOT NULL)),
    CHECK (recovered_at IS NULL OR (ended_at IS NOT NULL AND recovered_at BETWEEN triggered_at AND ended_at)),
    CHECK (end_reason <> 'RULE_CHANGED' OR recovered_at IS NULL),
    CHECK (version >= 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- E10 support. Current handling state is derived from the latest action.
CREATE TABLE p2_alert_handling_record (
    handling_record_id BIGINT NOT NULL AUTO_INCREMENT,
    event_id BIGINT NOT NULL,
    action VARCHAR(24) NOT NULL,
    reason TEXT NOT NULL,
    operator_name VARCHAR(100) NOT NULL,
    recorded_at DATETIME(3) NOT NULL,
    PRIMARY KEY (handling_record_id),
    KEY ix_p2_alert_handling_event (event_id, recorded_at, handling_record_id),
    CONSTRAINT fk_p2_alert_handling_event FOREIGN KEY (event_id) REFERENCES p2_alert_event (event_id),
    CHECK (action IN ('ACKNOWLEDGED','DEFER_REPAIR'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE p2_inspection (
    inspection_id BIGINT NOT NULL AUTO_INCREMENT,
    equipment_id BIGINT NOT NULL,
    inspected_at DATETIME(3) NOT NULL,
    inspector_name VARCHAR(100) NOT NULL,
    content TEXT NOT NULL,
    result VARCHAR(16) NOT NULL,
    description TEXT,
    operator_name VARCHAR(100) NOT NULL,
    recorded_at DATETIME(3) NOT NULL,
    updated_at DATETIME(3) NOT NULL,
    version BIGINT NOT NULL DEFAULT 1,
    PRIMARY KEY (inspection_id),
    KEY ix_p2_inspection_equipment_date (equipment_id, inspected_at),
    CONSTRAINT fk_p2_inspection_equipment FOREIGN KEY (equipment_id) REFERENCES dim_equipment (equipment_id),
    CHECK (result IN ('NORMAL','ABNORMAL')),
    CHECK (version >= 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE p2_repair_order (
    repair_order_id BIGINT NOT NULL AUTO_INCREMENT,
    order_no VARCHAR(64) NOT NULL,
    equipment_id BIGINT NOT NULL,
    source_type VARCHAR(16) NOT NULL,
    source_alert_event_id BIGINT,
    source_inspection_id BIGINT,
    source_snapshot JSON,
    status VARCHAR(24) NOT NULL DEFAULT 'DRAFT',
    description TEXT,
    supplement TEXT,
    reporter_name VARCHAR(100),
    created_at DATETIME(3) NOT NULL,
    updated_at DATETIME(3) NOT NULL,
    first_submitted_at DATETIME(3),
    completed_at DATETIME(3),
    version BIGINT NOT NULL DEFAULT 1,
    PRIMARY KEY (repair_order_id),
    UNIQUE KEY uk_p2_repair_order_no (order_no),
    KEY ix_p2_repair_equipment_status (equipment_id, status, created_at),
    KEY ix_p2_repair_status_created (status, created_at),
    CONSTRAINT fk_p2_repair_equipment FOREIGN KEY (equipment_id) REFERENCES dim_equipment (equipment_id),
    CONSTRAINT fk_p2_repair_alert FOREIGN KEY (source_alert_event_id) REFERENCES p2_alert_event (event_id),
    CONSTRAINT fk_p2_repair_inspection FOREIGN KEY (source_inspection_id) REFERENCES p2_inspection (inspection_id),
    CHECK (
        (source_type = 'MANUAL' AND source_alert_event_id IS NULL AND source_inspection_id IS NULL) OR
        (source_type = 'ALERT_EVENT' AND source_alert_event_id IS NOT NULL AND source_inspection_id IS NULL AND source_snapshot IS NOT NULL) OR
        (source_type = 'INSPECTION' AND source_inspection_id IS NOT NULL AND source_alert_event_id IS NULL AND source_snapshot IS NOT NULL)
    ),
    CHECK (status IN ('DRAFT','REVIEW_PENDING','RETURNED_FOR_INFO','IN_REPAIR','ACCEPTANCE_PENDING','COMPLETED','CLOSED')),
    CHECK (version >= 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE p2_repair_action (
    action_id BIGINT NOT NULL AUTO_INCREMENT,
    repair_order_id BIGINT NOT NULL,
    action_type VARCHAR(32) NOT NULL,
    result VARCHAR(32),
    previous_status VARCHAR(24),
    next_status VARCHAR(24) NOT NULL,
    occurred_at DATETIME(3),
    recorded_at DATETIME(3) NOT NULL,
    operator_name VARCHAR(100) NOT NULL,
    reason TEXT,
    description TEXT,
    PRIMARY KEY (action_id),
    KEY ix_p2_repair_action_order (repair_order_id, action_id),
    CONSTRAINT fk_p2_repair_action_order FOREIGN KEY (repair_order_id) REFERENCES p2_repair_order (repair_order_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE p2_knowledge_article (
    article_id BIGINT NOT NULL AUTO_INCREMENT,
    title VARCHAR(200) NOT NULL,
    category VARCHAR(32) NOT NULL,
    equipment_id BIGINT,
    equipment_type VARCHAR(64),
    fault_phenomenon TEXT,
    content MEDIUMTEXT,
    steps JSON NOT NULL,
    source_repair_order_id BIGINT,
    version BIGINT NOT NULL DEFAULT 1,
    created_at DATETIME(3) NOT NULL,
    updated_at DATETIME(3) NOT NULL,
    PRIMARY KEY (article_id),
    KEY ix_p2_knowledge_category_updated (category, updated_at),
    KEY ix_p2_knowledge_type_updated (equipment_type, updated_at),
    CONSTRAINT fk_p2_knowledge_equipment FOREIGN KEY (equipment_id) REFERENCES dim_equipment (equipment_id),
    CONSTRAINT fk_p2_knowledge_repair FOREIGN KEY (source_repair_order_id) REFERENCES p2_repair_order (repair_order_id),
    CHECK (category IN ('INTRODUCTION','OPERATION_MAINTENANCE','COMMON_FAULT','TROUBLESHOOTING','REPAIR_CASE')),
    CHECK (source_repair_order_id IS NULL OR category = 'REPAIR_CASE'),
    CHECK (equipment_id IS NOT NULL OR equipment_type IS NOT NULL),
    CHECK (JSON_TYPE(steps) = 'ARRAY'),
    CHECK (version >= 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- E07. Exactly one owning object per upload, independent of later references.
CREATE TABLE p2_attachment (
    attachment_id BIGINT NOT NULL AUTO_INCREMENT,
    equipment_id BIGINT,
    contract_id BIGINT,
    article_id BIGINT,
    repair_order_id BIGINT,
    file_kind VARCHAR(16) NOT NULL,
    original_name VARCHAR(255) NOT NULL,
    media_type VARCHAR(127) NOT NULL,
    size_bytes BIGINT NOT NULL,
    storage_key VARCHAR(255) NOT NULL,
    version BIGINT NOT NULL DEFAULT 1,
    created_at DATETIME(3) NOT NULL,
    updated_at DATETIME(3) NOT NULL,
    PRIMARY KEY (attachment_id),
    UNIQUE KEY uk_p2_attachment_storage (storage_key),
    CONSTRAINT fk_p2_attachment_equipment FOREIGN KEY (equipment_id) REFERENCES dim_equipment (equipment_id),
    CONSTRAINT fk_p2_attachment_contract FOREIGN KEY (contract_id) REFERENCES p2_maintenance_contract (contract_id),
    CONSTRAINT fk_p2_attachment_article FOREIGN KEY (article_id) REFERENCES p2_knowledge_article (article_id),
    CONSTRAINT fk_p2_attachment_repair FOREIGN KEY (repair_order_id) REFERENCES p2_repair_order (repair_order_id),
    CHECK ((equipment_id IS NOT NULL) + (contract_id IS NOT NULL) + (article_id IS NOT NULL) + (repair_order_id IS NOT NULL) = 1),
    CHECK (file_kind IN ('IMAGE','MANUAL','DOCUMENT','PHOTO')),
    CHECK (size_bytes >= 0),
    CHECK (version >= 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE p2_knowledge_attachment (
    article_id BIGINT NOT NULL,
    attachment_id BIGINT NOT NULL,
    PRIMARY KEY (article_id, attachment_id),
    CONSTRAINT fk_p2_knowledge_attachment_article FOREIGN KEY (article_id) REFERENCES p2_knowledge_article (article_id),
    CONSTRAINT fk_p2_knowledge_attachment_file FOREIGN KEY (attachment_id) REFERENCES p2_attachment (attachment_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE p2_repair_photo (
    repair_order_id BIGINT NOT NULL,
    attachment_id BIGINT NOT NULL,
    PRIMARY KEY (repair_order_id, attachment_id),
    CONSTRAINT fk_p2_repair_photo_order FOREIGN KEY (repair_order_id) REFERENCES p2_repair_order (repair_order_id),
    CONSTRAINT fk_p2_repair_photo_file FOREIGN KEY (attachment_id) REFERENCES p2_attachment (attachment_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE p2_repair_action_attachment (
    action_id BIGINT NOT NULL,
    attachment_id BIGINT NOT NULL,
    PRIMARY KEY (action_id, attachment_id),
    CONSTRAINT fk_p2_repair_action_file_action FOREIGN KEY (action_id) REFERENCES p2_repair_action (action_id),
    CONSTRAINT fk_p2_repair_action_file_file FOREIGN KEY (attachment_id) REFERENCES p2_attachment (attachment_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE p2_network_layout (
    workshop_id BIGINT NOT NULL,
    version BIGINT NOT NULL DEFAULT 1,
    updated_at DATETIME(3) NOT NULL,
    PRIMARY KEY (workshop_id),
    CONSTRAINT fk_p2_layout_workshop FOREIGN KEY (workshop_id) REFERENCES dim_workshop (workshop_id),
    CHECK (version >= 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE p2_network_node (
    workshop_id BIGINT NOT NULL,
    node_id VARCHAR(128) COLLATE utf8mb4_bin NOT NULL,
    equipment_id BIGINT,
    sensor_id BIGINT,
    x_percent DECIMAL(9,6) NOT NULL,
    y_percent DECIMAL(9,6) NOT NULL,
    PRIMARY KEY (workshop_id, node_id),
    UNIQUE KEY uk_p2_layout_equipment (workshop_id, equipment_id),
    UNIQUE KEY uk_p2_layout_sensor (workshop_id, sensor_id),
    CONSTRAINT fk_p2_network_node_layout FOREIGN KEY (workshop_id) REFERENCES p2_network_layout (workshop_id),
    CONSTRAINT fk_p2_network_node_equipment FOREIGN KEY (equipment_id) REFERENCES dim_equipment (equipment_id),
    CONSTRAINT fk_p2_network_node_sensor FOREIGN KEY (sensor_id) REFERENCES dim_sensor (sensor_id),
    CHECK ((equipment_id IS NOT NULL) + (sensor_id IS NOT NULL) = 1),
    CHECK (x_percent BETWEEN 0 AND 100 AND y_percent BETWEEN 0 AND 100)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE p2_network_edge (
    workshop_id BIGINT NOT NULL,
    edge_id VARCHAR(128) COLLATE utf8mb4_bin NOT NULL,
    from_node_id VARCHAR(128) COLLATE utf8mb4_bin NOT NULL,
    to_node_id VARCHAR(128) COLLATE utf8mb4_bin NOT NULL,
    label VARCHAR(255),
    PRIMARY KEY (workshop_id, edge_id),
    CONSTRAINT fk_p2_network_edge_from FOREIGN KEY (workshop_id, from_node_id)
        REFERENCES p2_network_node (workshop_id, node_id),
    CONSTRAINT fk_p2_network_edge_to FOREIGN KEY (workshop_id, to_node_id)
        REFERENCES p2_network_node (workshop_id, node_id),
    CHECK (from_node_id <> to_node_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
