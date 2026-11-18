package cn.copper.phase2.production;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.sql.Date;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * 生产记录模块的数据访问。事实行沿用原始 ID + 版本更正，
 * 读取口径：有效任务 = 所属计划 ACTIVE 且未被新计划取代。
 */
@Repository
public class ProductionRepository {

    private final JdbcTemplate jdbc;

    public ProductionRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    // ---------------------------------------------------------------- 基础行

    public record TaskRow(long taskId, long planId, long workshopId, long materialId,
                          LocalDate productionDate, int sequenceNo, BigDecimal plannedQualifiedTon,
                          BigDecimal plannedPlateTon, BigDecimal plannedRodUseTon,
                          String status, long version) {
    }

    public record OrderRow(long orderId, String orderNo, String customerName, long wireMaterialId,
                           BigDecimal requiredTon, LocalDate dueDate, String productionResult,
                           String deliveryResult, String accountingState, long version) {
    }

    private static final String TASK_SELECT = """
            SELECT t.task_id, t.plan_id, t.workshop_id, t.material_id, t.production_date, t.sequence_no,
                   t.planned_qualified_ton, t.planned_plate_ton, t.planned_rod_use_ton, t.status, t.version
            FROM p2_plan_task t
            JOIN p2_plan p ON p.plan_id = t.plan_id
            WHERE p.status = 'ACTIVE' AND t.superseded_by_plan_id IS NULL
            """;

    public TaskRow findTask(long taskId) {
        List<TaskRow> rows = jdbc.query(TASK_SELECT + " AND t.task_id = ?",
                (rs, n) -> mapTask(rs), taskId);
        return rows.isEmpty() ? null : rows.get(0);
    }

    /** 锁定任务行（版本校验与状态更新前调用）。 */
    public TaskRow lockTask(long taskId) {
        List<TaskRow> rows = jdbc.query(TASK_SELECT.replace(
                        "SELECT t.task_id", "SELECT t.task_id").concat(" AND t.task_id = ? FOR UPDATE"),
                (rs, n) -> mapTask(rs), taskId);
        return rows.isEmpty() ? null : rows.get(0);
    }

    private static TaskRow mapTask(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new TaskRow(rs.getLong("task_id"), rs.getLong("plan_id"), rs.getLong("workshop_id"),
                rs.getLong("material_id"), rs.getDate("production_date").toLocalDate(),
                rs.getInt("sequence_no"), rs.getBigDecimal("planned_qualified_ton"),
                rs.getBigDecimal("planned_plate_ton"), rs.getBigDecimal("planned_rod_use_ton"),
                rs.getString("status"), rs.getLong("version"));
    }

    public record TaskOrderRow(long taskOrderId, long taskId, long orderId, String demandKey,
                               String quantityKind, BigDecimal quantityTon, String orderNo) {
    }

    public List<TaskOrderRow> taskOrders(long taskId) {
        return jdbc.query("""
                SELECT to1.task_order_id, to1.task_id, to1.order_id, to1.demand_key,
                       to1.quantity_kind, to1.quantity_ton, o.order_no
                FROM p2_task_order to1
                JOIN p2_customer_order o ON o.order_id = to1.order_id
                WHERE to1.task_id = ? ORDER BY to1.task_order_id
                """, (rs, n) -> new TaskOrderRow(rs.getLong(1), rs.getLong(2), rs.getLong(3),
                rs.getString(4), rs.getString(5), rs.getBigDecimal(6), rs.getString(7)), taskId);
    }

    public OrderRow findOrder(long orderId) {
        List<OrderRow> rows = jdbc.query("""
                SELECT order_id, order_no, customer_name, wire_material_id, required_quantity_ton,
                       due_date, production_result, delivery_result, accounting_state, version
                FROM p2_customer_order WHERE order_id = ?
                """, (rs, n) -> new OrderRow(rs.getLong(1), rs.getString(2), rs.getString(3),
                rs.getLong(4), rs.getBigDecimal(5), rs.getDate(6).toLocalDate(), rs.getString(7),
                rs.getString(8), rs.getString(9), rs.getLong(10)), orderId);
        return rows.isEmpty() ? null : rows.get(0);
    }

    public OrderRow lockOrder(long orderId) {
        List<OrderRow> rows = jdbc.query("""
                SELECT order_id, order_no, customer_name, wire_material_id, required_quantity_ton,
                       due_date, production_result, delivery_result, accounting_state, version
                FROM p2_customer_order WHERE order_id = ? FOR UPDATE
                """, (rs, n) -> new OrderRow(rs.getLong(1), rs.getString(2), rs.getString(3),
                rs.getLong(4), rs.getBigDecimal(5), rs.getDate(6).toLocalDate(), rs.getString(7),
                rs.getString(8), rs.getString(9), rs.getLong(10)), orderId);
        return rows.isEmpty() ? null : rows.get(0);
    }

    public record MaterialRow(long materialId, String code, String name, String type,
                              String specification) {
    }

    public MaterialRow findMaterial(long materialId) {
        List<MaterialRow> rows = jdbc.query("""
                SELECT material_id, material_code, material_name, material_type, specification
                FROM p2_material WHERE material_id = ?
                """, (rs, n) -> new MaterialRow(rs.getLong(1), rs.getString(2), rs.getString(3),
                rs.getString(4), rs.getString(5)), materialId);
        return rows.isEmpty() ? null : rows.get(0);
    }

