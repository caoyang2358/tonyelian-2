package cn.copper.phase2.costing;

import cn.copper.phase2.common.Json;
import com.fasterxml.jackson.core.type.TypeReference;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.sql.Date;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 成本模块的数据访问。
 *
 * <p>只读 {@code p2_stock_movement}、{@code p2_output_record}、{@code p2_plate_input}、
 * {@code p2_rod_handover} 这些既有事实表，写 {@code p2_cost_run}、
 * {@code p2_stock_cost_ledger}、{@code p2_order_cost_item} 三张 V6 已建的成本表。
 * <b>不写</b>库存余额、流水与订单事实——成本只投影价值，不制造第二次数量。</p>
 *
 * <p>刻意不引用 {@code production_group_id} / {@code voided_at} 这类尚未评审的列：
 * 当前开发库只应用到 V11，用了这些列的查询在本机直接报 Unknown column。</p>
 */
@Repository
public class CostRepository {
    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");
    private static final TypeReference<List<Map<String, Object>>> REF_TYPE = new TypeReference<>() {
    };

    private final JdbcTemplate jdbc;

    public CostRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** 回放范围：为空表示全部。 */
    public record Scope(List<Long> materialIds, List<Long> orderIds, LocalDate from, LocalDate to) {
        public static Scope all() {
            return new Scope(List.of(), List.of(), null, null);
        }
    }

    public record MaterialRow(long materialId, String code, String name, String type, String specification) {
    }

    public record MovementRow(long movementId, long materialId, Long orderId, Long taskId,
                              String movementType, String sourceType, String sourceId,
                              LocalDate businessDate, BigDecimal quantityDelta, Long correctsMovementId) {
    }

    public record OutputRow(long outputId, long materialId, Long taskId, String groupKey,
                            LocalDate actualDate, BigDecimal qualifiedTon, String recordType,
                            long movementId, List<CostEngine.RodUseRef> rodRefs) {
    }

    public record PlateInputRow(Long taskId, String groupKey, LocalDate actualDate, BigDecimal amountYuan) {
    }

    public record HandoverRow(long handoverId, long movementId, BigDecimal sentQuantityTon) {
    }

    public record BalanceRow(long materialId, BigDecimal physicalQuantityTon, long version) {
    }

    public record RunRow(long costRunId, Long previousCostRunId, String status, String triggerType,
                         String ruleVersion, String affectedScope, String inputSnapshot, String missingItems,
                         String reason, String operatorName, OffsetDateTime requestedAt,
                         OffsetDateTime startedAt, OffsetDateTime completedAt, long version) {
    }

    public record LedgerRow(long stockCostLedgerId, long costRunId, long materialId, Long orderId,
                            Long movementId, String entryType, String sourceType, long sourceId,
                            String shareKey, LocalDate businessDate, BigDecimal quantityBasisTon,
                            BigDecimal unitCostYuanPerTon, BigDecimal costDeltaYuan, String missingItems) {
    }

    public record OrderCostTotal(Long orderId, BigDecimal amountYuan, long itemCount, long missingCount) {
    }

    public record OrderRow(long orderId, String orderNo, String customerName, long wireMaterialId,
                           BigDecimal requiredQuantityTon, LocalDate dueDate, BigDecimal contractAmountYuan,
                           BigDecimal agreedAmountYuan, boolean amountConfirmed, String productionResult,
                           String deliveryResult, String accountingState, OffsetDateTime completedAt,
                           OffsetDateTime cancelledAt, long version) {
    }

    public record OrderItemRow(long orderCostItemId, long costRunId, long orderId, Long stockCostLedgerId,
                              Long energyAllocationId, String itemType, String sourceKey,
                              LocalDate businessDate, BigDecimal amountYuan, String missingItems) {
    }

    private static final RowMapper<MaterialRow> MATERIAL = (rs, rowNum) -> new MaterialRow(
            rs.getLong("material_id"), rs.getString("material_code"), rs.getString("material_name"),
            rs.getString("material_type"), rs.getString("specification"));

