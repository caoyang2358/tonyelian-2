package cn.copper.phase2.initialization;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

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

    private long count(String sql) {
        Long count = jdbc.queryForObject(sql, Long.class);
        if (count == null) {
            throw new org.springframework.dao.DataAccessResourceFailureException("Count result unavailable");
        }
        return count;
    }

    private record DatabaseIdentity(String schemaName, String serverVersion) {}
}