    // ---------------------------------------------------------------- 任务列表

    public record TaskFilter(Long workshopId, Long orderId, LocalDate plannedFrom, LocalDate plannedTo,
                             LocalDate actualFrom, LocalDate actualTo, String executionState,
                             int page, int pageSize) {
    }

    public List<TaskRow> listTasks(TaskFilter filter) {
        StringBuilder sql = new StringBuilder(TASK_SELECT);
        List<Object> args = new ArrayList<>();
        if (filter.workshopId() != null) {
            sql.append(" AND t.workshop_id = ?");
            args.add(filter.workshopId());
        }
        if (filter.plannedFrom() != null) {
            sql.append(" AND t.production_date >= ?");
            args.add(Date.valueOf(filter.plannedFrom()));
        }
        if (filter.plannedTo() != null) {
            sql.append(" AND t.production_date <= ?");
            args.add(Date.valueOf(filter.plannedTo()));
        }
        if (filter.orderId() != null) {
            sql.append(" AND EXISTS (SELECT 1 FROM p2_task_order tor WHERE tor.task_id = t.task_id "
                    + "AND tor.order_id = ?)");
            args.add(filter.orderId());
        }
        if (filter.actualFrom() != null || filter.actualTo() != null) {
            sql.append(" AND EXISTS (SELECT 1 FROM p2_output_record o WHERE o.task_id = t.task_id AND o.voided_at IS NULL ");
            List<String> dateConds = new ArrayList<>();
            if (filter.actualFrom() != null) {
                dateConds.add("o.actual_date >= ?");
                args.add(Date.valueOf(filter.actualFrom()));
            }
            if (filter.actualTo() != null) {
                dateConds.add("o.actual_date <= ?");
                args.add(Date.valueOf(filter.actualTo()));
            }
            sql.append("(").append(String.join(" AND ", dateConds)).append("))");
        }
        sql.append(" ORDER BY t.production_date, t.workshop_id, t.sequence_no, t.task_id LIMIT ? OFFSET ?");
        args.add(filter.pageSize());
        args.add((long) (filter.page() - 1) * filter.pageSize());
        return jdbc.query(sql.toString(), (rs, n) -> mapTask(rs), args.toArray());
    }

    public long countTasks(TaskFilter filter) {
        StringBuilder sql = new StringBuilder("""
                SELECT COUNT(*) FROM p2_plan_task t
                JOIN p2_plan p ON p.plan_id = t.plan_id
                WHERE p.status = 'ACTIVE' AND t.superseded_by_plan_id IS NULL
                """);
        List<Object> args = new ArrayList<>();
        if (filter.workshopId() != null) {
            sql.append(" AND t.workshop_id = ?");
            args.add(filter.workshopId());
        }
        if (filter.plannedFrom() != null) {
            sql.append(" AND t.production_date >= ?");
            args.add(Date.valueOf(filter.plannedFrom()));
        }
        if (filter.plannedTo() != null) {
            sql.append(" AND t.production_date <= ?");
            args.add(Date.valueOf(filter.plannedTo()));
        }
        if (filter.orderId() != null) {
            sql.append(" AND EXISTS (SELECT 1 FROM p2_task_order tor WHERE tor.task_id = t.task_id "
                    + "AND tor.order_id = ?)");
            args.add(filter.orderId());
        }
        Long total = jdbc.queryForObject(sql.toString(), Long.class, args.toArray());
        return total == null ? 0 : total;
    }

    // ---------------------------------------------------------------- 任务实际量

    /** 任务已记录合格量：独立称重直接计入，共用称重按份额计入。 */
    public BigDecimal taskQualifiedTon(long taskId) {
        BigDecimal direct = jdbc.queryForObject("""
                SELECT COALESCE(SUM(o.qualified_quantity_ton), 0)
                FROM p2_output_record o
                WHERE o.task_id = ? AND o.record_type = 'QUALIFIED_OUTPUT' AND o.voided_at IS NULL
                  AND NOT EXISTS (SELECT 1 FROM p2_output_task_share s WHERE s.output_id = o.output_id)
                """, BigDecimal.class, taskId);
        BigDecimal shared = jdbc.queryForObject("""
                SELECT COALESCE(SUM(s.qualified_quantity_ton), 0)
                FROM p2_output_task_share s
                JOIN p2_output_record o ON o.output_id = s.output_id
                WHERE s.task_id = ? AND o.record_type = 'QUALIFIED_OUTPUT' AND o.voided_at IS NULL
                """, BigDecimal.class, taskId);
        return (direct == null ? BigDecimal.ZERO : direct)
                .add(shared == null ? BigDecimal.ZERO : shared);
    }

    public boolean taskHasWeighing(long taskId) {
        Long count = jdbc.queryForObject("""
                SELECT COUNT(*) FROM p2_output_record o
                WHERE (o.task_id = ? OR EXISTS (SELECT 1 FROM p2_output_task_share s
                        WHERE s.output_id = o.output_id AND s.task_id = ?))
                  AND o.record_type = 'QUALIFIED_OUTPUT' AND o.voided_at IS NULL
                """, Long.class, taskId, taskId);
        return count != null && count > 0;
    }

