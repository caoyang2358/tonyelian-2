package cn.copper.phase2.initialization;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.util.List;

import static cn.copper.phase2.initialization.InitializationModels.*;

@Repository
public class InitializationRepository {
    static final String DATABASE_INFO_SQL = "SELECT DATABASE() AS schema_name, VERSION() AS server_version";
    static final String PHASE1_TABLE_COUNT_SQL = """
            SELECT COUNT(*) FROM information_schema.tables
            WHERE table_schema = DATABASE() AND table_type = 'BASE TABLE'
              AND table_name IN ('dim_workshop', 'dim_equipment', 'dim_process', 'dim_unit',
                'dim_sensor', 'dim_sensor_parameter', 'ads_parameter_monitor', 'ods_sensor_data',
                'dim_energy_type', 'dim_energy_tariff', 'dws_energy_hour', 'dws_energy_day')
            """;
    static final String PHASE2_TABLE_COUNT_SQL = """
            SELECT COUNT(*) FROM information_schema.tables
            WHERE table_schema = DATABASE() AND table_type = 'BASE TABLE'
              AND LEFT(table_name, 3) = 'p2_' AND table_name <> 'p2_flyway_schema_history'
            """;
    static final String EQUIPMENT_SQL = """
            SELECT e.equipment_id AS id, e.equipment_code AS code, e.equipment_name AS name,
                   e.workshop_id, w.workshop_name, e.responsible_person, e.enabled
            FROM dim_equipment e
            JOIN dim_workshop w ON w.workshop_id = e.workshop_id
            WHERE e.equipment_usage = 'PRODUCTION'
            ORDER BY w.workshop_code, e.equipment_code
            """;
    static final RowMapper<EquipmentItem> EQUIPMENT_MAPPER = (rs, row) -> new EquipmentItem(
            rs.getLong("id"), rs.getString("code"), rs.getString("name"), rs.getLong("workshop_id"),
            rs.getString("workshop_name"), rs.getString("responsible_person"), rs.getBoolean("enabled"));
    private final JdbcTemplate jdbc;

    public InitializationRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public boolean connectionReady() {
        return Integer.valueOf(1).equals(jdbc.queryForObject("SELECT 1", Integer.class));
    }

    public DatabaseOverview databaseOverview() {
        DatabaseIdentity identity = jdbc.queryForObject(DATABASE_INFO_SQL,
                (rs, row) -> new DatabaseIdentity(rs.getString("schema_name"), rs.getString("server_version")));
        if (identity == null || identity.schemaName() == null) {
            throw new org.springframework.dao.DataAccessResourceFailureException("No selected database");
        }
        return new DatabaseOverview("UP", identity.schemaName(), identity.serverVersion(),
                count(PHASE1_TABLE_COUNT_SQL), count(PHASE2_TABLE_COUNT_SQL));
    }

    public Counts counts() {
        return new Counts(count("SELECT COUNT(*) FROM dim_workshop"),
                count("SELECT COUNT(*) FROM dim_equipment WHERE equipment_usage = 'PRODUCTION'"),
                count("SELECT COUNT(*) FROM dim_sensor"));
    }

    public List<EquipmentItem> productionEquipment() {
        return jdbc.query(EQUIPMENT_SQL, EQUIPMENT_MAPPER);
    }

    private long count(String sql) {
        Long count = jdbc.queryForObject(sql, Long.class);
        if (count == null) {
            throw new org.springframework.dao.DataAccessResourceFailureException("Count result unavailable");
        }
        return count;
    }

    private record DatabaseIdentity(String schemaName, String serverVersion) {}
}