    private static final RowMapper<MovementRow> MOVEMENT = (rs, rowNum) -> new MovementRow(
            rs.getLong("movement_id"), rs.getLong("material_id"), nullableLong(rs, "order_id"),
            nullableLong(rs, "task_id"), rs.getString("movement_type"), rs.getString("source_type"),
            rs.getString("source_id"), rs.getDate("business_date").toLocalDate(),
            rs.getBigDecimal("quantity_delta_ton"), nullableLong(rs, "corrects_movement_id"));

    private static final RowMapper<RunRow> RUN = (rs, rowNum) -> new RunRow(
            rs.getLong("cost_run_id"), nullableLong(rs, "previous_cost_run_id"), rs.getString("status"),
            rs.getString("trigger_type"), rs.getString("rule_version"), rs.getString("affected_scope"),
            rs.getString("input_snapshot"), rs.getString("missing_items"), rs.getString("reason"),
            rs.getString("operator_name"), offset(rs.getTimestamp("requested_at")),
            offset(rs.getTimestamp("started_at")), offset(rs.getTimestamp("completed_at")),
            rs.getLong("version"));

    private static final RowMapper<LedgerRow> LEDGER = (rs, rowNum) -> new LedgerRow(
            rs.getLong("stock_cost_ledger_id"), rs.getLong("cost_run_id"), rs.getLong("material_id"),
            nullableLong(rs, "order_id"), nullableLong(rs, "movement_id"), rs.getString("entry_type"),
            rs.getString("source_type"), rs.getLong("source_id"), rs.getString("share_key"),
            rs.getDate("business_date").toLocalDate(), rs.getBigDecimal("quantity_basis_ton"),
            rs.getBigDecimal("unit_cost_yuan_per_ton"), rs.getBigDecimal("cost_delta_yuan"),
            rs.getString("missing_items"));

    private static final RowMapper<OrderRow> ORDER = (rs, rowNum) -> new OrderRow(
            rs.getLong("order_id"), rs.getString("order_no"), rs.getString("customer_name"),
            rs.getLong("wire_material_id"), rs.getBigDecimal("required_quantity_ton"),
            rs.getDate("due_date").toLocalDate(), rs.getBigDecimal("contract_amount_yuan"),
            rs.getBigDecimal("agreed_amount_yuan"), rs.getBoolean("amount_confirmed"),
            rs.getString("production_result"), rs.getString("delivery_result"),
            rs.getString("accounting_state"), offset(rs.getTimestamp("completed_at")),
            offset(rs.getTimestamp("cancelled_at")), rs.getLong("version"));

    private static final RowMapper<OrderItemRow> ORDER_ITEM = (rs, rowNum) -> new OrderItemRow(
            rs.getLong("order_cost_item_id"), rs.getLong("cost_run_id"), rs.getLong("order_id"),
            nullableLong(rs, "stock_cost_ledger_id"), nullableLong(rs, "energy_allocation_id"),
            rs.getString("item_type"), rs.getString("source_key"),
            rs.getDate("business_date").toLocalDate(), rs.getBigDecimal("amount_yuan"),
            rs.getString("missing_items"));

    // ---------------------------------------------------------------- 事实读取

    public List<MaterialRow> materials() {
        return jdbc.query("""
                SELECT material_id, material_code, material_name, material_type, specification
                  FROM p2_material ORDER BY material_id
                """, MATERIAL);
    }

    public Optional<MaterialRow> material(long materialId) {
        return jdbc.query("""
                SELECT material_id, material_code, material_name, material_type, specification
                  FROM p2_material WHERE material_id = ?
                """, MATERIAL, materialId).stream().findFirst();
    }