    public boolean taskHasNoOutput(long taskId) {
        Long count = jdbc.queryForObject("""
                SELECT COUNT(*) FROM p2_output_record
                WHERE task_id = ? AND record_type = 'NO_OUTPUT' AND voided_at IS NULL
                """, Long.class, taskId);
        return count != null && count > 0;
    }

    /** 任务执行片段（EXECUTION 行）。 */
    public record ExecutionRow(long outputId, long workshopId, Long taskId, Long groupId,
                               LocalDate actualDate, LocalDateTime startedAt, LocalDateTime stoppedAt,
                               String notes, String operatorName, LocalDateTime recordedAt, long version) {
    }

    public List<ExecutionRow> taskExecutions(long taskId) {
        return jdbc.query("""
                SELECT o.output_id, o.workshop_id, o.task_id, o.production_group_id, o.actual_date,
                       o.started_at, o.stopped_at, o.reason, o.operator_name, o.recorded_at, o.version
                FROM p2_output_record o WHERE o.task_id = ? AND o.record_type = 'EXECUTION'
                ORDER BY o.actual_date, o.started_at, o.output_id
                """, (rs, n) -> new ExecutionRow(rs.getLong(1), rs.getLong(2), rs.getObject(3, Long.class),
                rs.getObject(4, Long.class), rs.getDate(5).toLocalDate(), ts(rs.getTimestamp(6)),
                ts(rs.getTimestamp(7)), rs.getString(8), rs.getString(9), ts(rs.getTimestamp(10)),
                rs.getLong(11)), taskId);
    }

    // ---------------------------------------------------------------- 事实行

    public record OutputRow(long outputId, String recordType, long workshopId, Long taskId,
                            Long materialId, Long movementId, Long groupId, String groupKey,
                            LocalDate actualDate, LocalDateTime startedAt, LocalDateTime stoppedAt,
                            BigDecimal qualifiedTon, String reason, String operatorName,
                            LocalDateTime recordedAt, long version, LocalDateTime voidedAt,
                            String taskReferences, String inputReferences, String executionReferences) {
    }

    public OutputRow findOutput(long outputId) {
        List<OutputRow> rows = jdbc.query("""
                SELECT output_id, record_type, workshop_id, task_id, material_id, movement_id,
                       production_group_id, production_group_key, actual_date, started_at, stopped_at,
                       qualified_quantity_ton, reason, operator_name, recorded_at, version, voided_at,
                       task_references, input_references, execution_references
                FROM p2_output_record WHERE output_id = ?
                """, (rs, n) -> mapOutput(rs), outputId);
        return rows.isEmpty() ? null : rows.get(0);
    }

    public OutputRow lockOutput(long outputId) {
        List<OutputRow> rows = jdbc.query("""
                SELECT output_id, record_type, workshop_id, task_id, material_id, movement_id,
                       production_group_id, production_group_key, actual_date, started_at, stopped_at,
                       qualified_quantity_ton, reason, operator_name, recorded_at, version, voided_at,
                       task_references, input_references, execution_references
                FROM p2_output_record WHERE output_id = ? FOR UPDATE
                """, (rs, n) -> mapOutput(rs), outputId);
        return rows.isEmpty() ? null : rows.get(0);
    }

    private static OutputRow mapOutput(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new OutputRow(rs.getLong("output_id"), rs.getString("record_type"),
                rs.getLong("workshop_id"), rs.getObject("task_id", Long.class),
                rs.getObject("material_id", Long.class), rs.getObject("movement_id", Long.class),
                rs.getObject("production_group_id", Long.class), rs.getString("production_group_key"),
                rs.getDate("actual_date").toLocalDate(), ts(rs.getTimestamp("started_at")),
                ts(rs.getTimestamp("stopped_at")), rs.getBigDecimal("qualified_quantity_ton"),
                rs.getString("reason"), rs.getString("operator_name"), ts(rs.getTimestamp("recorded_at")),
                rs.getLong("version"), ts(rs.getTimestamp("voided_at")),
                rs.getString("task_references"), rs.getString("input_references"),
                rs.getString("execution_references"));
    }

    public record ShareRow(long shareId, long outputId, long taskId, BigDecimal quantityTon, long version) {
    }

    public List<ShareRow> outputShares(long outputId) {
        return jdbc.query("""
                SELECT output_task_share_id, output_id, task_id, qualified_quantity_ton, version
                FROM p2_output_task_share WHERE output_id = ? ORDER BY task_id
                """, (rs, n) -> new ShareRow(rs.getLong(1), rs.getLong(2), rs.getLong(3),
                rs.getBigDecimal(4), rs.getLong(5)), outputId);
    }

    public List<ShareRow> lockOutputShares(long outputId) {
        return jdbc.query("""
                SELECT output_task_share_id, output_id, task_id, qualified_quantity_ton, version
                FROM p2_output_task_share WHERE output_id = ? ORDER BY task_id FOR UPDATE
                """, (rs, n) -> new ShareRow(rs.getLong(1), rs.getLong(2), rs.getLong(3),
                rs.getBigDecimal(4), rs.getLong(5)), outputId);
    }

    public record PlateInputRow(long inputId, Long taskId, long materialId, long movementId,
                                Long groupId, String groupKey, LocalDate actualDate,
                                BigDecimal inputTon, java.math.BigDecimal materialAmountYuan,
                                String operatorName, LocalDateTime recordedAt, long version) {
    }

