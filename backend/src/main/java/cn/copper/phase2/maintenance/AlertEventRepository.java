package cn.copper.phase2.maintenance;

import cn.copper.phase2.maintenance.AlertEventModels.AlertEventDetail;
import cn.copper.phase2.maintenance.AlertEventModels.AlertEventItem;
import cn.copper.phase2.maintenance.AlertEventModels.AlertEventQuery;
import cn.copper.phase2.maintenance.AlertEventModels.HandlingRecordItem;
import cn.copper.phase2.maintenance.AlertEventModels.RepairOrderRef;
import cn.copper.phase2.maintenance.AlertEventModels.RuleSnapshot;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * 提醒页签的 SQL 访问层。事件事实只读，人工处理记录单独成表。
 *
 * <p>两个状态列在库里不存在，全部由下面两段片段推导；片段同时用于查询列和筛选条件，
 * 保证"筛出来的"与"显示出来的"永远一致。
 */
@Repository
public class AlertEventRepository {
    /** 事件状态推导：未结束 → 进行中；有恢复时间 → 自然恢复；否则被规则变更终止。 */
    static final String EVENT_STATE_COLUMN = "CASE WHEN e.ended_at IS NULL THEN '"
            + AlertEventStates.ONGOING + "' WHEN e.recovered_at IS NOT NULL THEN '"
            + AlertEventStates.RECOVERED + "' ELSE '" + AlertEventStates.ENDED + "' END";

    /** 人工状态推导：取最近一条处理记录的动作，没有记录即待处理。 */
    static final String HANDLING_STATE_COLUMN = "COALESCE((SELECT h.action FROM p2_alert_handling_record h"
            + " WHERE h.event_id = e.event_id"
            + " ORDER BY h.recorded_at DESC, h.handling_record_id DESC LIMIT 1), '"
            + AlertEventStates.PENDING + "')";

    static final String EVENT_SELECT = """
            SELECT e.event_id, e.equipment_id, eq.equipment_name,
                   e.sensor_id, s.sensor_code,
                   e.parameter_id, p.parameter_code,
                   e.started_at, e.triggered_at, e.recovered_at, e.ended_at, e.end_reason,
                   %s AS event_state, %s AS handling_state,
                   (SELECT GROUP_CONCAT(r.repair_order_id ORDER BY r.repair_order_id)
                      FROM p2_repair_order r
                     WHERE r.source_alert_event_id = e.event_id) AS repair_order_ids,
                   e.version
            FROM p2_alert_event e
            JOIN dim_equipment eq ON eq.equipment_id = e.equipment_id
            JOIN dim_sensor s ON s.sensor_id = e.sensor_id
            JOIN dim_sensor_parameter p ON p.parameter_id = e.parameter_id
            """.formatted(EVENT_STATE_COLUMN, HANDLING_STATE_COLUMN);

    static final RowMapper<AlertEventItem> EVENT_ITEM_MAPPER = (rs, row) -> new AlertEventItem(
            rs.getString("event_id"),
            rs.getString("equipment_id"),
            rs.getString("equipment_name"),
            rs.getString("sensor_id"),
            rs.getString("sensor_code"),
            rs.getString("parameter_id"),
            rs.getString("parameter_code"),
            MaintenanceTimes.toOffset(rs, "started_at"),
            MaintenanceTimes.toOffset(rs, "triggered_at"),
            MaintenanceTimes.toOffset(rs, "recovered_at"),
            MaintenanceTimes.toOffset(rs, "ended_at"),
            rs.getString("end_reason"),
            rs.getString("event_state"),
            rs.getString("handling_state"),
            splitIds(rs.getString("repair_order_ids")),
            rs.getInt("version"));

    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;

    public AlertEventRepository(JdbcTemplate jdbc, ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
    }

    /** 按触发时刻、ID 倒序稳定分页（文档 S1-A01 指定的展示顺序）。 */
    public List<AlertEventItem> findEvents(AlertEventQuery query, int offset, int limit) {
        List<Object> args = new ArrayList<>();
        String sql = EVENT_SELECT + whereClause(query, args)
                + " ORDER BY e.triggered_at DESC, e.event_id DESC LIMIT ? OFFSET ?";
        args.add(limit);
        args.add(offset);
        return jdbc.query(sql, EVENT_ITEM_MAPPER, args.toArray());
    }