    public List<MovementRow> movements(Scope scope) {
        StringBuilder sql = new StringBuilder("""
                SELECT movement_id, material_id, order_id, task_id, movement_type, source_type,
                       source_id, business_date, quantity_delta_ton, corrects_movement_id
                  FROM p2_stock_movement WHERE 1 = 1
                """);
        List<Object> args = new ArrayList<>();
        appendIn(sql, args, "material_id", scope.materialIds());
        appendIn(sql, args, "order_id", scope.orderIds());
        // 只设上界，不设下界：成本是按流水顺序回放的，截掉期初就等于让第一批出库
        // 从「实物 0」开始扣，必然算出负库存。调用方给的 from 只表示「从哪天开始关心」，
        // 不表示「之前的流水不用看」。
        if (scope.to() != null) {
            sql.append(" AND business_date <= ?");
            args.add(Date.valueOf(scope.to()));
        }
        sql.append(" ORDER BY material_id, business_date, movement_id");
        return jdbc.query(sql.toString(), MOVEMENT, args.toArray());
    }

    public List<MovementRow> movementsByIds(Collection<Long> movementIds) {
        if (movementIds.isEmpty()) {
            return List.of();
        }
        StringBuilder sql = new StringBuilder("""
                SELECT movement_id, material_id, order_id, task_id, movement_type, source_type,
                       source_id, business_date, quantity_delta_ton, corrects_movement_id
                  FROM p2_stock_movement WHERE movement_id IN (
                """);
        appendPlaceholders(sql, movementIds.size());
        sql.append(")");
        return jdbc.query(sql.toString(), MOVEMENT, movementIds.toArray());
    }

    public List<OutputRow> outputs(Collection<Long> movementIds) {
        if (movementIds.isEmpty()) {
            return List.of();
        }
        StringBuilder sql = new StringBuilder("""
                SELECT output_id, material_id, task_id, production_group_key, actual_date,
                       qualified_quantity_ton, record_type, movement_id, input_references
                  FROM p2_output_record WHERE movement_id IN (
                """);
        List<Object> args = new ArrayList<>(movementIds);
        appendPlaceholders(sql, movementIds.size());
        sql.append(") AND record_type = 'QUALIFIED_OUTPUT'");
        return jdbc.query(sql.toString(), (rs, rowNum) -> {
            Long taskId = nullableLong(rs, "task_id");
            String group = rs.getString("production_group_key");
            LocalDate date = rs.getDate("actual_date").toLocalDate();
            String groupKey = group != null && !group.isBlank()
                    ? "G:" + group
                    : "T:" + (taskId == null ? "N" : taskId) + ":" + date;
            return new OutputRow(rs.getLong("output_id"), rs.getLong("material_id"), taskId, groupKey,
                    date, rs.getBigDecimal("qualified_quantity_ton"), rs.getString("record_type"),
                    rs.getLong("movement_id"), rodRefs(rs.getString("input_references")));
        }, args.toArray());
    }

    public List<PlateInputRow> plateInputs(Collection<Long> taskIds, Collection<String> groupKeys) {
        if (taskIds.isEmpty() && groupKeys.isEmpty()) {
            return List.of();
        }
        StringBuilder sql = new StringBuilder("""
                SELECT task_id, production_group_key, actual_date, material_amount_yuan
                  FROM p2_plate_input WHERE
                """);
        List<Object> args = new ArrayList<>();
        List<String> clauses = new ArrayList<>();
        if (!taskIds.isEmpty()) {
            StringBuilder in = new StringBuilder("task_id IN (");
            appendPlaceholders(in, taskIds.size());
            in.append(")");
            clauses.add(in.toString());
            args.addAll(taskIds);
        }
        if (!groupKeys.isEmpty()) {
            List<String> keys = groupKeys.stream()
                    .filter(key -> key.startsWith("G:"))
                    .map(key -> key.substring(2))
                    .toList();
            if (!keys.isEmpty()) {
                StringBuilder in = new StringBuilder("production_group_key IN (");
                appendPlaceholders(in, keys.size());
                in.append(")");
                clauses.add(in.toString());
                args.addAll(keys);
            }
        }
        if (clauses.isEmpty()) {
            return List.of();
        }
        sql.append(String.join(" OR ", clauses));
        return jdbc.query(sql.toString(), (rs, rowNum) -> {
            Long taskId = nullableLong(rs, "task_id");
            String group = rs.getString("production_group_key");
            LocalDate date = rs.getDate("actual_date").toLocalDate();
            String groupKey = group != null && !group.isBlank()
                    ? "G:" + group
                    : "T:" + (taskId == null ? "N" : taskId) + ":" + date;
            return new PlateInputRow(taskId, groupKey, date, rs.getBigDecimal("material_amount_yuan"));
        }, args.toArray());
    }