    public PlateInputRow findPlateInput(long inputId) {
        List<PlateInputRow> rows = jdbc.query("""
                SELECT input_id, task_id, material_id, movement_id, production_group_id,
                       production_group_key, actual_date, input_quantity_ton, material_amount_yuan,
                       operator_name, recorded_at, version
                FROM p2_plate_input WHERE input_id = ?
                """, (rs, n) -> new PlateInputRow(rs.getLong(1), rs.getObject(2, Long.class),
                rs.getLong(3), rs.getLong(4), rs.getObject(5, Long.class), rs.getString(6),
                rs.getDate(7).toLocalDate(), rs.getBigDecimal(8), rs.getBigDecimal(9),
                rs.getString(10), ts(rs.getTimestamp(11)), rs.getLong(12)), inputId);
        return rows.isEmpty() ? null : rows.get(0);
    }

    public PlateInputRow lockPlateInput(long inputId) {
        List<PlateInputRow> rows = jdbc.query("""
                SELECT input_id, task_id, material_id, movement_id, production_group_id,
                       production_group_key, actual_date, input_quantity_ton, material_amount_yuan,
                       operator_name, recorded_at, version
                FROM p2_plate_input WHERE input_id = ? FOR UPDATE
                """, (rs, n) -> new PlateInputRow(rs.getLong(1), rs.getObject(2, Long.class),
                rs.getLong(3), rs.getLong(4), rs.getObject(5, Long.class), rs.getString(6),
                rs.getDate(7).toLocalDate(), rs.getBigDecimal(8), rs.getBigDecimal(9),
                rs.getString(10), ts(rs.getTimestamp(11)), rs.getLong(12)), inputId);
        return rows.isEmpty() ? null : rows.get(0);
    }

    public record HandoverRow(long handoverId, Long taskId, Long orderId, long movementId,
                              Long groupId, LocalDate actualDate, BigDecimal sentTon,
                              BigDecimal receivedTon, LocalDateTime sentAt, LocalDateTime receivedAt,
                              String releaseReason, String releasedBy, LocalDateTime releasedAt,
                              String operatorName, LocalDateTime recordedAt, long version,
                              String receivingReferences) {
    }

    private static final String HANDOVER_SELECT = """
            SELECT h.handover_id, h.task_id, h.order_id, h.movement_id, h.production_group_id,
                   DATE(h.sent_at) AS actual_date,
                   h.sent_quantity_ton, h.received_quantity_ton, h.sent_at, h.received_at,
                   h.release_reason, h.released_by, h.released_at, h.operator_name, h.recorded_at,
                   h.version, h.receiving_references
            FROM p2_rod_handover h
            """;

    public HandoverRow findHandover(long handoverId) {
        List<HandoverRow> rows = jdbc.query(HANDOVER_SELECT + " WHERE h.handover_id = ?",
                (rs, n) -> mapHandover(rs), handoverId);
        return rows.isEmpty() ? null : rows.get(0);
    }

    public HandoverRow lockHandover(long handoverId) {
        List<HandoverRow> rows = jdbc.query(HANDOVER_SELECT + " WHERE h.handover_id = ? FOR UPDATE",
                (rs, n) -> mapHandover(rs), handoverId);
        return rows.isEmpty() ? null : rows.get(0);
    }

    private static HandoverRow mapHandover(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new HandoverRow(rs.getLong("handover_id"), rs.getObject("task_id", Long.class),
                rs.getObject("order_id", Long.class), rs.getLong("movement_id"),
                rs.getObject("production_group_id", Long.class),
                rs.getDate("actual_date") == null ? null : rs.getDate("actual_date").toLocalDate(),
                rs.getBigDecimal("sent_quantity_ton"), rs.getBigDecimal("received_quantity_ton"),
                ts(rs.getTimestamp("sent_at")), ts(rs.getTimestamp("received_at")),
                rs.getString("release_reason"), rs.getString("released_by"),
                ts(rs.getTimestamp("released_at")), rs.getString("operator_name"),
                ts(rs.getTimestamp("recorded_at")), rs.getLong("version"),
                rs.getString("receiving_references"));
    }

    // ---------------------------------------------------------------- 归集组

    public record GroupRow(long groupId, String origin, long workshopId, long materialId,
                           LocalDate anchorActualDate, String operatorName, String reason,
                           LocalDateTime recordedAt, long version) {
    }

    public GroupRow findGroup(long groupId) {
        List<GroupRow> rows = jdbc.query("""
                SELECT production_group_id, origin, workshop_id, material_id, anchor_actual_date,
                       operator_name, reason, recorded_at, version
                FROM p2_production_group WHERE production_group_id = ?
                """, (rs, n) -> new GroupRow(rs.getLong(1), rs.getString(2), rs.getLong(3),
                rs.getLong(4), rs.getDate(5).toLocalDate(), rs.getString(6), rs.getString(7),
                ts(rs.getTimestamp(8)), rs.getLong(9)), groupId);
        return rows.isEmpty() ? null : rows.get(0);
    }

