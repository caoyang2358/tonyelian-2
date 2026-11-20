package cn.copper.phase2.production;

import cn.copper.phase2.common.BusinessException;
import cn.copper.phase2.common.ErrorCode;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.sql.Date;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 统一库存写入服务。所有实物过账、预留核销与供料兑现都经本服务，
 * 其他模块调用而不得各算一套（开发约定）。仅在已开启的业务事务内使用。
 *
 * <p>锁顺序约定（与订单与计划事务设计一致）：先锁物料余额行（按 material_id 升序），
 * 再锁预留/承诺行（按 id 升序）。流水的 (source_type, source_id, source_line_no)
 * 唯一键防止同一原始事实重复入账。</p>
 */
@Service
public class StockService {

    private final JdbcTemplate jdbc;

    public StockService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public record BalanceSnapshot(long materialId, BigDecimal physicalTon, long version) {
    }

    public record MovementResult(long movementId, BigDecimal quantityDeltaTon, BigDecimal physicalAfterTon) {
    }

    /** 读取并锁定物料余额行（FOR UPDATE）。行不存在视为零库存并补建。 */
    public BalanceSnapshot lockBalance(long materialId) {
        List<BalanceSnapshot> rows = jdbc.query("""
                SELECT material_id, physical_quantity_ton, version FROM p2_stock_balance
                WHERE material_id = ? FOR UPDATE
                """, (rs, n) -> new BalanceSnapshot(rs.getLong(1), rs.getBigDecimal(2), rs.getLong(3)),
                materialId);
        if (!rows.isEmpty()) {
            return rows.get(0);
        }
        jdbc.update("INSERT INTO p2_stock_balance (material_id, physical_quantity_ton) VALUES (?, 0)",
                materialId);
        return new BalanceSnapshot(materialId, BigDecimal.ZERO, 0);
    }

    /** 只读余额（不锁行），用于查询接口的库存版本快照。 */
    public BalanceSnapshot readBalance(long materialId) {
        List<BalanceSnapshot> rows = jdbc.query("""
                SELECT material_id, physical_quantity_ton, version FROM p2_stock_balance
                WHERE material_id = ?
                """, (rs, n) -> new BalanceSnapshot(rs.getLong(1), rs.getBigDecimal(2), rs.getLong(3)),
                materialId);
        return rows.isEmpty() ? new BalanceSnapshot(materialId, BigDecimal.ZERO, 0) : rows.get(0);
    }

