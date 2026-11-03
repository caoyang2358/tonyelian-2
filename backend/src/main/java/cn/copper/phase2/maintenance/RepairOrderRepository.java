package cn.copper.phase2.maintenance;

import cn.copper.phase2.maintenance.RepairOrderModels.RepairActionItem;
import cn.copper.phase2.maintenance.RepairOrderModels.RepairOrderItem;
import cn.copper.phase2.maintenance.RepairOrderModels.RepairOrderQuery;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * 维修工单的 SQL 访问层。SQL 全部集中在本类，Service 不直接接触 JdbcTemplate。
 */
@Repository
public class RepairOrderRepository {
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

    private static final String ORDER_DETAIL_SELECT = """
            SELECT r.repair_order_id, r.order_no, r.equipment_id, e.equipment_name, r.source_type,
                   CASE r.source_type
                       WHEN 'ALERT_EVENT' THEN r.source_alert_event_id
                       WHEN 'INSPECTION' THEN r.source_inspection_id
                       ELSE NULL
                   END AS source_id,
                   r.source_snapshot, r.status, r.description, r.supplement, r.reporter_name,
                   r.created_at, r.updated_at, r.first_submitted_at, r.completed_at, r.version
            FROM p2_repair_order r
            JOIN dim_equipment e ON e.equipment_id = r.equipment_id
            WHERE r.repair_order_id = ?
            """;

    private static final String ACTION_SELECT = """
            SELECT a.action_id, a.action_type, a.result, a.previous_status, a.next_status,
                   a.occurred_at, a.recorded_at, a.operator_name, a.reason, a.description,
                   (SELECT GROUP_CONCAT(aa.attachment_id ORDER BY aa.attachment_id)
                      FROM p2_repair_action_attachment aa
                     WHERE aa.action_id = a.action_id) AS attachment_ids
            FROM p2_repair_action a
            WHERE a.repair_order_id = ?
            """;

    static final RowMapper<RepairActionItem> ACTION_MAPPER = (rs, row) -> new RepairActionItem(
            rs.getString("action_id"),
            rs.getString("action_type"),
            rs.getString("result"),
            rs.getString("previous_status"),
            rs.getString("next_status"),
            MaintenanceTimes.toOffset(rs, "occurred_at"),
            MaintenanceTimes.toOffset(rs, "recorded_at"),
            rs.getString("operator_name"),
            rs.getString("reason"),
            rs.getString("description"),
            splitIds(rs.getString("attachment_ids")));

    /** 工单主表一行；sourceSnapshotJson 保留原文，由 Service 决定如何对外呈现。 */
    public record OrderRow(
            String repairOrderId,
            String orderNo,
            String equipmentId,
            String equipmentName,
            String sourceType,
            String sourceId,
            String sourceSnapshotJson,
            String status,
            String description,
            String supplement,
            String reporterName,
            OffsetDateTime createdAt,
            OffsetDateTime updatedAt,
            OffsetDateTime firstSubmittedAt,
            OffsetDateTime completedAt,
            int version) {}

    /** 行锁读到的状态与版本，用于并发校验。 */
    public record StatusRow(String status, int version) {}

    /** 巡检来源的证据快照原料。 */
    public record InspectionSource(
            String inspectionId,
            String equipmentId,
            String equipmentName,
            OffsetDateTime inspectedAt,
            String inspectorName,
            String content,
            String result,
            String description,
            int version) {}

    /** 告警事件来源的证据快照原料。 */
    public record AlertSource(
            String eventId,
            String equipmentId,
            String equipmentName,
            String sensorId,
            String sensorCode,
            String sensorName,
            String parameterId,
            String parameterCode,
            String parameterName,
            OffsetDateTime startedAt,
            OffsetDateTime triggeredAt,
            String triggerValue,
            String unitCode,
            OffsetDateTime endedAt,
            String endReason,
            int version) {}

    private final JdbcTemplate jdbc;