    public GroupRow lockGroup(long groupId) {
        List<GroupRow> rows = jdbc.query("""
                SELECT production_group_id, origin, workshop_id, material_id, anchor_actual_date,
                       operator_name, reason, recorded_at, version
                FROM p2_production_group WHERE production_group_id = ? FOR UPDATE
                """, (rs, n) -> new GroupRow(rs.getLong(1), rs.getString(2), rs.getLong(3),
                rs.getLong(4), rs.getDate(5).toLocalDate(), rs.getString(6), rs.getString(7),
                ts(rs.getTimestamp(8)), rs.getLong(9)), groupId);
        return rows.isEmpty() ? null : rows.get(0);
    }

    public long insertGroup(String origin, long workshopId, long materialId, LocalDate anchorDate,
                            String operatorName, String reason) {
        jdbc.update("""
                INSERT INTO p2_production_group
                    (origin, workshop_id, material_id, anchor_actual_date, operator_name, reason)
                VALUES (?, ?, ?, ?, ?, ?)
                """, origin, workshopId, materialId, Date.valueOf(anchorDate), operatorName, reason);
        return jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
    }

    public void bumpGroupVersion(long groupId) {
        jdbc.update("UPDATE p2_production_group SET version = version + 1 WHERE production_group_id = ?",
                groupId);
    }

    // ---------------------------------------------------------------- 日状态

    public record DayStatusRow(long workshopId, LocalDate actualDate, long sourceRevision,
                               Long confirmedSourceRevision, boolean recordsComplete,
                               String confirmedBy, LocalDateTime confirmedAt, long version) {
    }

    public DayStatusRow findDayStatus(long workshopId, LocalDate actualDate) {
        List<DayStatusRow> rows = jdbc.query("""
                SELECT workshop_id, actual_date, source_revision, confirmed_source_revision,
                       records_complete, confirmed_by, confirmed_at, version
                FROM p2_production_day_status WHERE workshop_id = ? AND actual_date = ?
                """, (rs, n) -> new DayStatusRow(rs.getLong(1), rs.getDate(2).toLocalDate(),
                rs.getLong(3), rs.getObject(4, Long.class), rs.getBoolean(5), rs.getString(6),
                ts(rs.getTimestamp(7)), rs.getLong(8)), workshopId, Date.valueOf(actualDate));
        return rows.isEmpty() ? null : rows.get(0);
    }

    /** 写入事实时推进来源修订并使已确认录全失效。 */
    public void bumpDayRevision(long workshopId, LocalDate actualDate) {
        jdbc.update("""
                INSERT INTO p2_production_day_status (workshop_id, actual_date, source_revision, version)
                VALUES (?, ?, 1, 1)
                ON DUPLICATE KEY UPDATE source_revision = source_revision + 1, version = version + 1,
                    confirmed_source_revision = NULL, confirmed_by = NULL, confirmed_at = NULL,
                    records_complete = FALSE
                """, workshopId, Date.valueOf(actualDate));
    }

    public DayStatusRow lockDayStatus(long workshopId, LocalDate actualDate) {
        List<DayStatusRow> rows = jdbc.query("""
                SELECT workshop_id, actual_date, source_revision, confirmed_source_revision,
                       records_complete, confirmed_by, confirmed_at, version
                FROM p2_production_day_status WHERE workshop_id = ? AND actual_date = ? FOR UPDATE
                """, (rs, n) -> new DayStatusRow(rs.getLong(1), rs.getDate(2).toLocalDate(),
                rs.getLong(3), rs.getObject(4, Long.class), rs.getBoolean(5), rs.getString(6),
                ts(rs.getTimestamp(7)), rs.getLong(8)), workshopId, Date.valueOf(actualDate));
        return rows.isEmpty() ? null : rows.get(0);
    }

    public void insertDayStatus(long workshopId, LocalDate actualDate) {
        jdbc.update("""
                INSERT IGNORE INTO p2_production_day_status (workshop_id, actual_date)
                VALUES (?, ?)
                """, workshopId, Date.valueOf(actualDate));
    }

    public void confirmDay(long workshopId, LocalDate actualDate, long sourceRevision, String confirmedBy,
                           LocalDateTime confirmedAt, long previousVersion) {
        jdbc.update("""
                UPDATE p2_production_day_status
                SET confirmed_source_revision = ?, records_complete = TRUE, confirmed_by = ?,
                    confirmed_at = ?, version = version + 1
                WHERE workshop_id = ? AND actual_date = ? AND version = ?
                """, sourceRevision, confirmedBy, confirmedAt, workshopId,
                Date.valueOf(actualDate), previousVersion);
    }

    // ---------------------------------------------------------------- 计划外/实际时间线

    public record RecordFilter(String recordType, Long taskId, Long groupId, Long workshopId,
                               Long orderId, LocalDate actualFrom, LocalDate actualTo, String source,
                               int page, int pageSize) {
    }