    public List<HandoverRow> handovers(Collection<Long> handoverIds) {
        if (handoverIds.isEmpty()) {
            return List.of();
        }
        StringBuilder sql = new StringBuilder("""
                SELECT handover_id, movement_id, sent_quantity_ton FROM p2_rod_handover
                 WHERE handover_id IN (
                """);
        appendPlaceholders(sql, handoverIds.size());
        sql.append(")");
        return jdbc.query(sql.toString(), (rs, rowNum) -> new HandoverRow(
                rs.getLong("handover_id"), rs.getLong("movement_id"),
                rs.getBigDecimal("sent_quantity_ton")), handoverIds.toArray());
    }

    public List<BalanceRow> balances() {
        return jdbc.query("""
                SELECT material_id, physical_quantity_ton, version FROM p2_stock_balance ORDER BY material_id
                """, (rs, rowNum) -> new BalanceRow(rs.getLong("material_id"),
                rs.getBigDecimal("physical_quantity_ton"), rs.getLong("version")));
    }

    /** 有效预留＝已预留−已消耗−已释放；不减总实物，只影响可用量。 */
    public Map<Long, BigDecimal> effectiveReservations() {
        Map<Long, BigDecimal> result = new LinkedHashMap<>();
        jdbc.query("""
                SELECT material_id,
                       COALESCE(SUM(reserved_quantity_ton - consumed_quantity_ton - released_quantity_ton), 0) AS effective
                  FROM p2_stock_reservation GROUP BY material_id
                """, rs -> {
            result.put(rs.getLong("material_id"), rs.getBigDecimal("effective"));
        });
        return result;
    }

    // ---------------------------------------------------------------- 成本表写入