    public long countEvents(AlertEventQuery query) {
        List<Object> args = new ArrayList<>();
        String sql = "SELECT COUNT(*) FROM p2_alert_event e" + whereClause(query, args);
        Long total = jdbc.queryForObject(sql, Long.class, args.toArray());
        return total == null ? 0L : total;
    }

    /** 详情：主体、规则快照与关联工单在同一处组装，避免调用方拼半个对象。 */
    public AlertEventDetail findEvent(long eventId) {
        String sql = """
                SELECT e.event_id, e.equipment_id, eq.equipment_name,
                       e.sensor_id, s.sensor_code,
                       e.parameter_id, p.parameter_code,
                       e.started_at, e.triggered_at, e.recovered_at, e.ended_at, e.end_reason,
                       %s AS event_state, %s AS handling_state,
                       e.trigger_value, e.unit_code, e.source_reference,
                       e.rule_version_id, e.version
                FROM p2_alert_event e
                JOIN dim_equipment eq ON eq.equipment_id = e.equipment_id
                JOIN dim_sensor s ON s.sensor_id = e.sensor_id
                JOIN dim_sensor_parameter p ON p.parameter_id = e.parameter_id
                WHERE e.event_id = ?
                """.formatted(EVENT_STATE_COLUMN, HANDLING_STATE_COLUMN);
        List<EventRow> rows = jdbc.query(sql, EventRow::map, eventId);
        if (rows.isEmpty()) {
            return null;
        }
        EventRow row = rows.get(0);
        return new AlertEventDetail(
                row.eventId(), row.equipmentId(), row.equipmentName(),
                row.sensorId(), row.sensorCode(), row.parameterId(), row.parameterCode(),
                row.startedAt(), row.triggeredAt(), row.recoveredAt(), row.endedAt(), row.endReason(),
                row.eventState(), row.handlingState(),
                row.triggerValue(), row.unitCode(), readJson(row.sourceReferenceJson()),
                String.valueOf(row.ruleVersionId()),
                findRuleSnapshot(row.ruleVersionId()),
                findRepairOrders(eventId),
                row.version());
    }

    /** 详情主体的中间载体；只在读出一行时短暂存在，不对外暴露。 */
    private record EventRow(
            String eventId,
            String equipmentId,
            String equipmentName,
            String sensorId,
            String sensorCode,
            String parameterId,
            String parameterCode,
            OffsetDateTime startedAt,
            OffsetDateTime triggeredAt,
            OffsetDateTime recoveredAt,
            OffsetDateTime endedAt,
            String endReason,
            String eventState,
            String handlingState,
            String triggerValue,
            String unitCode,
            String sourceReferenceJson,
            long ruleVersionId,
            int version) {

        static EventRow map(ResultSet rs, int row) throws SQLException {
            return new EventRow(
                    rs.getString("event_id"),
                    rs.getString("equipment_id"),
                    rs.getString("equipment_name"),
                    rs.getString("sensor_id"),
                    rs.getString("sensor_code"),
                    rs.getString("parameter_id"),
                    rs.getString("parameter_code"),
                    MaintenanceTimes.toOffset(rs, "started_at"),
                    MaintenanceTimes.toOffset(rs, "triggered_at"),
                    MaintenanceTimes.toOffset(rs, "recovered_at"),
                    MaintenanceTimes.toOffset(rs, "ended_at"),
                    rs.getString("end_reason"),
                    rs.getString("event_state"),
                    rs.getString("handling_state"),
                    decimal(rs, "trigger_value"),
                    rs.getString("unit_code"),
                    rs.getString("source_reference"),
                    rs.getLong("rule_version_id"),
                    rs.getInt("version"));
        }
    }

    /** 触发时生效的规则版本；多参数结构下按规则版本号取值。 */
    public RuleSnapshot findRuleSnapshot(long ruleVersionId) {
        List<RuleSnapshot> rows = jdbc.query("""
                SELECT rule_version_id, rule_version, normal_lower, normal_upper,
                       alarm_lower, alarm_upper, unit_code, trigger_mode, duration_seconds, effective_at
                FROM p2_alert_rule_version
                WHERE rule_version_id = ?
                """, (rs, row) -> new RuleSnapshot(
                rs.getString("rule_version_id"),
                rs.getString("rule_version"),
                decimal(rs, "normal_lower"),
                decimal(rs, "normal_upper"),
                decimal(rs, "alarm_lower"),
                decimal(rs, "alarm_upper"),
                rs.getString("unit_code"),
                rs.getString("trigger_mode"),
                (Integer) rs.getObject("duration_seconds"),
                MaintenanceTimes.toOffset(rs, "effective_at")), ruleVersionId);
        return rows.isEmpty() ? null : rows.get(0);
    }