    /**
     * 实际时间线：六类事实的统一投影。每条主记录一个 output/input/handover ID。
     * 以实际日、录入时间、ID 稳定降序排列。
     */
    public static final String TIMELINE_SQL = """
            SELECT * FROM (
                SELECT o.output_id AS record_id, o.record_type, o.workshop_id, o.task_id,
                       o.production_group_id AS group_id, o.material_id, o.actual_date,
                       o.recorded_at, o.operator_name, o.qualified_quantity_ton AS quantity_ton,
                       o.reason, o.voided_at,
                       NULL AS sent_ton, NULL AS received_ton,
                       o.task_id AS handover_order_id, NULL AS handover_task_id
                FROM p2_output_record o
                UNION ALL
                SELECT i.input_id, 'PLATE_INPUT', COALESCE(t.workshop_id, g.workshop_id), i.task_id,
                       i.production_group_id, i.material_id, i.actual_date, i.recorded_at,
                       i.operator_name, i.input_quantity_ton, NULL, NULL,
                       NULL, NULL, NULL, NULL
                FROM p2_plate_input i
                LEFT JOIN p2_plan_task t ON t.task_id = i.task_id
                LEFT JOIN p2_production_group g ON g.production_group_id = i.production_group_id
                UNION ALL
                SELECT h.handover_id, 'ROD_HANDOVER', COALESCE(t.workshop_id, g.workshop_id), h.task_id,
                       h.production_group_id, NULL, DATE(h.sent_at), h.recorded_at, h.operator_name,
                       h.sent_quantity_ton, NULL, NULL,
                       h.sent_quantity_ton, h.received_quantity_ton, h.order_id, h.task_id
                FROM p2_rod_handover h
                LEFT JOIN p2_plan_task t ON t.task_id = h.task_id
                LEFT JOIN p2_production_group g ON g.production_group_id = h.production_group_id
            ) timeline
            WHERE 1 = 1
            """;

    public record TimelineRow(long recordId, String recordType, Long workshopId, Long taskId,
                              Long groupId, Long materialId, LocalDate actualDate,
                              LocalDateTime recordedAt, String operatorName, BigDecimal quantityTon,
                              String reason, LocalDateTime voidedAt, BigDecimal sentTon,
                              BigDecimal receivedTon, Long orderId, Long handoverTaskId) {
    }

    public List<TimelineRow> timeline(RecordFilter filter, int limit, int offset) {
        StringBuilder sql = new StringBuilder(TIMELINE_SQL);
        List<Object> args = new ArrayList<>();
        appendTimelineFilters(sql, filter, args);
        sql.append(" ORDER BY actual_date DESC, recorded_at DESC, record_id DESC LIMIT ? OFFSET ?");
        args.add(limit);
        args.add(offset);
        return jdbc.query(sql.toString(), (rs, n) -> new TimelineRow(rs.getLong("record_id"),
                rs.getString("record_type"), rs.getObject("workshop_id", Long.class),
                rs.getObject("task_id", Long.class), rs.getObject("group_id", Long.class),
                rs.getObject("material_id", Long.class), rs.getDate("actual_date").toLocalDate(),
                ts(rs.getTimestamp("recorded_at")), rs.getString("operator_name"),
                rs.getBigDecimal("quantity_ton"), rs.getString("reason"), ts(rs.getTimestamp("voided_at")),
                rs.getBigDecimal("sent_ton"), rs.getBigDecimal("received_ton"),
                rs.getObject("handover_order_id", Long.class),
                rs.getObject("handover_task_id", Long.class)), args.toArray());
    }

    public long countTimeline(RecordFilter filter) {
        StringBuilder sql = new StringBuilder(TIMELINE_SQL);
        List<Object> args = new ArrayList<>();
        appendTimelineFilters(sql, filter, args);
        String wrapped = "SELECT COUNT(*) FROM (" + sql + ") tl";
        Long total = jdbc.queryForObject(wrapped, Long.class, args.toArray());
        return total == null ? 0 : total;
    }

    private static void appendTimelineFilters(StringBuilder sql, RecordFilter filter, List<Object> args) {
        if (filter.recordType() != null) {
            sql.append(" AND record_type = ?");
            args.add(filter.recordType());
        }
        if (filter.taskId() != null) {
            sql.append(" AND (task_id = ? OR handover_task_id = ?)");
            args.add(filter.taskId());
            args.add(filter.taskId());
        }
        if (filter.groupId() != null) {
            sql.append(" AND group_id = ?");
            args.add(filter.groupId());
        }
        if (filter.workshopId() != null) {
            sql.append(" AND workshop_id = ?");
            args.add(filter.workshopId());
        }
        if (filter.actualFrom() != null) {
            sql.append(" AND actual_date >= ?");
            args.add(Date.valueOf(filter.actualFrom()));
        }
        if (filter.actualTo() != null) {
            sql.append(" AND actual_date <= ?");
            args.add(Date.valueOf(filter.actualTo()));
        }
        if (filter.source() != null) {
            if (ProductionTypes.SOURCE_PLANNED.equals(filter.source())) {
                sql.append(" AND task_id IS NOT NULL");
            } else {
                sql.append(" AND task_id IS NULL AND group_id IS NOT NULL");
            }
        }
        if (filter.orderId() != null) {
            sql.append(" AND EXISTS (SELECT 1 FROM p2_finished_allocation fa "
                    + "WHERE fa.output_id = timeline.record_id AND fa.order_id = ?)");
            args.add(filter.orderId());
        }
    }

    // ---------------------------------------------------------------- 车间日事实（日录全/零产出校验）

    public BigDecimal workshopDayQualifiedTon(long workshopId, LocalDate actualDate) {
        BigDecimal total = jdbc.queryForObject("""
                SELECT COALESCE(SUM(qualified_quantity_ton), 0) FROM p2_output_record
                WHERE workshop_id = ? AND actual_date = ? AND record_type = 'QUALIFIED_OUTPUT'
                  AND voided_at IS NULL
                """, BigDecimal.class, workshopId, Date.valueOf(actualDate));
        return total == null ? BigDecimal.ZERO : total;
    }

