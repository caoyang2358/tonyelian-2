-- Phase two owns only this initialization table. Never recreate or drop phase-one tables.
CREATE TABLE p2_project_info (
    project_id BIGINT NOT NULL AUTO_INCREMENT,
    project_code VARCHAR(64) NOT NULL,
    project_name VARCHAR(100) NOT NULL,
    project_scope VARCHAR(64) NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (project_id),
    UNIQUE KEY uk_p2_project_code (project_code)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

INSERT INTO p2_project_info (project_code, project_name, project_scope)
VALUES ('COPPER_PHASE2', '铜冶炼二期', 'INITIALIZATION_ONLY');
