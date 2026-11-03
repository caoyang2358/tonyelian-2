package cn.copper.phase2.maintenance;

import cn.copper.phase2.maintenance.MaintenanceModels.RepairOrderItem;
import cn.copper.phase2.maintenance.MaintenanceModels.RepairOrderQuery;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.util.ArrayList;
import java.util.List;

/**
 * 维修工单的 SQL 访问层。SQL 全部集中在本类，Service 不直接接触 JdbcTemplate。
 */
@Repository
public class MaintenanceRepository {
    static final String REPAIR_ORDER_SELECT = """
            SELECT r.repair_order_id, r.order_no, r.equipment_id, e.equipment_name,
                   r.source_type,
                   CASE r.source_type
                       WHEN 'ALERT_EVENT' THEN r.source_alert_event_id
                       WHEN 'INSPECTION' THEN r.source_inspection_id
                       ELSE NULL
                   END AS source_id,
                   r.status, r.created_at, r.first_submitted_at, r.completed_at, r.version
            FROM p2_repair_order r
            JOIN dim_equipment e ON e.equipment_id = r.equipment_id
            """;

    static final RowMapper<RepairOrderItem> REPAIR_ORDER_MAPPER = (rs, row) -> new RepairOrderItem(
            rs.getString("repair_order_id"),
            rs.getString("order_no"),
            rs.getString("equipment_id"),
            rs.getString("equipment_name"),
            rs.getString("source_type"),
            rs.getString("source_id"),
            rs.getString("status"),
            MaintenanceTimes.toOffset(rs, "created_at"),
            MaintenanceTimes.toOffset(rs, "first_submitted_at"),
            MaintenanceTimes.toOffset(rs, "completed_at"),
            rs.getInt("version"));

    private final JdbcTemplate jdbc;

    public MaintenanceRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** 按创建时间倒序稳定分页，避免同一页重复出现同一行。 */
    public List<RepairOrderItem> findRepairOrders(RepairOrderQuery query, int offset, int limit) {
        List<Object> args = new ArrayList<>();
        String sql = REPAIR_ORDER_SELECT + whereClause(query, args)
                + " ORDER BY r.created_at DESC, r.repair_order_id DESC LIMIT ? OFFSET ?";
        args.add(limit);
        args.add(offset);
        return jdbc.query(sql, REPAIR_ORDER_MAPPER, args.toArray());
    }

    public long countRepairOrders(RepairOrderQuery query) {
        List<Object> args = new ArrayList<>();
        String sql = "SELECT COUNT(*) FROM p2_repair_order r" + whereClause(query, args);
        Long total = jdbc.queryForObject(sql, Long.class, args.toArray());
        return total == null ? 0L : total;
    }

    private static String whereClause(RepairOrderQuery query, List<Object> args) {
        List<String> conditions = new ArrayList<>();
        if (query.equipmentId() != null) {
            conditions.add("r.equipment_id = ?");
            args.add(Long.valueOf(query.equipmentId()));
        }
        if (query.status() != null) {
            conditions.add("r.status = ?");
            args.add(query.status());
        }
        if (query.sourceType() != null) {
            conditions.add("r.source_type = ?");
            args.add(query.sourceType());
        }
        if (query.from() != null) {
            conditions.add("r.created_at >= ?");
            args.add(MaintenanceTimes.toLocal(MaintenanceTimes.parseMoment("from", query.from())));
        }
        if (query.to() != null) {
            conditions.add("r.created_at < ?");
            args.add(MaintenanceTimes.toLocal(MaintenanceTimes.parseMoment("to", query.to())));
        }
        return conditions.isEmpty() ? "" : " WHERE " + String.join(" AND ", conditions);
    }
}
