package cn.copper.phase2.reference;

import cn.copper.phase2.reference.ReferenceDataModels.ProcessOption;
import cn.copper.phase2.reference.ReferenceDataModels.UnitOption;
import cn.copper.phase2.reference.ReferenceDataModels.WorkshopOption;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * 只读一期基础字典。二期不复制这些主数据，也不在此处新增任何写入。
 * 工序为空时按真实记录返回空集合，不按示意布局虚构 ID。
 */
@Repository
public class ReferenceDataRepository {
    static final String WORKSHOP_SQL = """
            SELECT workshop_id, workshop_code, workshop_name, enabled
            FROM dim_workshop
            ORDER BY workshop_code
            """;

    static final String PROCESS_SQL = """
            SELECT process_id, process_code, process_name, workshop_id, sequence_no, enabled
            FROM dim_process
            ORDER BY workshop_id, sequence_no
            """;

    static final String UNIT_SQL = """
            SELECT unit_id, unit_code, unit_name, unit_category
            FROM dim_unit
            ORDER BY unit_code
            """;

    static final RowMapper<WorkshopOption> WORKSHOP_MAPPER = (rs, row) -> new WorkshopOption(
            String.valueOf(rs.getLong("workshop_id")),
            rs.getString("workshop_code"),
            rs.getString("workshop_name"),
            rs.getBoolean("enabled"));

    static final RowMapper<ProcessOption> PROCESS_MAPPER = (rs, row) -> new ProcessOption(
            String.valueOf(rs.getLong("process_id")),
            rs.getString("process_code"),
            rs.getString("process_name"),
            String.valueOf(rs.getLong("workshop_id")),
            rs.getInt("sequence_no"),
            rs.getBoolean("enabled"));

    static final RowMapper<UnitOption> UNIT_MAPPER = (rs, row) -> new UnitOption(
            String.valueOf(rs.getLong("unit_id")),
            rs.getString("unit_code"),
            rs.getString("unit_name"),
            rs.getString("unit_category"));

    private final JdbcTemplate jdbc;

    public ReferenceDataRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public List<WorkshopOption> workshops() {
        return jdbc.query(WORKSHOP_SQL, WORKSHOP_MAPPER);
    }

    public List<ProcessOption> processes() {
        return jdbc.query(PROCESS_SQL, PROCESS_MAPPER);
    }

    public List<UnitOption> units() {
        return jdbc.query(UNIT_SQL, UNIT_MAPPER);
    }
}