    public RepairOrderRepository(JdbcTemplate jdbc) {
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

    public boolean equipmentExists(long equipmentId) {
        Long count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM dim_equipment WHERE equipment_id = ?", Long.class, equipmentId);
        return count != null && count > 0;
    }

    public OrderRow findOrder(long repairOrderId) {
        List<OrderRow> rows = jdbc.query(ORDER_DETAIL_SELECT, (rs, row) -> new OrderRow(
                rs.getString("repair_order_id"),
                rs.getString("order_no"),
                rs.getString("equipment_id"),
                rs.getString("equipment_name"),
                rs.getString("source_type"),
                rs.getString("source_id"),
                rs.getString("source_snapshot"),
                rs.getString("status"),
                rs.getString("description"),
                rs.getString("supplement"),
                rs.getString("reporter_name"),
                MaintenanceTimes.toOffset(rs, "created_at"),
                MaintenanceTimes.toOffset(rs, "updated_at"),
                MaintenanceTimes.toOffset(rs, "first_submitted_at"),
                MaintenanceTimes.toOffset(rs, "completed_at"),
                rs.getInt("version")), repairOrderId);
        return rows.isEmpty() ? null : rows.get(0);
    }

    /** 写入动作前先锁行，确保「校验状态与版本 → 写历史 → 推进状态」在同一事务内串行。 */
    public StatusRow lockOrder(long repairOrderId) {
        List<StatusRow> rows = jdbc.query(
                "SELECT status, version FROM p2_repair_order WHERE repair_order_id = ? FOR UPDATE",
                (rs, row) -> new StatusRow(rs.getString("status"), rs.getInt("version")),
                repairOrderId);
        return rows.isEmpty() ? null : rows.get(0);
    }

    /**
     * 建单。单号要用到自增主键，而主键在插入前不可知，因此先用请求 ID 占位，
     * 取得主键后再在同一事务内改成正式单号。
     */
    public long insertOrder(String placeholderOrderNo, long equipmentId, String sourceType,
                            Long sourceAlertEventId, Long sourceInspectionId, String sourceSnapshotJson,
                            LocalDateTime now) {
        KeyHolder keys = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            PreparedStatement ps = connection.prepareStatement("""
                    INSERT INTO p2_repair_order
                        (order_no, equipment_id, source_type, source_alert_event_id, source_inspection_id,
                         source_snapshot, status, created_at, updated_at, version)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, 1)
                    """, Statement.RETURN_GENERATED_KEYS);
            ps.setString(1, placeholderOrderNo);
            ps.setLong(2, equipmentId);
            ps.setString(3, sourceType);
            ps.setObject(4, sourceAlertEventId);
            ps.setObject(5, sourceInspectionId);
            ps.setString(6, sourceSnapshotJson);
            ps.setString(7, RepairOrderWorkflow.DRAFT);
            ps.setObject(8, now);
            ps.setObject(9, now);
            return ps;
        }, keys);
        Number key = keys.getKey();
        if (key == null) {
            throw new IllegalStateException("建单后未取得工单主键");
        }
        return key.longValue();
    }

    public void updateOrderNo(long repairOrderId, String orderNo) {
        jdbc.update("UPDATE p2_repair_order SET order_no = ? WHERE repair_order_id = ?", orderNo, repairOrderId);
    }

    /** S1-R04 保存资料：不改状态，只推进版本与更新时间。 */
    public void saveContent(long repairOrderId, String description, String supplement, String reporterName,
                            LocalDateTime now) {
        jdbc.update("""
                UPDATE p2_repair_order
                   SET description = ?, supplement = ?, reporter_name = ?, updated_at = ?, version = version + 1
                 WHERE repair_order_id = ?
                """, description, supplement, reporterName, now, repairOrderId);
    }

    /**
     * 推进状态并递增版本。firstSubmittedAt 只在首次提交时写入，
     * completedAt 只在验收通过时写入，传 null 表示保持原值。
     */
    public void advanceStatus(long repairOrderId, String status, LocalDateTime now,
                              LocalDateTime firstSubmittedAt, LocalDateTime completedAt) {
        jdbc.update("""
                UPDATE p2_repair_order
                   SET status = ?, updated_at = ?, version = version + 1,
                       first_submitted_at = COALESCE(first_submitted_at, ?),
                       completed_at = COALESCE(?, completed_at)
                 WHERE repair_order_id = ?
                """, status, now, firstSubmittedAt, completedAt, repairOrderId);
    }

    public long insertAction(long repairOrderId, String actionType, String result, String previousStatus,
                             String nextStatus, LocalDateTime occurredAt, LocalDateTime recordedAt,
                             String operatorName, String reason, String description) {
        KeyHolder keys = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            PreparedStatement ps = connection.prepareStatement("""
                    INSERT INTO p2_repair_action
                        (repair_order_id, action_type, result, previous_status, next_status,
                         occurred_at, recorded_at, operator_name, reason, description)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """, Statement.RETURN_GENERATED_KEYS);
            ps.setLong(1, repairOrderId);
            ps.setString(2, actionType);
            ps.setString(3, result);
            ps.setString(4, previousStatus);
            ps.setString(5, nextStatus);
            ps.setObject(6, occurredAt);
            ps.setObject(7, recordedAt);
            ps.setString(8, operatorName);
            ps.setString(9, reason);
            ps.setString(10, description);
            return ps;
        }, keys);
        Number key = keys.getKey();
        if (key == null) {
            throw new IllegalStateException("写入动作后未取得动作主键");
        }
        return key.longValue();
    }

    public List<RepairActionItem> findActions(long repairOrderId, int offset, int limit) {
        return jdbc.query(ACTION_SELECT + " ORDER BY a.action_id LIMIT ? OFFSET ?",
                ACTION_MAPPER, repairOrderId, limit, offset);
    }

    public long countActions(long repairOrderId) {
        Long total = jdbc.queryForObject(
                "SELECT COUNT(*) FROM p2_repair_action WHERE repair_order_id = ?", Long.class, repairOrderId);
        return total == null ? 0L : total;
    }

    public List<String> findPhotoIds(long repairOrderId) {
        return splitIds(jdbc.queryForObject("""
                SELECT GROUP_CONCAT(attachment_id ORDER BY attachment_id)
                  FROM p2_repair_photo WHERE repair_order_id = ?
                """, String.class, repairOrderId));
    }

    /** 工单照片按整集合替换：先清空再写入调用方给出的有效附件 ID。 */
    public void replacePhotos(long repairOrderId, List<Long> attachmentIds) {
        jdbc.update("DELETE FROM p2_repair_photo WHERE repair_order_id = ?", repairOrderId);
        for (Long attachmentId : attachmentIds) {
            jdbc.update("INSERT INTO p2_repair_photo (repair_order_id, attachment_id) VALUES (?, ?)",
                    repairOrderId, attachmentId);
        }
    }

    /** 校验附件确实挂在本工单下，避免把别人的照片挂进来。 */
    public boolean attachmentBelongsToOrder(long attachmentId, long repairOrderId) {
        Long count = jdbc.queryForObject("""
                SELECT COUNT(*) FROM p2_attachment WHERE attachment_id = ? AND repair_order_id = ?
                """, Long.class, attachmentId, repairOrderId);
        return count != null && count > 0;
    }

    public void linkActionAttachment(long actionId, long attachmentId) {
        jdbc.update("INSERT INTO p2_repair_action_attachment (action_id, attachment_id) VALUES (?, ?)",
                actionId, attachmentId);
    }

    public InspectionSource findInspectionSource(long inspectionId) {
        List<InspectionSource> rows = jdbc.query("""
                SELECT i.inspection_id, i.equipment_id, e.equipment_name, i.inspected_at, i.inspector_name,
                       i.content, i.result, i.description, i.version
                FROM p2_inspection i
                JOIN dim_equipment e ON e.equipment_id = i.equipment_id
                WHERE i.inspection_id = ?
                """, (rs, row) -> new InspectionSource(
                rs.getString("inspection_id"),
                rs.getString("equipment_id"),
                rs.getString("equipment_name"),
                MaintenanceTimes.toOffset(rs, "inspected_at"),
                rs.getString("inspector_name"),
                rs.getString("content"),
                rs.getString("result"),
                rs.getString("description"),
                rs.getInt("version")), inspectionId);
        return rows.isEmpty() ? null : rows.get(0);
    }

    public AlertSource findAlertSource(long eventId) {
        List<AlertSource> rows = jdbc.query("""
                SELECT v.event_id, v.equipment_id, e.equipment_name,
                       v.sensor_id, s.sensor_code, s.sensor_name,
                       v.parameter_id, p.parameter_code, p.parameter_name,
                       v.started_at, v.triggered_at, v.trigger_value, v.trigger_value_text, v.unit_code,
                       v.ended_at, v.end_reason, v.version
                FROM p2_alert_event v
                JOIN dim_equipment e ON e.equipment_id = v.equipment_id
                JOIN dim_sensor s ON s.sensor_id = v.sensor_id
                JOIN dim_sensor_parameter p ON p.parameter_id = v.parameter_id
                WHERE v.event_id = ?
                """, (rs, row) -> {
            String text = rs.getString("trigger_value_text");
            if (text == null) {
                BigDecimal value = rs.getBigDecimal("trigger_value");
                text = value == null ? null : value.toPlainString();
            }
            return new AlertSource(
                    rs.getString("event_id"),
                    rs.getString("equipment_id"),
                    rs.getString("equipment_name"),
                    rs.getString("sensor_id"),
                    rs.getString("sensor_code"),
                    rs.getString("sensor_name"),
                    rs.getString("parameter_id"),
                    rs.getString("parameter_code"),
                    rs.getString("parameter_name"),
                    MaintenanceTimes.toOffset(rs, "started_at"),
                    MaintenanceTimes.toOffset(rs, "triggered_at"),
                    text,
                    rs.getString("unit_code"),
                    MaintenanceTimes.toOffset(rs, "ended_at"),
                    rs.getString("end_reason"),
                    rs.getInt("version"));
        }, eventId);
        return rows.isEmpty() ? null : rows.get(0);
    }

    public long insertChangeLog(String objectType, String objectId, String action, String beforeValue,
                                String afterValue, String reason, String operatorName) {
        KeyHolder keys = new GeneratedKeyHolder();
        LocalDateTime now = LocalDateTime.now(MaintenanceTimes.ZONE);
        jdbc.update(connection -> {
            PreparedStatement ps = connection.prepareStatement("""
                    INSERT INTO p2_change_log
                        (object_type, object_id, action, before_value, after_value, reason, operator_name, recorded_at)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                    """, Statement.RETURN_GENERATED_KEYS);
            ps.setString(1, objectType);
            ps.setString(2, objectId);
            ps.setString(3, action);
            ps.setString(4, beforeValue);
            ps.setString(5, afterValue);
            ps.setString(6, reason);
            ps.setString(7, operatorName);
            ps.setObject(8, now);
            return ps;
        }, keys);
        Number key = keys.getKey();
        if (key == null) {
            throw new IllegalStateException("写入留痕后未取得留痕主键");
        }
        return key.longValue();
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

    static List<String> splitIds(String joined) {
        if (joined == null || joined.isBlank()) {
            return List.of();
        }
        List<String> ids = new ArrayList<>();
        for (String part : joined.split(",")) {
            String trimmed = part.trim();
            if (!trimmed.isEmpty()) {
                ids.add(trimmed);
            }
        }
        return ids;
    }
}
