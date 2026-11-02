-- DEVELOPMENT SNAPSHOT ONLY. Apply once to a NEW EMPTY, ISOLATED MySQL schema.
-- Source: phase1 active backend migration-mysql/V1__mysql_schema.sql
-- Source SHA256: a63b94efdb47676960c806f061059581378f0e2005a3eec256858cfaf54a7ed3
-- Contains the original 12 CREATE TABLE statements and 3 ADD FOREIGN KEY statements.
-- No database selection, data deletion, table replacement, or foreign-key disabling.
-- Do not register this snapshot as a production Flyway migration.

SET NAMES utf8mb4;
CREATE TABLE dim_workshop (
  workshop_id BIGINT NOT NULL AUTO_INCREMENT,
  workshop_code VARCHAR(64) NOT NULL,
  workshop_name VARCHAR(100) NOT NULL,
  workshop_type VARCHAR(50), description VARCHAR(255),
  enabled TINYINT(1) NOT NULL DEFAULT 1,
  created_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (workshop_id), UNIQUE KEY uk_dim_workshop_code (workshop_code)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE dim_equipment (
  equipment_id BIGINT NOT NULL AUTO_INCREMENT,
  equipment_code VARCHAR(64) NOT NULL, equipment_name VARCHAR(100) NOT NULL,
  workshop_id BIGINT NOT NULL, responsible_person VARCHAR(100),
  enabled TINYINT(1) NOT NULL DEFAULT 1, equipment_usage VARCHAR(32) NOT NULL DEFAULT 'PRODUCTION',
  created_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (equipment_id), UNIQUE KEY uk_dim_equipment_code (equipment_code),
  KEY idx_equipment_workshop (workshop_id),
  CONSTRAINT fk_equipment_workshop FOREIGN KEY (workshop_id) REFERENCES dim_workshop(workshop_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE dim_unit (
  unit_id BIGINT NOT NULL AUTO_INCREMENT, unit_code VARCHAR(32) NOT NULL, unit_name VARCHAR(50) NOT NULL,
  unit_category VARCHAR(50) NOT NULL, base_unit_code VARCHAR(32), conversion_factor DECIMAL(20,10) NOT NULL DEFAULT 1,
  enabled TINYINT(1) NOT NULL DEFAULT 1, PRIMARY KEY(unit_id), UNIQUE KEY uk_dim_unit_code(unit_code)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE dim_process (
  process_id BIGINT NOT NULL AUTO_INCREMENT, process_code VARCHAR(64) NOT NULL, process_name VARCHAR(100) NOT NULL,
  workshop_id BIGINT NOT NULL, sequence_no INT NOT NULL, description VARCHAR(255), enabled TINYINT(1) NOT NULL DEFAULT 1,
  PRIMARY KEY(process_id), UNIQUE KEY uk_dim_process_code(process_code), UNIQUE KEY uk_process_workshop_seq(workshop_id,sequence_no),
  CONSTRAINT fk_process_workshop FOREIGN KEY(workshop_id) REFERENCES dim_workshop(workshop_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE dim_sensor (
  sensor_id BIGINT NOT NULL AUTO_INCREMENT, sensor_code VARCHAR(128) NOT NULL, sensor_name VARCHAR(100) NOT NULL,
  equipment_id BIGINT NOT NULL, sensor_type VARCHAR(50), data_source VARCHAR(50), enabled TINYINT(1) NOT NULL DEFAULT 1,
  created_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP, updated_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  energy_type_id BIGINT NULL, energy_scope_type VARCHAR(16) NULL, energy_workshop_id BIGINT NULL,
  parent_energy_sensor_id BIGINT NULL, energy_flow_direction VARCHAR(16) NOT NULL DEFAULT 'INPUT',
  energy_multiplier DECIMAL(20,6) NOT NULL DEFAULT 1, configured_loss_rate DECIMAL(10,6) NOT NULL DEFAULT 0,
  PRIMARY KEY(sensor_id), UNIQUE KEY uk_dim_sensor_code(sensor_code), KEY idx_energy_sensor_parent(parent_energy_sensor_id),
  KEY idx_energy_sensor_scope(energy_type_id,energy_scope_type,energy_workshop_id,equipment_id),
  KEY idx_sensor_equipment(equipment_id), KEY idx_sensor_energy_workshop(energy_workshop_id),
  CONSTRAINT fk_sensor_equipment FOREIGN KEY(equipment_id) REFERENCES dim_equipment(equipment_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE dim_sensor_parameter (
  parameter_id BIGINT NOT NULL AUTO_INCREMENT, sensor_id BIGINT NOT NULL, parameter_code VARCHAR(64) NOT NULL,
  parameter_name VARCHAR(100) NOT NULL, data_type VARCHAR(32) NOT NULL, unit_code VARCHAR(32), decimal_places INT NOT NULL DEFAULT 2,
  normal_lower_limit DECIMAL(20,6), normal_upper_limit DECIMAL(20,6), alarm_lower_limit DECIMAL(20,6), alarm_upper_limit DECIMAL(20,6),
  sampling_frequency_seconds INT, enabled TINYINT(1) NOT NULL DEFAULT 1, process_id BIGINT NULL,
  primary_display TINYINT(1) NOT NULL DEFAULT 0, trend_display TINYINT(1) NOT NULL DEFAULT 0, display_order INT NOT NULL DEFAULT 0,
  PRIMARY KEY(parameter_id), UNIQUE KEY uk_sensor_parameter(sensor_id,parameter_code), KEY idx_parameter_process(process_id),
  CONSTRAINT fk_parameter_sensor FOREIGN KEY(sensor_id) REFERENCES dim_sensor(sensor_id),
  CONSTRAINT fk_parameter_unit FOREIGN KEY(unit_code) REFERENCES dim_unit(unit_code),
  CONSTRAINT fk_parameter_process FOREIGN KEY(process_id) REFERENCES dim_process(process_id),
  CONSTRAINT ck_parameter_type CHECK(data_type IN ('DECIMAL','INTEGER','BOOLEAN','STRING')),
  CONSTRAINT ck_parameter_sampling CHECK(sampling_frequency_seconds IS NULL OR sampling_frequency_seconds>0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE dim_energy_type (
  energy_type_id BIGINT NOT NULL AUTO_INCREMENT, energy_code VARCHAR(32) NOT NULL, energy_name VARCHAR(50) NOT NULL,
  standard_unit_code VARCHAR(32) NOT NULL, tce_factor DECIMAL(20,10) NOT NULL, factor_version VARCHAR(32) NOT NULL,
  effective_from DATE NOT NULL, enabled TINYINT(1) NOT NULL DEFAULT 1, PRIMARY KEY(energy_type_id),
  UNIQUE KEY uk_energy_code(energy_code), CONSTRAINT fk_energy_type_unit FOREIGN KEY(standard_unit_code) REFERENCES dim_unit(unit_code),
  CONSTRAINT ck_energy_code CHECK(energy_code IN ('ELECTRICITY','WATER','COAL_GAS')), CONSTRAINT ck_energy_factor CHECK(tce_factor>=0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE ods_sensor_data (
  record_id BIGINT NOT NULL AUTO_INCREMENT, source_system VARCHAR(64) NOT NULL, source_record_id VARCHAR(128),
  sensor_code VARCHAR(128) NOT NULL, parameter_code VARCHAR(64) NOT NULL, collect_time TIMESTAMP NOT NULL,
  value_num DECIMAL(20,6), value_text VARCHAR(500), source_unit VARCHAR(32), quality_code VARCHAR(32),
  ingest_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP, is_deleted TINYINT(1) NOT NULL DEFAULT 0,
  PRIMARY KEY(record_id), UNIQUE KEY uk_ods_sensor_source_record(source_system,source_record_id),
  KEY idx_ods_sensor_time(sensor_code,parameter_code,collect_time),
  CONSTRAINT ck_ods_value CHECK((value_num IS NOT NULL AND value_text IS NULL) OR (value_num IS NULL AND value_text IS NOT NULL))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE ads_parameter_monitor (
  sensor_id BIGINT NOT NULL, parameter_id BIGINT NOT NULL, workshop_id BIGINT NOT NULL, equipment_id BIGINT NOT NULL,
  unit_code VARCHAR(32), latest_value DECIMAL(20,6), latest_value_text VARCHAR(500), quality_status VARCHAR(16) NOT NULL,
  limit_status VARCHAR(16) NOT NULL, latest_collect_time TIMESTAMP NOT NULL, refresh_time TIMESTAMP NOT NULL,
  PRIMARY KEY(sensor_id,parameter_id), KEY idx_ads_workshop(workshop_id), KEY idx_ads_equipment(equipment_id),
  CONSTRAINT fk_ads_sensor FOREIGN KEY(sensor_id) REFERENCES dim_sensor(sensor_id), CONSTRAINT fk_ads_parameter FOREIGN KEY(parameter_id) REFERENCES dim_sensor_parameter(parameter_id),
  CONSTRAINT fk_ads_workshop FOREIGN KEY(workshop_id) REFERENCES dim_workshop(workshop_id), CONSTRAINT fk_ads_equipment FOREIGN KEY(equipment_id) REFERENCES dim_equipment(equipment_id),
  CONSTRAINT ck_ads_quality CHECK(quality_status IN ('GOOD','BAD','UNCERTAIN')), CONSTRAINT ck_ads_limit CHECK(limit_status IN ('NORMAL','WARNING','ALARM','UNKNOWN'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE dim_energy_tariff (
  tariff_id BIGINT NOT NULL AUTO_INCREMENT, energy_type_id BIGINT NOT NULL, period_type VARCHAR(16) NOT NULL,
  start_minute INT NOT NULL, end_minute INT NOT NULL, unit_price DECIMAL(20,6) NOT NULL, effective_from DATE NOT NULL, effective_to DATE,
  PRIMARY KEY(tariff_id), KEY idx_tariff_energy(energy_type_id), CONSTRAINT fk_tariff_energy FOREIGN KEY(energy_type_id) REFERENCES dim_energy_type(energy_type_id),
  CONSTRAINT ck_tariff_period CHECK(period_type IN ('PEAK','FLAT','VALLEY','STANDARD')), CONSTRAINT ck_tariff_start CHECK(start_minute>=0 AND start_minute<1440),
  CONSTRAINT ck_tariff_end CHECK(end_minute>0 AND end_minute<=1440), CONSTRAINT ck_tariff_range CHECK(start_minute<end_minute), CONSTRAINT ck_tariff_price CHECK(unit_price>=0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE dws_energy_hour (
  sensor_id BIGINT NOT NULL, bucket_time TIMESTAMP NOT NULL, consumption DECIMAL(20,6) NOT NULL, cost DECIMAL(20,6) NOT NULL DEFAULT 0,
  tce_amount DECIMAL(20,10) NOT NULL DEFAULT 0, peak_amount DECIMAL(20,6) NOT NULL DEFAULT 0, flat_amount DECIMAL(20,6) NOT NULL DEFAULT 0,
  valley_amount DECIMAL(20,6) NOT NULL DEFAULT 0, calculation_method VARCHAR(24) NOT NULL, quality_status VARCHAR(16) NOT NULL,
  factor_version VARCHAR(32) NOT NULL, refresh_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY(sensor_id,bucket_time), CONSTRAINT fk_energy_hour_sensor FOREIGN KEY(sensor_id) REFERENCES dim_sensor(sensor_id),
  CONSTRAINT ck_energy_hour_method CHECK(calculation_method IN ('CUMULATIVE_DIFF','INSTANT_INTEGRAL','NO_DATA')),
  CONSTRAINT ck_energy_hour_quality CHECK(quality_status IN ('GOOD','PARTIAL','BAD','NO_DATA'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE dws_energy_day (
  sensor_id BIGINT NOT NULL, statistic_date DATE NOT NULL, consumption DECIMAL(20,6) NOT NULL, cost DECIMAL(20,6) NOT NULL DEFAULT 0,
  tce_amount DECIMAL(20,10) NOT NULL DEFAULT 0, peak_amount DECIMAL(20,6) NOT NULL DEFAULT 0, flat_amount DECIMAL(20,6) NOT NULL DEFAULT 0,
  valley_amount DECIMAL(20,6) NOT NULL DEFAULT 0, production_quantity DECIMAL(20,6), production_stage VARCHAR(32), quality_status VARCHAR(16) NOT NULL,
  factor_version VARCHAR(32) NOT NULL, refresh_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY(sensor_id,statistic_date), CONSTRAINT fk_energy_day_sensor FOREIGN KEY(sensor_id) REFERENCES dim_sensor(sensor_id),
  CONSTRAINT ck_energy_day_quality CHECK(quality_status IN ('GOOD','PARTIAL','BAD','NO_DATA'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

ALTER TABLE dim_sensor ADD CONSTRAINT fk_sensor_energy_type FOREIGN KEY(energy_type_id) REFERENCES dim_energy_type(energy_type_id);
ALTER TABLE dim_sensor ADD CONSTRAINT fk_sensor_energy_workshop FOREIGN KEY(energy_workshop_id) REFERENCES dim_workshop(workshop_id);
ALTER TABLE dim_sensor ADD CONSTRAINT fk_sensor_parent_energy FOREIGN KEY(parent_energy_sensor_id) REFERENCES dim_sensor(sensor_id);
