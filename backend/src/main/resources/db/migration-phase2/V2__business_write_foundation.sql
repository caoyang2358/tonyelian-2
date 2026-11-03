-- G02 and technical idempotency storage shared by all business modules.
CREATE TABLE p2_change_log (
    change_log_id BIGINT NOT NULL AUTO_INCREMENT,
    object_type VARCHAR(64) NOT NULL,
    object_id VARCHAR(128) NOT NULL,
    action VARCHAR(64) NOT NULL,
    before_value JSON NULL,
    after_value JSON NULL,
    reason VARCHAR(1000) NULL,
    operator_name VARCHAR(100) NOT NULL,
    recorded_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (change_log_id),
    KEY idx_p2_change_object (object_type, object_id, recorded_at, change_log_id),
    CONSTRAINT ck_p2_change_has_value CHECK (before_value IS NOT NULL OR after_value IS NOT NULL)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE p2_request_result (
    request_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    operation VARCHAR(100) NOT NULL,
    target_key VARCHAR(128) NULL,
    payload_hash BINARY(32) NOT NULL,
    response_data JSON NULL,
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    completed_at DATETIME(3) NULL,
    PRIMARY KEY (request_id),
    CONSTRAINT ck_p2_request_completion CHECK (
        (completed_at IS NULL AND response_data IS NULL)
        OR (completed_at IS NOT NULL AND response_data IS NOT NULL)
    )
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
