package cn.copper.phase2.equipment;

import cn.copper.phase2.equipment.EquipmentModels.EquipmentItem;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public class EquipmentRepository {
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

    public EquipmentRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public List<EquipmentItem> productionEquipment() {
        return jdbc.query(EQUIPMENT_SQL, EQUIPMENT_MAPPER);
    }
}
