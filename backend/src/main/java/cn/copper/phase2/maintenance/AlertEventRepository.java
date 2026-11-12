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
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.LocalDateTime;
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

    /**
     * 是否已有未结的维修工单。报修意味着已经有人接手，事件不该再按"无人处理"算紧急，
     * 否则会出现"工单在维修中、事件仍标红紧急"的矛盾提示。
     */
    static final String OPEN_ORDER_EXISTS = "EXISTS (SELECT 1 FROM p2_repair_order r"
            + " WHERE r.source_alert_event_id = e.event_id"
            + " AND r.status NOT IN ('COMPLETED', 'CLOSED'))";

    /**
     * 处理优先级推导：还坏着没人管最急，还坏着已受理次之，自己好了没人确认再次，其余最低。
     * 与两个状态列同源，排序和展示不会互相打架。
     *
     * <p>"有人管"包含两种情形：登记过处理记录，或已经报修出未结工单。</p>
     */
    static final String PRIORITY_COLUMN = "CASE WHEN e.ended_at IS NULL AND "
            + HANDLING_STATE_COLUMN + " = '" + AlertEventStates.PENDING + "' AND NOT ("
            + OPEN_ORDER_EXISTS + ") THEN '"
            + AlertEventStates.PRIORITY_URGENT + "'"
            + " WHEN e.ended_at IS NULL THEN '" + AlertEventStates.PRIORITY_HIGH + "'"
            + " WHEN e.recovered_at IS NOT NULL AND " + HANDLING_STATE_COLUMN + " = '"
            + AlertEventStates.PENDING + "' THEN '" + AlertEventStates.PRIORITY_NORMAL + "'"
            + " ELSE '" + AlertEventStates.PRIORITY_LOW + "' END";

    /** 优先级从急到缓的固定次序，供 ORDER BY 使用。 */
    static final String PRIORITY_ORDER = "FIELD(" + PRIORITY_COLUMN + ", '"
            + AlertEventStates.PRIORITY_URGENT + "', '" + AlertEventStates.PRIORITY_HIGH + "', '"
            + AlertEventStates.PRIORITY_NORMAL + "', '" + AlertEventStates.PRIORITY_LOW + "')";

    static final String EVENT_SELECT = """
            SELECT e.event_id, e.equipment_id, eq.equipment_name,
                   e.sensor_id, s.sensor_code,
                   e.parameter_id, p.parameter_code, p.parameter_name,
                   e.started_at, e.triggered_at, e.recovered_at, e.ended_at, e.end_reason,
                   %s AS event_state, %s AS handling_state, %s AS priority,
                   e.trigger_value, e.unit_code,
                   JSON_UNQUOTE(JSON_EXTRACT(e.source_reference, '$.type')) AS source_type,
                   (SELECT GROUP_CONCAT(r.repair_order_id ORDER BY r.repair_order_id)
                      FROM p2_repair_order r
                     WHERE r.source_alert_event_id = e.event_id) AS repair_order_ids,
                   (SELECT GROUP_CONCAT(r.status ORDER BY r.repair_order_id)
                      FROM p2_repair_order r
                     WHERE r.source_alert_event_id = e.event_id) AS repair_order_statuses,
                   e.version
            FROM p2_alert_event e
            JOIN dim_equipment eq ON eq.equipment_id = e.equipment_id
            JOIN dim_sensor s ON s.sensor_id = e.sensor_id
            JOIN dim_sensor_parameter p ON p.parameter_id = e.parameter_id
            """.formatted(EVENT_STATE_COLUMN, HANDLING_STATE_COLUMN, PRIORITY_COLUMN);

    static final RowMapper<AlertEventItem> EVENT_ITEM_MAPPER = (rs, row) -> new AlertEventItem(
            rs.getString("event_id"),
            rs.getString("equipment_id"),
            rs.getString("equipment_name"),
            rs.getString("sensor_id"),
            rs.getString("sensor_code"),
            rs.getString("parameter_id"),
            rs.getString("parameter_code"),
            rs.getString("parameter_name"),
            MaintenanceTimes.toOffset(rs, "started_at"),
            MaintenanceTimes.toOffset(rs, "triggered_at"),
            MaintenanceTimes.toOffset(rs, "recovered_at"),
            MaintenanceTimes.toOffset(rs, "ended_at"),
            rs.getString("end_reason"),
            rs.getString("event_state"),
            rs.getString("handling_state"),
            rs.getString("priority"),
            rs.getString("trigger_value"),
            rs.getString("unit_code"),
            rs.getString("source_type"),
            splitIds(rs.getString("repair_order_ids")),
            splitIds(rs.getString("repair_order_statuses")),
            rs.getInt("version"));

    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;

    public AlertEventRepository(JdbcTemplate jdbc, ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
    }

    /** 默认按触发时刻倒序（文档 S1-A01 的展示顺序）；按优先级时先急后缓，同级别仍按时间倒序。 */
    public List<AlertEventItem> findEvents(AlertEventQuery query, int offset, int limit) {
        List<Object> args = new ArrayList<>();
        String order = AlertEventStates.SORT_PRIORITY.equals(query.sort())
                ? " ORDER BY " + PRIORITY_ORDER + ", e.triggered_at DESC, e.event_id DESC"
                : " ORDER BY e.triggered_at DESC, e.event_id DESC";
        String sql = EVENT_SELECT + whereClause(query, args) + order + " LIMIT ? OFFSET ?";
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
                       e.parameter_id, p.parameter_code, p.parameter_name,
                       e.started_at, e.triggered_at, e.recovered_at, e.ended_at, e.end_reason,
                       %s AS event_state, %s AS handling_state, %s AS priority,
                       e.trigger_value, e.unit_code, e.source_reference,
                       e.rule_version_id, e.version
                FROM p2_alert_event e
                JOIN dim_equipment eq ON eq.equipment_id = e.equipment_id
                JOIN dim_sensor s ON s.sensor_id = e.sensor_id
                JOIN dim_sensor_parameter p ON p.parameter_id = e.parameter_id
                WHERE e.event_id = ?
                """.formatted(EVENT_STATE_COLUMN, HANDLING_STATE_COLUMN, PRIORITY_COLUMN);
        List<EventRow> rows = jdbc.query(sql, EventRow::map, eventId);
        if (rows.isEmpty()) {
            return null;
        }
        EventRow row = rows.get(0);
        return new AlertEventDetail(
                row.eventId(), row.equipmentId(), row.equipmentName(),
                row.sensorId(), row.sensorCode(), row.parameterId(), row.parameterCode(), row.parameterName(),
                row.startedAt(), row.triggeredAt(), row.recoveredAt(), row.endedAt(), row.endReason(),
                row.eventState(), row.handlingState(), row.priority(),
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
            String parameterName,
            OffsetDateTime startedAt,
            OffsetDateTime triggeredAt,
            OffsetDateTime recoveredAt,
            OffsetDateTime endedAt,
            String endReason,
            String eventState,
            String handlingState,
            String priority,
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
                    rs.getString("parameter_name"),
                    MaintenanceTimes.toOffset(rs, "started_at"),
                    MaintenanceTimes.toOffset(rs, "triggered_at"),
                    MaintenanceTimes.toOffset(rs, "recovered_at"),
                    MaintenanceTimes.toOffset(rs, "ended_at"),
                    rs.getString("end_reason"),
                    rs.getString("event_state"),
                    rs.getString("handling_state"),
                    rs.getString("priority"),
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

    /** 行锁读事件版本；返回 null 表示事件不存在。 */
    public Integer lockEventVersion(long eventId) {
        List<Integer> rows = jdbc.query(
                "SELECT version FROM p2_alert_event WHERE event_id = ? FOR UPDATE",
                (rs, row) -> rs.getInt(1), eventId);
        return rows.isEmpty() ? null : rows.get(0);
    }

    /** 追加一条人工处理记录；记录本身即审计依据，因此不再重复写 p2_change_log。 */
    public long insertHandlingRecord(long eventId, String action, String reason, String operatorName) {
        KeyHolder keys = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            PreparedStatement statement = connection.prepareStatement("""
                    INSERT INTO p2_alert_handling_record (event_id, action, reason, operator_name, recorded_at)
                    VALUES (?, ?, ?, ?, CURRENT_TIMESTAMP(3))
                    """, Statement.RETURN_GENERATED_KEYS);
            statement.setLong(1, eventId);
            statement.setString(2, action);
            statement.setString(3, reason);
            statement.setString(4, operatorName);
            return statement;
        }, keys);
        Number key = keys.getKey();
        if (key == null) {
            throw new IllegalStateException("人工处理记录未返回主键");
        }
        return key.longValue();
    }

    /** 推进事件版本：人工动作不改事实列，只让并发客户端知道事件已被处理过。 */
    public EventTouch touchEvent(long eventId) {
        jdbc.update(
                "UPDATE p2_alert_event SET version = version + 1, updated_at = CURRENT_TIMESTAMP(3)"
                        + " WHERE event_id = ?", eventId);
        return jdbc.queryForObject(
                "SELECT version, updated_at FROM p2_alert_event WHERE event_id = ?",
                (rs, row) -> new EventTouch(rs.getInt("version"),
                        MaintenanceTimes.toOffset(rs, "updated_at")), eventId);
    }

    /** 版本推进后的结果，供 S1-A03 响应回显。 */
    public record EventTouch(int version, OffsetDateTime updatedAt) {}

    /* ---------------- S1-A05 人工补录 ---------------- */

    /** 测点与参数的从属关系、设备归属与单位：补录只需要选测点+参数，其余由这里补齐。 */
    public record SensorParameterRef(
            long sensorId, long equipmentId, String equipmentName, String sensorCode,
            long parameterId, String parameterCode, String parameterName, String unitCode) {}

    /** 参数不存在或不属于该测点时返回 null，由业务层转成 400。 */
    public SensorParameterRef findSensorParameter(long sensorId, long parameterId) {
        List<SensorParameterRef> rows = jdbc.query("""
                SELECT s.sensor_id, s.equipment_id, e.equipment_name, s.sensor_code,
                       p.parameter_id, p.parameter_code, p.parameter_name, p.unit_code
                FROM dim_sensor s
                JOIN dim_equipment e ON e.equipment_id = s.equipment_id
                JOIN dim_sensor_parameter p ON p.sensor_id = s.sensor_id
                WHERE s.sensor_id = ? AND p.parameter_id = ?
                """, (rs, row) -> new SensorParameterRef(
                rs.getLong("sensor_id"), rs.getLong("equipment_id"), rs.getString("equipment_name"),
                rs.getString("sensor_code"), rs.getLong("parameter_id"),
                rs.getString("parameter_code"), rs.getString("parameter_name"),
                rs.getString("unit_code")), sensorId, parameterId);
        return rows.isEmpty() ? null : rows.get(0);
    }

    /**
     * 该参数上仍未结束的事件编号。
     *
     * <p>库上有唯一索引 {@code uk_p2_alert_active_parameter}，同一参数至多一条；
     * 这里先查一次是为了给出可读的提示，而不是把唯一键异常直接抛给使用者。</p>
     */
    public Long findActiveEventId(long parameterId) {
        List<Long> rows = jdbc.query(
                "SELECT event_id FROM p2_alert_event WHERE parameter_id = ? AND ended_at IS NULL",
                (rs, row) -> rs.getLong(1), parameterId);
        return rows.isEmpty() ? null : rows.get(0);
    }

    /** 该参数当前最新的规则版本；补录事件要挂在它上面（外键非空）。 */
    public Long findLatestRuleVersionId(long parameterId) {
        List<Long> rows = jdbc.query(
                "SELECT rule_version_id FROM p2_alert_rule_version WHERE parameter_id = ?"
                        + " ORDER BY rule_version DESC LIMIT 1",
                (rs, row) -> rs.getLong(1), parameterId);
        return rows.isEmpty() ? null : rows.get(0);
    }

    /**
     * 参数从未配过规则时，按参数定义里的阈值补一条初版规则。
     *
     * <p>事件表的 {@code rule_version_id} 非空且是外键，没有规则版本就插不进事件；
     * 与其让补录直接失败，不如把参数定义里的阈值固化成第 1 版规则——
     * 阈值本身取自参数定义，不是凭空编造。</p>
     */
    public long insertInitialRuleVersion(long parameterId, LocalDateTime effectiveAt, String operatorName) {
        KeyHolder keys = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            PreparedStatement statement = connection.prepareStatement("""
                    INSERT INTO p2_alert_rule_version
                        (parameter_id, rule_version, previous_rule_version_id,
                         normal_lower, normal_upper, alarm_lower, alarm_upper,
                         unit_code, trigger_mode, duration_seconds, enabled, effective_at,
                         reason, operator_name)
                    SELECT p.parameter_id,
                           (SELECT COALESCE(MAX(rule_version), 0) + 1 FROM p2_alert_rule_version
                             WHERE parameter_id = p.parameter_id),
                           NULL, p.normal_lower_limit, p.normal_upper_limit,
                           p.alarm_lower_limit, p.alarm_upper_limit,
                           p.unit_code, 'IMMEDIATE', NULL, 1, ?,
                           '人工补录异常事件时按参数定义建立初始规则', ?
                      FROM dim_sensor_parameter p
                     WHERE p.parameter_id = ?
                    """, Statement.RETURN_GENERATED_KEYS);
            statement.setTimestamp(1, Timestamp.valueOf(effectiveAt));
            statement.setString(2, operatorName);
            statement.setLong(3, parameterId);
            return statement;
        }, keys);
        Number key = keys.getKey();
        if (key == null) {
            throw new IllegalStateException("规则版本未返回主键");
        }
        return key.longValue();
    }

    /** 写入人工补录的事件本体；{@code sourceReferenceJson} 里带 type=MANUAL、补录人与原因。 */
    public long insertManualEvent(long equipmentId, long sensorId, long parameterId, long ruleVersionId,
                                  LocalDateTime startedAt, LocalDateTime triggeredAt, BigDecimal triggerValue,
                                  String unitCode, String sourceReferenceJson) {
        KeyHolder keys = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            PreparedStatement statement = connection.prepareStatement("""
                    INSERT INTO p2_alert_event
                        (equipment_id, sensor_id, parameter_id, rule_version_id,
                         started_at, triggered_at, trigger_value, unit_code, source_reference,
                         version, updated_at)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, 1, CURRENT_TIMESTAMP(3))
                    """, Statement.RETURN_GENERATED_KEYS);
            statement.setLong(1, equipmentId);
            statement.setLong(2, sensorId);
            statement.setLong(3, parameterId);
            statement.setLong(4, ruleVersionId);
            statement.setTimestamp(5, Timestamp.valueOf(startedAt));
            statement.setTimestamp(6, Timestamp.valueOf(triggeredAt));
            statement.setBigDecimal(7, triggerValue);
            statement.setString(8, unitCode);
            statement.setString(9, sourceReferenceJson);
            return statement;
        }, keys);
        Number key = keys.getKey();
        if (key == null) {
            throw new IllegalStateException("异常事件未返回主键");
        }
        return key.longValue();
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