    public boolean workshopDayHasPositiveOutput(long workshopId, LocalDate actualDate) {
        Long count = jdbc.queryForObject("""
                SELECT COUNT(*) FROM p2_output_record
                WHERE workshop_id = ? AND actual_date = ? AND record_type = 'QUALIFIED_OUTPUT'
                  AND voided_at IS NULL
                """, Long.class, workshopId, Date.valueOf(actualDate));
        return count != null && count > 0;
    }

    public boolean workshopDayHasValidZero(long workshopId, LocalDate actualDate, Long taskId) {
        Long count;
        if (taskId == null) {
            count = jdbc.queryForObject("""
                    SELECT COUNT(*) FROM p2_output_record
                    WHERE workshop_id = ? AND actual_date = ? AND record_type = 'NO_OUTPUT'
                      AND voided_at IS NULL AND task_id IS NULL
                    """, Long.class, workshopId, Date.valueOf(actualDate));
        } else {
            count = jdbc.queryForObject("""
                    SELECT COUNT(*) FROM p2_output_record
                    WHERE workshop_id = ? AND actual_date = ? AND record_type = 'NO_OUTPUT'
                      AND voided_at IS NULL AND task_id = ?
                    """, Long.class, workshopId, Date.valueOf(actualDate), taskId);
        }
        return count != null && count > 0;
    }

    /** 车间日内已发生执行事实但未称重的任务（录全阻塞项）。 */
    public record PendingWeighingTask(long taskId, LocalDate actualDate) {
    }

    public List<PendingWeighingTask> dayPendingWeighings(long workshopId, LocalDate actualDate) {
        return jdbc.query("""
                SELECT DISTINCT o.task_id, o.actual_date
                FROM p2_output_record o
                WHERE o.workshop_id = ? AND o.actual_date = ? AND o.task_id IS NOT NULL
                  AND o.voided_at IS NULL
                  AND NOT EXISTS (SELECT 1 FROM p2_output_record w
                        WHERE w.task_id = o.task_id AND w.actual_date = o.actual_date
                          AND w.record_type = 'QUALIFIED_OUTPUT' AND w.voided_at IS NULL)
                  AND NOT EXISTS (SELECT 1 FROM p2_output_record z
                        WHERE z.task_id = o.task_id AND z.actual_date = o.actual_date
                          AND z.record_type = 'NO_OUTPUT' AND z.voided_at IS NULL)
                ORDER BY o.task_id
                """, (rs, n) -> new PendingWeighingTask(rs.getLong(1), rs.getDate(2).toLocalDate()),
                workshopId, Date.valueOf(actualDate));
    }

    /** 已被产出引用的交接吨数（用杆来源核对）。 */
    public BigDecimal referencedRodUseTon(long handoverId) {
        BigDecimal total = jdbc.queryForObject("""
                SELECT COALESCE(SUM(ref.qty), 0)
                FROM p2_output_record o,
                     JSON_TABLE(o.input_references, '$[*]' COLUMNS (
                         handoverId BIGINT PATH '$.handoverId',
                         qty DECIMAL(18,3) PATH '$.sentQuantityTon')) ref
                WHERE o.record_type = 'QUALIFIED_OUTPUT' AND o.voided_at IS NULL
                  AND ref.handoverId = ?
                """, BigDecimal.class, handoverId);
        return total == null ? BigDecimal.ZERO : total;
    }

    /** 任务当日计划内零产出/称重冲突所需的版本与原因查询由服务层组合。 */

    // ---------------------------------------------------------------- 任务状态更新

    public void completeTask(long taskId) {
        jdbc.update("UPDATE p2_plan_task SET status = 'COMPLETED', version = version + 1 "
                + "WHERE task_id = ?", taskId);
    }

    public void bumpTaskVersion(long taskId) {
        jdbc.update("UPDATE p2_plan_task SET version = version + 1 WHERE task_id = ?", taskId);
    }

    public void completeOrder(long orderId, String productionResult, String reason,
                              LocalDateTime completedAt) {
        jdbc.update("""
                UPDATE p2_customer_order SET production_result = ?, completion_reason = ?,
                    completed_at = ?, version = version + 1 WHERE order_id = ?
                """, productionResult, reason, completedAt, orderId);
    }

    public BigDecimal orderAllocatedTon(long orderId) {
        BigDecimal total = jdbc.queryForObject("""
                SELECT COALESCE(SUM(fa.allocated_quantity_ton - fa.released_quantity_ton), 0)
                FROM p2_finished_allocation fa WHERE fa.order_id = ?
                """, BigDecimal.class, orderId);
        return total == null ? BigDecimal.ZERO : total;
    }

    /** 订单尚需量 = 要求量 - 有效成品归属。 */
    public BigDecimal orderRemainingTon(OrderRow order) {
        BigDecimal allocated = orderAllocatedTon(order.orderId());
        return order.requiredTon().subtract(allocated).max(BigDecimal.ZERO);
    }

    /** 物料按编码查询 ID。 */
    public Long materialIdByCode(String code) {
        return scalar("SELECT material_id FROM p2_material WHERE material_code = ?", Long.class, code);
    }