    /**
     * 写入运行记录。
     *
     * <p>三个 JSON 列接收的是<b>对象</b>而不是已序列化的字符串：由这里统一
     * {@code writeValueAsString}。如果调用方先序列化再传进来，会写成「JSON 里的字符串」，
     * 读出来是一段文本而不是对象，快照就白存了。</p>
     */
    public long insertRun(Long previousCostRunId, String status, String triggerType, Object affectedScope,
                          Object inputSnapshot, Object missingItems, String reason, String operatorName) {
        KeyHolder keys = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            // started_at 用数据库时钟：表上有「started_at >= requested_at」的 CHECK，
            // 而 requested_at 的默认值由本语句求值。用 Java 侧的时间会早几毫秒，直接违反约束。
            PreparedStatement ps = connection.prepareStatement("""
                    INSERT INTO p2_cost_run
                        (previous_cost_run_id, status, trigger_type, rule_version, affected_scope,
                         input_snapshot, missing_items, reason, operator_name, started_at)
                    VALUES (?, ?, ?, ?, CAST(? AS JSON), CAST(? AS JSON), CAST(? AS JSON), ?, ?,
                            CURRENT_TIMESTAMP(3))
                    """, Statement.RETURN_GENERATED_KEYS);
            setNullableLong(ps, 1, previousCostRunId);
            ps.setString(2, status);
            ps.setString(3, triggerType);
            ps.setString(4, CostModels.RULE_VERSION);
            ps.setString(5, json(affectedScope));
            ps.setString(6, json(inputSnapshot));
            ps.setString(7, json(missingItems));
            ps.setString(8, reason);
            ps.setString(9, operatorName);
            return ps;
        }, keys);
        return requireKey(keys);
    }

    public void finishRun(long costRunId, String status, Object missingItems) {
        jdbc.update("""
                UPDATE p2_cost_run
                   SET status = ?, missing_items = CAST(? AS JSON), completed_at = CURRENT_TIMESTAMP(3),
                       version = version + 1
                 WHERE cost_run_id = ?
                """, status, json(missingItems), costRunId);
    }

    public Optional<RunRow> run(long costRunId) {
        return jdbc.query("""
                SELECT cost_run_id, previous_cost_run_id, status, trigger_type, rule_version,
                       affected_scope, input_snapshot, missing_items, reason, operator_name,
                       requested_at, started_at, completed_at, version
                  FROM p2_cost_run WHERE cost_run_id = ?
                """, RUN, costRunId).stream().findFirst();
    }

    /** 当前有效结果＝最近一次已提交承诺结果的运行；执行中与失败的候选不能作为当前结果。 */
    public Optional<RunRow> latestPublishedRun() {
        return jdbc.query("""
                SELECT cost_run_id, previous_cost_run_id, status, trigger_type, rule_version,
                       affected_scope, input_snapshot, missing_items, reason, operator_name,
                       requested_at, started_at, completed_at, version
                  FROM p2_cost_run WHERE status IN ('COMPLETED', 'INCOMPLETE')
                 ORDER BY cost_run_id DESC LIMIT 1
                """, RUN).stream().findFirst();
    }

    /**
     * 本次运行的能源分摊。只读本运行自己的行，不串用上一次运行的结果——
     * 补算产出的新成本必须和同一版本的分摊配套，否则金额与依据对不上。
     */
    public List<CostEngine.EnergyFact> energyAllocations(long costRunId) {
        return jdbc.query("""
                SELECT energy_allocation_id, workshop_id, actual_date, energy_type, target_type,
                       order_id, output_id, qualified_quantity_ton, amount_yuan
                  FROM p2_energy_allocation
                 WHERE cost_run_id = ?
                 ORDER BY energy_allocation_id
                """, (rs, rowNum) -> new CostEngine.EnergyFact(
                        rs.getLong("energy_allocation_id"),
                        rs.getLong("workshop_id"),
                        rs.getDate("actual_date").toLocalDate(),
                        rs.getString("energy_type"),
                        rs.getString("target_type"),
                        (Long) rs.getObject("order_id", Long.class),
                        (Long) rs.getObject("output_id", Long.class),
                        rs.getBigDecimal("qualified_quantity_ton"),
                        rs.getBigDecimal("amount_yuan")), costRunId);
    }

    public long insertLedger(long costRunId, CostEngine.LedgerDraft draft) {
        KeyHolder keys = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            PreparedStatement ps = connection.prepareStatement("""
                    INSERT INTO p2_stock_cost_ledger
                        (cost_run_id, material_id, order_id, movement_id, entry_type, source_type,
                         source_id, share_key, business_date, quantity_basis_ton,
                         unit_cost_yuan_per_ton, cost_delta_yuan, missing_items)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, CAST(? AS JSON))
                    """, Statement.RETURN_GENERATED_KEYS);
            ps.setLong(1, costRunId);
            ps.setLong(2, draft.materialId());
            setNullableLong(ps, 3, draft.orderId());
            setNullableLong(ps, 4, draft.movementId());
            ps.setString(5, draft.entryType());
            ps.setString(6, draft.sourceType());
            ps.setLong(7, draft.sourceId());
            ps.setString(8, CostModels.SHARE_POOL);
            ps.setDate(9, Date.valueOf(draft.businessDate()));
            ps.setBigDecimal(10, draft.quantityTon());
            ps.setBigDecimal(11, draft.unitCostYuanPerTon());
            ps.setBigDecimal(12, draft.costDeltaYuan());
            ps.setString(13, draft.missingItems().isEmpty() ? null : json(draft.missingItems()));
            return ps;
        }, keys);
        return requireKey(keys);
    }

    public void insertOrderItem(long costRunId, CostEngine.OrderDraft draft, Long ledgerId) {
        // 表上 CHECK 要求：能源项只带 energy_allocation_id，领用项只带 stock_cost_ledger_id，
        // 两个都填或都不填都会被拒。这里按类型取对应引用，不做「保险起见都写上」。
        Long allocationId = CostModels.ITEM_WORKSHOP_ENERGY.equals(draft.itemType())
                ? draft.energyAllocationId() : null;
        Long ledgerRef = CostModels.ITEM_WORKSHOP_ENERGY.equals(draft.itemType()) ? null : ledgerId;
        jdbc.update("""
                INSERT INTO p2_order_cost_item
                    (cost_run_id, order_id, stock_cost_ledger_id, energy_allocation_id, item_type,
                     source_key, business_date, amount_yuan, missing_items)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, CAST(? AS JSON))
                """, costRunId, draft.orderId(), ledgerRef, allocationId, draft.itemType(),
                draft.sourceKey(), Date.valueOf(draft.businessDate()), draft.amountYuan(),
                draft.missingItems().isEmpty() ? null : json(draft.missingItems()));
    }

    public void updateAccountingState(long orderId, String state) {
        jdbc.update("UPDATE p2_customer_order SET accounting_state = ? WHERE order_id = ?", state, orderId);
    }

    // ---------------------------------------------------------------- 成本表读取

    public List<LedgerRow> ledger(long costRunId, Long materialId, Long orderId, String entryType,
                                  LocalDate from, LocalDate to, int limit, int offset) {
        StringBuilder sql = new StringBuilder("""
                SELECT stock_cost_ledger_id, cost_run_id, material_id, order_id, movement_id,
                       entry_type, source_type, source_id, share_key, business_date,
                       quantity_basis_ton, unit_cost_yuan_per_ton, cost_delta_yuan, missing_items
                  FROM p2_stock_cost_ledger WHERE cost_run_id = ?
                """);
        List<Object> args = new ArrayList<>();
        args.add(costRunId);
        appendEquals(sql, args, "material_id", materialId);
        appendEquals(sql, args, "order_id", orderId);
        appendEquals(sql, args, "entry_type", entryType);
        if (from != null) {
            sql.append(" AND business_date >= ?");
            args.add(Date.valueOf(from));
        }
        if (to != null) {
            sql.append(" AND business_date <= ?");
            args.add(Date.valueOf(to));
        }
        sql.append(" ORDER BY business_date, stock_cost_ledger_id LIMIT ? OFFSET ?");
        args.add(limit);
        args.add(offset);
        return jdbc.query(sql.toString(), LEDGER, args.toArray());
    }

    public long countLedger(long costRunId, Long materialId, Long orderId, String entryType,
                            LocalDate from, LocalDate to) {
        StringBuilder sql = new StringBuilder("SELECT COUNT(*) FROM p2_stock_cost_ledger WHERE cost_run_id = ?");
        List<Object> args = new ArrayList<>();
        args.add(costRunId);
        appendEquals(sql, args, "material_id", materialId);
        appendEquals(sql, args, "order_id", orderId);
        appendEquals(sql, args, "entry_type", entryType);
        if (from != null) {
            sql.append(" AND business_date >= ?");
            args.add(Date.valueOf(from));
        }
        if (to != null) {
            sql.append(" AND business_date <= ?");
            args.add(Date.valueOf(to));
        }
        Long total = jdbc.queryForObject(sql.toString(), Long.class, args.toArray());
        return total == null ? 0 : total;
    }

    /** 按物料汇总当前运行的已知价值与缺项行数，供 K01 使用。 */
    public Map<Long, MaterialValue> materialValues(long costRunId) {
        Map<Long, MaterialValue> values = new LinkedHashMap<>();
        jdbc.query("""
                SELECT material_id,
                       COALESCE(SUM(cost_delta_yuan), 0) AS value_yuan,
                       SUM(CASE WHEN missing_items IS NULL THEN 0 ELSE 1 END) AS missing_rows
                  FROM p2_stock_cost_ledger WHERE cost_run_id = ? GROUP BY material_id
                """, rs -> {
            values.put(rs.getLong("material_id"), new MaterialValue(
                    rs.getBigDecimal("value_yuan"), rs.getLong("missing_rows")));
        }, costRunId);
        return values;
    }

    public record MaterialValue(BigDecimal valueYuan, long missingRows) {
    }

    public List<OrderCostTotal> orderCostTotals(long costRunId) {
        return jdbc.query("""
                SELECT order_id, COALESCE(SUM(amount_yuan), 0) AS amount_yuan, COUNT(*) AS item_count,
                       SUM(CASE WHEN missing_items IS NULL THEN 0 ELSE 1 END) AS missing_count
                  FROM p2_order_cost_item WHERE cost_run_id = ? GROUP BY order_id
                """, (rs, rowNum) -> new OrderCostTotal(rs.getLong("order_id"),
                rs.getBigDecimal("amount_yuan"), rs.getLong("item_count"), rs.getLong("missing_count")),
                costRunId);
    }

    public List<OrderItemRow> orderItems(long costRunId, long orderId) {
        return jdbc.query("""
                SELECT order_cost_item_id, cost_run_id, order_id, stock_cost_ledger_id,
                       energy_allocation_id, item_type, source_key, business_date, amount_yuan, missing_items
                  FROM p2_order_cost_item WHERE cost_run_id = ? AND order_id = ?
                 ORDER BY business_date, order_cost_item_id
                """, ORDER_ITEM, costRunId, orderId);
    }

    public List<OrderRow> orders(Long orderId, String accountingState, int limit, int offset) {
        StringBuilder sql = new StringBuilder("""
                SELECT order_id, order_no, customer_name, wire_material_id, required_quantity_ton,
                       due_date, contract_amount_yuan, agreed_amount_yuan, amount_confirmed,
                       production_result, delivery_result, accounting_state, completed_at, cancelled_at, version
                  FROM p2_customer_order WHERE 1 = 1
                """);
        List<Object> args = new ArrayList<>();
        appendEquals(sql, args, "order_id", orderId);
        if (accountingState != null && !accountingState.isBlank()) {
            sql.append(" AND accounting_state = ?");
            args.add(accountingState);
        }
        sql.append(" ORDER BY order_id DESC LIMIT ? OFFSET ?");
        args.add(limit);
        args.add(offset);
        return jdbc.query(sql.toString(), ORDER, args.toArray());
    }

    public long countOrders(Long orderId, String accountingState) {
        StringBuilder sql = new StringBuilder("SELECT COUNT(*) FROM p2_customer_order WHERE 1 = 1");
        List<Object> args = new ArrayList<>();
        appendEquals(sql, args, "order_id", orderId);
        if (accountingState != null && !accountingState.isBlank()) {
            sql.append(" AND accounting_state = ?");
            args.add(accountingState);
        }
        Long total = jdbc.queryForObject(sql.toString(), Long.class, args.toArray());
        return total == null ? 0 : total;
    }

    public Optional<OrderRow> order(long orderId) {
        return orders(orderId, null, 1, 0).stream().findFirst();
    }

    public long ledgerMovementId(long ledgerId) {
        Long value = jdbc.queryForObject(
                "SELECT movement_id FROM p2_stock_cost_ledger WHERE stock_cost_ledger_id = ?",
                Long.class, ledgerId);
        return value == null ? 0L : value;
    }

    public Optional<Long> nextRun(long costRunId) {
        return jdbc.query("""
                SELECT cost_run_id FROM p2_cost_run WHERE previous_cost_run_id = ?
                 ORDER BY cost_run_id DESC LIMIT 1
                """, (rs, rowNum) -> rs.getLong("cost_run_id"), costRunId).stream().findFirst();
    }

    /** 运行结果概要：条数、价值合计、缺项行数。K06 用它说明「这次到底算了什么」。 */
    public record RunSummary(long ledgerRows, BigDecimal costDeltaYuan, long missingRows,
                             long materialCount, long orderItemCount, BigDecimal orderAmountYuan) {
    }

    public RunSummary runSummary(long costRunId) {
        return jdbc.queryForObject("""
                SELECT (SELECT COUNT(*) FROM p2_stock_cost_ledger WHERE cost_run_id = ?) AS ledger_rows,
                       (SELECT COALESCE(SUM(cost_delta_yuan), 0) FROM p2_stock_cost_ledger
                         WHERE cost_run_id = ?) AS cost_delta_yuan,
                       (SELECT COUNT(*) FROM p2_stock_cost_ledger
                         WHERE cost_run_id = ? AND missing_items IS NOT NULL) AS missing_rows,
                       (SELECT COUNT(DISTINCT material_id) FROM p2_stock_cost_ledger
                         WHERE cost_run_id = ?) AS material_count,
                       (SELECT COUNT(*) FROM p2_order_cost_item WHERE cost_run_id = ?) AS order_item_count,
                       (SELECT COALESCE(SUM(amount_yuan), 0) FROM p2_order_cost_item
                         WHERE cost_run_id = ?) AS order_amount_yuan
                """, (rs, rowNum) -> new RunSummary(rs.getLong("ledger_rows"),
                rs.getBigDecimal("cost_delta_yuan"), rs.getLong("missing_rows"),
                rs.getLong("material_count"), rs.getLong("order_item_count"),
                rs.getBigDecimal("order_amount_yuan")),
                costRunId, costRunId, costRunId, costRunId, costRunId, costRunId);
    }

    // ---------------------------------------------------------------- 工具

    private static List<CostEngine.RodUseRef> rodRefs(String inputReferences) {
        if (inputReferences == null || inputReferences.isBlank()) {
            return List.of();
        }
        try {
            List<Map<String, Object>> refs = Json.MAPPER.readValue(inputReferences, REF_TYPE);
            List<CostEngine.RodUseRef> result = new ArrayList<>();
            for (Map<String, Object> ref : refs) {
                Object handoverId = ref.get("handoverId");
                Object sent = ref.get("sentQuantityTon");
                if (handoverId == null || sent == null) {
                    continue;
                }
                result.add(new CostEngine.RodUseRef(Long.parseLong(String.valueOf(handoverId)),
                        new BigDecimal(String.valueOf(sent))));
            }
            return result;
        } catch (Exception unreadable) {
            // 引用读不出来就是「对应关系不明」，不能当成「没有引用」——那会让成本变成
            // 完整但错误的数字。这里返回空表示无引用，由上层标缺项。
            return List.of();
        }
    }

    private static void appendIn(StringBuilder sql, List<Object> args, String column, List<Long> values) {
        if (values == null || values.isEmpty()) {
            return;
        }
        sql.append(" AND ").append(column).append(" IN (");
        appendPlaceholders(sql, values.size());
        sql.append(")");
        args.addAll(values);
    }

    private static void appendEquals(StringBuilder sql, List<Object> args, String column, Object value) {
        if (value == null) {
            return;
        }
        sql.append(" AND ").append(column).append(" = ?");
        args.add(value);
    }

    private static void appendPlaceholders(StringBuilder sql, int count) {
        for (int index = 0; index < count; index++) {
            if (index > 0) {
                sql.append(", ");
            }
            sql.append("?");
        }
    }

    private static void setNullableLong(PreparedStatement ps, int index, Long value) throws java.sql.SQLException {
        if (value == null) {
            ps.setNull(index, java.sql.Types.BIGINT);
        } else {
            ps.setLong(index, value);
        }
    }

    private static Long nullableLong(java.sql.ResultSet rs, String column) throws java.sql.SQLException {
        long value = rs.getLong(column);
        return rs.wasNull() ? null : value;
    }

    private static OffsetDateTime offset(Timestamp timestamp) {
        return timestamp == null ? null : timestamp.toLocalDateTime().atZone(ZONE).toOffsetDateTime();
    }

    private static String json(Object value) {
        if (value == null) {
            return null;
        }
        try {
            return Json.MAPPER.writeValueAsString(value);
        } catch (Exception failure) {
            throw new IllegalStateException("JSON 序列化失败：" + failure.getMessage(), failure);
        }
    }

    private static long requireKey(KeyHolder keys) {
        Number key = keys.getKey();
        if (key == null) {
            throw new IllegalStateException("插入成功但未取得自增主键。");
        }
        return key.longValue();
    }
}