    /** 该事件发起的工单；维修状态独立读取，不由事件状态推导。 */
    public List<RepairOrderRef> findRepairOrders(long eventId) {
        return jdbc.query("""
                SELECT repair_order_id, order_no, status
                FROM p2_repair_order
                WHERE source_alert_event_id = ?
                ORDER BY repair_order_id
                """, (rs, row) -> new RepairOrderRef(
                rs.getString("repair_order_id"),
                rs.getString("order_no"),
                rs.getString("status")), eventId);
    }

    public boolean eventExists(long eventId) {
        Long count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM p2_alert_event WHERE event_id = ?", Long.class, eventId);
        return count != null && count > 0;
    }

    /** 处理历史：按记录时刻倒序，保留每一次动作。 */
    public List<HandlingRecordItem> findHandlingRecords(long eventId, int offset, int limit) {
        return jdbc.query("""
                SELECT handling_record_id, action, reason, operator_name, recorded_at
                FROM p2_alert_handling_record
                WHERE event_id = ?
                ORDER BY recorded_at DESC, handling_record_id DESC
                LIMIT ? OFFSET ?
                """, (rs, row) -> new HandlingRecordItem(
                rs.getString("handling_record_id"),
                rs.getString("action"),
                rs.getString("reason"),
                rs.getString("operator_name"),
                MaintenanceTimes.toOffset(rs, "recorded_at")), eventId, limit, offset);
    }

    public long countHandlingRecords(long eventId) {
        Long total = jdbc.queryForObject(
                "SELECT COUNT(*) FROM p2_alert_handling_record WHERE event_id = ?", Long.class, eventId);
        return total == null ? 0L : total;
    }

    private static String whereClause(AlertEventQuery query, List<Object> args) {
        List<String> conditions = new ArrayList<>();
        if (query.equipmentId() != null) {
            conditions.add("e.equipment_id = ?");
            args.add(Long.valueOf(query.equipmentId()));
        }
        if (query.sensorId() != null) {
            conditions.add("e.sensor_id = ?");
            args.add(Long.valueOf(query.sensorId()));
        }
        if (query.parameterId() != null) {
            conditions.add("e.parameter_id = ?");
            args.add(Long.valueOf(query.parameterId()));
        }
        if (query.eventState() != null) {
            conditions.add(EVENT_STATE_COLUMN + " = ?");
            args.add(query.eventState());
        }
        if (query.handlingState() != null) {
            conditions.add(HANDLING_STATE_COLUMN + " = ?");
            args.add(query.handlingState());
        }
        if (query.from() != null) {
            conditions.add("e.triggered_at >= ?");
            args.add(MaintenanceTimes.toLocal(MaintenanceTimes.parseMoment("from", query.from())));
        }
        if (query.to() != null) {
            conditions.add("e.triggered_at < ?");
            args.add(MaintenanceTimes.toLocal(MaintenanceTimes.parseMoment("to", query.to())));
        }
        return conditions.isEmpty() ? "" : " WHERE " + String.join(" AND ", conditions);
    }

    /** DECIMAL 列按十进制字符串返回，去掉多余尾零（78.500000 → 78.5）。 */
    static String decimal(ResultSet rs, String column) throws SQLException {
        BigDecimal value = rs.getBigDecimal(column);
        return value == null ? null : value.stripTrailingZeros().toPlainString();
    }

    /** source_reference 是 JSON 列，还原成对象返回，避免前端拿到转义字符串。 */
    private Object readJson(String text) {
        if (text == null || text.isBlank()) {
            return null;
        }
        try {
            return objectMapper.readValue(text, Object.class);
        } catch (JsonProcessingException error) {
            return text;
        }
    }

    /** GROUP_CONCAT 返回 "3,7" 形式；无关联时为 null。 */
    static List<String> splitIds(String joined) {
        if (joined == null || joined.isBlank()) {
            return List.of();
        }
        return Arrays.stream(joined.split(",")).map(String::trim).filter(text -> !text.isEmpty()).toList();
    }
}