    /** 车间按编码查询 ID。 */
    public Long workshopIdByCode(String code) {
        return scalar("SELECT workshop_id FROM dim_workshop WHERE workshop_code = ?", Long.class, code);
    }

    // ---------------------------------------------------------------- 补充查询

    /** 通用单值查询（字典 ID 等）。 */
    public <T> T scalar(String sql, Class<T> type, Object... args) {
        List<T> rows = jdbc.queryForList(sql, type, args);
        return rows.isEmpty() ? null : rows.get(0);
    }

    /** 任务的全部投料事实（时间序）。 */
    public List<PlateInputRow> plateInputsByTask(long taskId) {
        return jdbc.query("""
                SELECT input_id, task_id, material_id, movement_id, production_group_id,
                       production_group_key, actual_date, input_quantity_ton, material_amount_yuan,
                       operator_name, recorded_at, version
                FROM p2_plate_input WHERE task_id = ? ORDER BY actual_date, input_id
                """, (rs, n) -> mapPlateInput(rs), taskId);
    }

    /** 任务的全部交接事实（按交出时间序）。 */
    public List<HandoverRow> handoversByTask(long taskId) {
        return jdbc.query(HANDOVER_SELECT + " WHERE h.task_id = ? ORDER BY h.sent_at, h.handover_id",
                (rs, n) -> mapHandover(rs), taskId);
    }

    private static PlateInputRow mapPlateInput(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new PlateInputRow(rs.getLong("input_id"), rs.getObject("task_id", Long.class),
                rs.getLong("material_id"), rs.getLong("movement_id"),
                rs.getObject("production_group_id", Long.class), rs.getString("production_group_key"),
                rs.getDate("actual_date").toLocalDate(), rs.getBigDecimal("input_quantity_ton"),
                rs.getBigDecimal("material_amount_yuan"), rs.getString("operator_name"),
                ts(rs.getTimestamp("recorded_at")), rs.getLong("version"));
    }

    /** 产出对应的成品归属（含订单与预留 ID）。 */
    public List<java.util.Map<String, Object>> allocationsByOutput(long outputId) {
        return jdbc.query("""
                SELECT fa.finished_allocation_id, fa.order_id, fa.material_id, fa.output_id,
                       fa.reservation_id, fa.source_type, fa.original_quantity_ton,
                       fa.allocated_quantity_ton, fa.released_quantity_ton, fa.version,
                       o.order_no
                FROM p2_finished_allocation fa
                JOIN p2_customer_order o ON o.order_id = fa.order_id
                WHERE fa.output_id = ? ORDER BY fa.finished_allocation_id
                """, (rs, n) -> {
            java.util.Map<String, Object> entry = new java.util.LinkedHashMap<>();
            entry.put("allocationId", String.valueOf(rs.getLong("finished_allocation_id")));
            entry.put("orderId", String.valueOf(rs.getLong("order_id")));
            entry.put("orderNo", rs.getString("order_no"));
            entry.put("materialId", String.valueOf(rs.getLong("material_id")));
            entry.put("reservationId", String.valueOf(rs.getLong("reservation_id")));
            entry.put("sourceType", rs.getString("source_type"));
            entry.put("quantityTon", rs.getBigDecimal("allocated_quantity_ton")
                    .setScale(3, java.math.RoundingMode.HALF_UP).toPlainString());
            entry.put("releasedTon", rs.getBigDecimal("released_quantity_ton")
                    .setScale(3, java.math.RoundingMode.HALF_UP).toPlainString());
            return entry;
        }, outputId);
    }

    /** 库存流水快照（记录详情的库存依据）。 */
    public java.util.Map<String, Object> movementSnapshot(long movementId) {
        List<java.util.Map<String, Object>> rows = jdbc.query("""
                SELECT movement_id, material_id, order_id, task_id, movement_type, source_type,
                       source_id, business_date, quantity_delta_ton, physical_after_ton,
                       operator_name, reason, recorded_at
                FROM p2_stock_movement WHERE movement_id = ?
                """, (rs, n) -> {
            java.util.Map<String, Object> entry = new java.util.LinkedHashMap<>();
            entry.put("movementId", String.valueOf(rs.getLong("movement_id")));
            entry.put("materialId", String.valueOf(rs.getLong("material_id")));
            entry.put("orderId", String.valueOf(rs.getLong("order_id")));
            entry.put("taskId", String.valueOf(rs.getLong("task_id")));
            entry.put("movementType", rs.getString("movement_type"));
            entry.put("sourceType", rs.getString("source_type"));
            entry.put("sourceId", rs.getString("source_id"));
            entry.put("businessDate", rs.getDate("business_date").toString());
            entry.put("quantityDeltaTon", rs.getBigDecimal("quantity_delta_ton")
                    .setScale(3, java.math.RoundingMode.HALF_UP).toPlainString());
            entry.put("physicalAfterTon", rs.getBigDecimal("physical_after_ton")
                    .setScale(3, java.math.RoundingMode.HALF_UP).toPlainString());
            entry.put("operatorName", rs.getString("operator_name"));
            return entry;
        }, movementId);
        return rows.isEmpty() ? null : rows.get(0);
    }

    // ---------------------------------------------------------------- 工具

    private static LocalDateTime ts(Timestamp timestamp) {
        return timestamp == null ? null : timestamp.toLocalDateTime();
    }
}
