package cn.copper.phase2.maintenance;

import cn.copper.phase2.maintenance.InspectionModels.InspectionDetail;
import cn.copper.phase2.maintenance.InspectionModels.InspectionItem;
import cn.copper.phase2.maintenance.InspectionModels.InspectionQuery;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

import java.sql.PreparedStatement;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.LocalDateTime;
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
                   i.operator_name, i.content, i.result, i.description, i.recorded_at, i.version,
                   (SELECT GROUP_CONCAT(r.repair_order_id ORDER BY r.repair_order_id)
                      FROM p2_repair_order r
                     WHERE r.source_inspection_id = i.inspection_id) AS repair_order_ids,
                   (SELECT GROUP_CONCAT(r.status ORDER BY r.repair_order_id)
                      FROM p2_repair_order r
                     WHERE r.source_inspection_id = i.inspection_id) AS repair_order_statuses,
                   (SELECT COUNT(*) FROM p2_attachment a
                     WHERE a.inspection_id = i.inspection_id) AS photo_count
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
            splitIds(rs.getString("repair_order_statuses")),
            rs.getInt("photo_count"),
            rs.getInt("version"));

    static final RowMapper<InspectionDetail> INSPECTION_DETAIL_MAPPER = (rs, row) -> new InspectionDetail(
            rs.getString("inspection_id"),
            rs.getString("equipment_id"),
            rs.getString("equipment_name"),
            MaintenanceTimes.toOffset(rs, "inspected_at"),
            rs.getString("inspector_name"),
            rs.getString("operator_name"),
            rs.getString("content"),
            rs.getString("result"),
            rs.getString("description"),
            splitIds(rs.getString("repair_order_ids")),
            rs.getInt("photo_count"),
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

    public boolean equipmentExists(long equipmentId) {
        Long count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM dim_equipment WHERE equipment_id = ?", Long.class, equipmentId);
        return count != null && count > 0;
    }

    /** 新增巡检并返回主键；recorded_at 为服务器录入时间，与 inspected_at 分开保存。 */
    public long insertInspection(long equipmentId, LocalDateTime inspectedAt, String inspectorName,
                                 String content, String result, String description, String operatorName) {
        KeyHolder keys = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            PreparedStatement statement = connection.prepareStatement("""
                    INSERT INTO p2_inspection
                        (equipment_id, inspected_at, inspector_name, content, result, description,
                         operator_name, recorded_at, updated_at, version)
                    VALUES (?, ?, ?, ?, ?, ?, ?, CURRENT_TIMESTAMP(3), CURRENT_TIMESTAMP(3), 1)
                    """, Statement.RETURN_GENERATED_KEYS);
            statement.setLong(1, equipmentId);
            statement.setTimestamp(2, Timestamp.valueOf(inspectedAt));
            statement.setString(3, inspectorName);
            statement.setString(4, content);
            statement.setString(5, result);
            statement.setString(6, description);
            statement.setString(7, operatorName);
            return statement;
        }, keys);
        Number key = keys.getKey();
        if (key == null) {
            throw new IllegalStateException("巡检记录未返回主键");
        }
        return key.longValue();
    }

    /** 行锁读当前版本；返回 null 表示记录不存在。 */
    public Integer lockVersion(long inspectionId) {
        List<Integer> rows = jdbc.query(
                "SELECT version FROM p2_inspection WHERE inspection_id = ? FOR UPDATE",
                (rs, row) -> rs.getInt(1), inspectionId);
        return rows.isEmpty() ? null : rows.get(0);
    }

    /**
     * 更正巡检资料：设备身份不变，版本号递增。
     *
     * <p>只改巡检事实字段，不动 {@code operator_name}：那一列表示"原登记人"，
     * 更正人由调用方写进 p2_change_log，两者语义不同不能互相覆盖。</p>
     */
    public void updateInspection(long inspectionId, LocalDateTime inspectedAt, String inspectorName,
                                 String content, String result, String description) {
        jdbc.update("""
                UPDATE p2_inspection
                   SET inspected_at = ?, inspector_name = ?, content = ?, result = ?, description = ?,
                       updated_at = CURRENT_TIMESTAMP(3), version = version + 1
                 WHERE inspection_id = ?
                """, Timestamp.valueOf(inspectedAt), inspectorName, content, result, description,
                inspectionId);
    }

    /** 重要更正留痕（p2_change_log），返回留痕编号供响应回显。 */
    public long insertChangeLog(String objectType, String objectId, String action,
                                String beforeJson, String afterJson, String reason, String operatorName) {
        KeyHolder keys = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            PreparedStatement statement = connection.prepareStatement("""
                    INSERT INTO p2_change_log
                        (object_type, object_id, action, before_value, after_value, reason, operator_name)
                    VALUES (?, ?, ?, ?, ?, ?, ?)
                    """, Statement.RETURN_GENERATED_KEYS);
            statement.setString(1, objectType);
            statement.setString(2, objectId);
            statement.setString(3, action);
            statement.setString(4, beforeJson);
            statement.setString(5, afterJson);
            statement.setString(6, reason);
            statement.setString(7, operatorName);
            return statement;
        }, keys);
        Number key = keys.getKey();
        if (key == null) {
            throw new IllegalStateException("变更留痕未返回主键");
        }
        return key.longValue();
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
