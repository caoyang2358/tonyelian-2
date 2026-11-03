package cn.copper.phase2.maintenance;

import cn.copper.phase2.maintenance.InspectionModels.InspectionDetail;
import cn.copper.phase2.maintenance.InspectionModels.InspectionItem;
import cn.copper.phase2.maintenance.InspectionModels.InspectionQuery;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * 巡检页签的 SQL 访问层。巡检记录本身独立于工单，报修关联只读 p2_repair_order 的来源列。
 */
@Repository
public class InspectionRepository {
    static final String INSPECTION_SELECT = """
            SELECT i.inspection_id, i.equipment_id, e.equipment_name, i.inspected_at, i.inspector_name,
                   i.content, i.result, i.description, i.recorded_at, i.version,
                   (SELECT GROUP_CONCAT(r.repair_order_id ORDER BY r.repair_order_id)
                      FROM p2_repair_order r
                     WHERE r.source_inspection_id = i.inspection_id) AS repair_order_ids
            FROM p2_inspection i
            JOIN dim_equipment e ON e.equipment_id = i.equipment_id
            """;

    static final RowMapper<InspectionItem> INSPECTION_ITEM_MAPPER = (rs, row) -> new InspectionItem(
            rs.getString("inspection_id"),
            rs.getString("equipment_id"),
            rs.getString("equipment_name"),
            MaintenanceTimes.toOffset(rs, "inspected_at"),
            rs.getString("inspector_name"),
            rs.getString("result"),
            splitIds(rs.getString("repair_order_ids")),
            rs.getInt("version"));

    static final RowMapper<InspectionDetail> INSPECTION_DETAIL_MAPPER = (rs, row) -> new InspectionDetail(
            rs.getString("inspection_id"),
            rs.getString("equipment_id"),
            rs.getString("equipment_name"),
            MaintenanceTimes.toOffset(rs, "inspected_at"),
            rs.getString("inspector_name"),
            rs.getString("content"),
            rs.getString("result"),
            rs.getString("description"),
            splitIds(rs.getString("repair_order_ids")),
            MaintenanceTimes.toOffset(rs, "recorded_at"),
            rs.getInt("version"));

    private final JdbcTemplate jdbc;

    public InspectionRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** 按实际巡检时刻、ID 倒序稳定分页（文档 S1-I01 指定的展示顺序）。 */
    public List<InspectionItem> findInspections(InspectionQuery query, int offset, int limit) {
        List<Object> args = new ArrayList<>();
        String sql = INSPECTION_SELECT + whereClause(query, args)
                + " ORDER BY i.inspected_at DESC, i.inspection_id DESC LIMIT ? OFFSET ?";
        args.add(limit);
        args.add(offset);
        return jdbc.query(sql, INSPECTION_ITEM_MAPPER, args.toArray());
    }

    public long countInspections(InspectionQuery query) {
        List<Object> args = new ArrayList<>();
        String sql = "SELECT COUNT(*) FROM p2_inspection i" + whereClause(query, args);
        Long total = jdbc.queryForObject(sql, Long.class, args.toArray());
        return total == null ? 0L : total;
    }

    public InspectionDetail findInspection(long inspectionId) {
        List<InspectionDetail> rows = jdbc.query(
                INSPECTION_SELECT + " WHERE i.inspection_id = ?", INSPECTION_DETAIL_MAPPER, inspectionId);
        return rows.isEmpty() ? null : rows.get(0);
    }

    private static String whereClause(InspectionQuery query, List<Object> args) {
        List<String> conditions = new ArrayList<>();
        if (query.equipmentId() != null) {
            conditions.add("i.equipment_id = ?");
            args.add(Long.valueOf(query.equipmentId()));
        }
        if (query.result() != null) {
            conditions.add("i.result = ?");
            args.add(query.result());
        }
        if (query.from() != null) {
            conditions.add("i.inspected_at >= ?");
            args.add(MaintenanceTimes.toLocal(MaintenanceTimes.parseMoment("from", query.from())));
        }
        if (query.to() != null) {
            conditions.add("i.inspected_at < ?");
            args.add(MaintenanceTimes.toLocal(MaintenanceTimes.parseMoment("to", query.to())));
        }
        return conditions.isEmpty() ? "" : " WHERE " + String.join(" AND ", conditions);
    }

    /** GROUP_CONCAT 返回 "3,7" 形式；无关联时为 null。 */
    static List<String> splitIds(String joined) {
        if (joined == null || joined.isBlank()) {
            return List.of();
        }
        return Arrays.stream(joined.split(",")).map(String::trim).filter(text -> !text.isEmpty()).toList();
    }
}