    /**
     * 过账一笔库存流水并更新实物余额。
     *
     * @param sourceType 来源事实类型（如 PLATE_INPUT / ROD_OUTPUT）
     * @param sourceId   来源事实 ID（含 sourceLineNo 形成防重复唯一键）
     */
    public MovementResult postMovement(long materialId, String movementType, Long orderId, Long taskId,
                                       String sourceType, String sourceId, int sourceLineNo,
                                       String requestId, LocalDate businessDate, BigDecimal deltaTon,
                                       String operatorName, String reason, Long correctsMovementId) {
        BalanceSnapshot before = lockBalance(materialId);
        BigDecimal after = before.physicalTon().add(deltaTon);
        if (after.compareTo(BigDecimal.ZERO) < 0) {
            Map<String, Object> conflicts = new LinkedHashMap<>();
            conflicts.put("materialId", materialId);
            conflicts.put("availableTon", ProductionTypes.ton(before.physicalTon()));
            conflicts.put("requestedTon", ProductionTypes.ton(deltaTon.negate()));
            throw new BusinessException(ErrorCode.VALIDATION_ERROR,
                    "库存实物不足，无法完成本次扣减。", Map.of("conflicts", conflicts));
        }
        jdbc.update("""
                INSERT INTO p2_stock_movement
                    (material_id, order_id, task_id, movement_type, source_type, source_id, source_line_no,
                     request_id, corrects_movement_id, business_date, quantity_delta_ton, physical_after_ton,
                     operator_name, reason)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, materialId, orderId, taskId, movementType, sourceType, sourceId, sourceLineNo,
                requestId, correctsMovementId, Date.valueOf(businessDate), deltaTon, after,
                operatorName, reason);
        long movementId = jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
        jdbc.update("""
                UPDATE p2_stock_balance SET physical_quantity_ton = ?, version = version + 1
                WHERE material_id = ?
                """, after, materialId);
        return new MovementResult(movementId, deltaTon, after);
    }

    public record ReservationRow(long reservationId, long materialId, Long orderId, Long taskId,
                                 String demandKey, String sourceType, BigDecimal reservedTon,
                                 BigDecimal consumedTon, BigDecimal releasedTon, long version) {
    }

    /** 读取并锁定任务适用的预留行（按 reservation_id 升序加锁）。 */
    public List<ReservationRow> lockReservationsByTask(long taskId, String demandKey) {
        return jdbc.query("""
                SELECT reservation_id, material_id, order_id, task_id, demand_key, source_type,
                       reserved_quantity_ton, consumed_quantity_ton, released_quantity_ton, version
                FROM p2_stock_reservation
                WHERE task_id = ? AND (demand_key = ? OR demand_key IS NULL)
                ORDER BY reservation_id
                FOR UPDATE
                """, (rs, n) -> new ReservationRow(rs.getLong(1), rs.getLong(2), rs.getObject(3, Long.class),
                rs.getLong(4), rs.getString(5), rs.getString(6), rs.getBigDecimal(7),
                rs.getBigDecimal(8), rs.getBigDecimal(9), rs.getLong(10)), taskId, demandKey);
    }

    public List<ReservationRow> lockReservationsByTask(long taskId) {
        return jdbc.query("""
                SELECT reservation_id, material_id, order_id, task_id, demand_key, source_type,
                       reserved_quantity_ton, consumed_quantity_ton, released_quantity_ton, version
                FROM p2_stock_reservation
                WHERE task_id = ?
                ORDER BY reservation_id
                FOR UPDATE
                """, (rs, n) -> new ReservationRow(rs.getLong(1), rs.getLong(2), rs.getObject(3, Long.class),
                rs.getLong(4), rs.getString(5), rs.getString(6), rs.getBigDecimal(7),
                rs.getBigDecimal(8), rs.getBigDecimal(9), rs.getLong(10)), taskId);
    }

    /** 按剩余量核销预留；返回本次实际核销量。 */
    public BigDecimal consumeReservation(ReservationRow row, BigDecimal quantity) {
        BigDecimal remaining = row.reservedTon().subtract(row.consumedTon()).subtract(row.releasedTon());
        BigDecimal applied = quantity.min(remaining.max(BigDecimal.ZERO));
        if (applied.compareTo(BigDecimal.ZERO) > 0) {
            jdbc.update("""
                    UPDATE p2_stock_reservation SET consumed_quantity_ton = consumed_quantity_ton + ?,
                        version = version + 1 WHERE reservation_id = ?
                    """, applied, row.reservationId());
        }
        return applied;
    }

    /** 释放任务不再需要的预留量，返回实际释放量。 */
    public BigDecimal releaseReservation(ReservationRow row, BigDecimal quantity) {
        BigDecimal remaining = row.reservedTon().subtract(row.consumedTon()).subtract(row.releasedTon());
        BigDecimal applied = quantity.min(remaining.max(BigDecimal.ZERO));
        if (applied.compareTo(BigDecimal.ZERO) > 0) {
            jdbc.update("""
                    UPDATE p2_stock_reservation SET released_quantity_ton = released_quantity_ton + ?,
                        version = version + 1 WHERE reservation_id = ?
                    """, applied, row.reservationId());
        }
        return applied;
    }

    public record CommitmentRow(long commitmentId, long sourceTaskId, long targetTaskId, long materialId,
                                String demandKey, BigDecimal committedTon, BigDecimal fulfilledTon,
                                BigDecimal releasedTon, long version) {
    }

    /** 读取并锁定以该任务为来源（产出兑现）的供料承诺。 */
    public List<CommitmentRow> lockCommitmentsBySourceTask(long sourceTaskId) {
        return jdbc.query("""
                SELECT supply_commitment_id, source_task_id, target_task_id, material_id, demand_key,
                       committed_quantity_ton, fulfilled_quantity_ton, released_quantity_ton, version
                FROM p2_supply_commitment WHERE source_task_id = ? ORDER BY supply_commitment_id
                FOR UPDATE
                """, (rs, n) -> new CommitmentRow(rs.getLong(1), rs.getLong(2), rs.getLong(3),
                rs.getLong(4), rs.getString(5), rs.getBigDecimal(6), rs.getBigDecimal(7),
                rs.getBigDecimal(8), rs.getLong(9)), sourceTaskId);
    }

    /** 兑现供料承诺：合格铜杆入库时按量推进，返回实际兑现量。 */
    public BigDecimal fulfillCommitment(CommitmentRow row, BigDecimal quantity) {
        BigDecimal remaining = row.committedTon().subtract(row.fulfilledTon()).subtract(row.releasedTon());
        BigDecimal applied = quantity.min(remaining.max(BigDecimal.ZERO));
        if (applied.compareTo(BigDecimal.ZERO) > 0) {
            jdbc.update("""
                    UPDATE p2_supply_commitment SET fulfilled_quantity_ton = fulfilled_quantity_ton + ?,
                        version = version + 1 WHERE supply_commitment_id = ?
                    """, applied, row.commitmentId());
        }
        return applied;
    }

    /** 事实行版本冲突校验。 */
    public void checkVersion(long expected, long current, String what) {
        if (expected != current) {
            throw cn.copper.phase2.common.BusinessException.versionConflict(expected, current);
        }
    }

    /** 幂等载荷内的当前时刻（保证序列化稳定由调用方负责）。 */
    public static LocalDateTime now() {
        return LocalDateTime.now(ProductionTypes.ZONE);
    }
}
