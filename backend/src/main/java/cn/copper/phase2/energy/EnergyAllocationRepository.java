package cn.copper.phase2.energy;

import cn.copper.phase2.common.Json;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.sql.Date;
import java.sql.PreparedStatement;
import java.sql.Types;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

/**
 * 分摊行的落库与回读（S2-E03 / S2-K05）。
 *
 * <p>分摊行是「某次核算运行下，某车间某日的某项能源，按什么比例算到谁头上」的唯一凭证。
 * 它挂在 {@code cost_run_id} 下，所以补算天然产生新的一批行，旧批次原样保留——
 * 想回答「上一次为什么是那个数」，直接按运行号取即可。</p>
 *
 * <p>本表有一组 CHECK 约束（目标为订单必须带 {@code order_id}、比例落在 0–1、金额非负），
 * 触发时 MySQL 报的是 `BadSqlGrammarException`，很容易被上层误报成「数据库连不上」。
 * 所以写入前先把约束条件在代码里过一遍，缺目标或比例越界直接给业务错误。</p>
 */
@Repository
public class EnergyAllocationRepository {
    private final JdbcTemplate jdbc;

    public EnergyAllocationRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 待写入的一行分摊。字段与表一一对应，不做内部换算——
     * 写成什么样，页面上看到的就是什么样。
     */
    public record Draft(
            long workshopId,
            LocalDate actualDate,
            String energyType,
            String meterSourceKey,
            OffsetDateTime sourceRefreshedAt,
            String sourceQuality,
            Object sourceSnapshot,
            String unitCode,
            BigDecimal dailyConsumption,
            Long energyPriceId,
            BigDecimal effectiveUnitPrice,
            String targetType,
            String targetKey,
            Long orderId,
            Long outputId,
            BigDecimal qualifiedQuantityTon,
            BigDecimal denominatorQuantityTon,
            BigDecimal allocationRatio,
            BigDecimal allocatedConsumption,
            BigDecimal amountYuan,
            List<String> missingItems) {
    }

    /** 某车间某日当前有效的分摊批次。 */
    public record PersistedRun(long costRunId, int rowCount, BigDecimal amountYuan) {}

    public void deleteByRun(long costRunId) {
        jdbc.update("DELETE FROM p2_energy_allocation WHERE cost_run_id = ?", costRunId);
    }

    public int insertAll(long costRunId, List<Draft> drafts) {
        if (drafts.isEmpty()) {
            return 0;
        }
        validate(drafts);
        int[][] counts = jdbc.batchUpdate("""
                INSERT INTO p2_energy_allocation
                    (cost_run_id, workshop_id, actual_date, energy_type, meter_source_key,
                     source_refreshed_at, source_quality, source_snapshot, unit_code,
                     daily_consumption, energy_price_id, effective_unit_price,
                     target_type, target_key, order_id, output_id, finished_allocation_id,
                     qualified_quantity_ton, denominator_quantity_ton, allocation_ratio,
                     allocated_consumption, amount_yuan, missing_items)
                VALUES (?, ?, ?, ?, ?, ?, ?, CAST(? AS JSON), ?, ?, ?, ?, ?, ?, ?, ?, NULL,
                        ?, ?, ?, ?, ?, CAST(? AS JSON))
                """, drafts, 200, (ps, draft) -> {
            int i = 1;
            ps.setLong(i++, costRunId);
            ps.setLong(i++, draft.workshopId());
            ps.setDate(i++, Date.valueOf(draft.actualDate()));
            ps.setString(i++, draft.energyType());
            ps.setString(i++, draft.meterSourceKey());
            setTime(ps, i++, draft.sourceRefreshedAt());
            ps.setString(i++, draft.sourceQuality());
            ps.setString(i++, json(draft.sourceSnapshot()));
            ps.setString(i++, draft.unitCode());
            ps.setBigDecimal(i++, draft.dailyConsumption());
            setLong(ps, i++, draft.energyPriceId());
            ps.setBigDecimal(i++, draft.effectiveUnitPrice());
            ps.setString(i++, draft.targetType());
            ps.setString(i++, draft.targetKey());
            setLong(ps, i++, draft.orderId());
            setLong(ps, i++, draft.outputId());
            ps.setBigDecimal(i++, draft.qualifiedQuantityTon());
            ps.setBigDecimal(i++, draft.denominatorQuantityTon());
            ps.setBigDecimal(i++, draft.allocationRatio());
            ps.setBigDecimal(i++, draft.allocatedConsumption());
            ps.setBigDecimal(i++, draft.amountYuan());
            ps.setString(i, draft.missingItems() == null || draft.missingItems().isEmpty()
                    ? null : json(draft.missingItems()));
        });
        int total = 0;
        for (int[] row : counts) {
            for (int value : row) {
                total += Math.max(value, 0);
            }
        }
        return total;
    }

    /**
     * 当前有效批次：只认已提交结果的运行（{@code COMPLETED}/{@code INCOMPLETE}）。
     * 执行中或失败的运行不能作为「费用已经落账」的依据。
     */
    public Optional<PersistedRun> currentByDay(long workshopId, LocalDate actualDate) {
        return jdbc.query("""
                SELECT a.cost_run_id, COUNT(*) AS row_count, COALESCE(SUM(a.amount_yuan), 0) AS amount
                  FROM p2_energy_allocation a
                  JOIN p2_cost_run r ON r.cost_run_id = a.cost_run_id
                 WHERE a.workshop_id = ? AND a.actual_date = ?
                   AND r.status IN ('COMPLETED', 'INCOMPLETE')
                 GROUP BY a.cost_run_id
                 ORDER BY a.cost_run_id DESC
                 LIMIT 1
                """, (rs, rowNum) -> new PersistedRun(
                        rs.getLong("cost_run_id"),
                        rs.getInt("row_count"),
                        rs.getBigDecimal("amount")), workshopId, Date.valueOf(actualDate))
                .stream().findFirst();
    }

    /** 某次运行写下的全部分摊行，供成本引擎按目标取金额。 */
    public List<StoredRow> byRun(long costRunId) {
        return jdbc.query("""
                SELECT energy_allocation_id, workshop_id, actual_date, energy_type, target_type,
                       target_key, order_id, output_id, qualified_quantity_ton,
                       denominator_quantity_ton, allocation_ratio, allocated_consumption, amount_yuan
                  FROM p2_energy_allocation
                 WHERE cost_run_id = ?
                 ORDER BY workshop_id, actual_date, energy_type, energy_allocation_id
                """, (rs, rowNum) -> new StoredRow(
                        rs.getLong("energy_allocation_id"),
                        rs.getLong("workshop_id"),
                        rs.getDate("actual_date").toLocalDate(),
                        rs.getString("energy_type"),
                        rs.getString("target_type"),
                        rs.getString("target_key"),
                        (Long) rs.getObject("order_id", Long.class),
                        (Long) rs.getObject("output_id", Long.class),
                        rs.getBigDecimal("qualified_quantity_ton"),
                        rs.getBigDecimal("denominator_quantity_ton"),
                        rs.getBigDecimal("allocation_ratio"),
                        rs.getBigDecimal("allocated_consumption"),
                        rs.getBigDecimal("amount_yuan")), costRunId);
    }

    public record StoredRow(long allocationId, long workshopId, LocalDate actualDate, String energyType,
                            String targetType, String targetKey, Long orderId, Long outputId,
                            BigDecimal qualifiedQuantityTon, BigDecimal denominatorQuantityTon,
                            BigDecimal allocationRatio, BigDecimal allocatedConsumption,
                            BigDecimal amountYuan) {}

    public List<Draft> draftsByRun(long costRunId) {
        return jdbc.query("""
                SELECT workshop_id, actual_date, energy_type, target_type, target_key, order_id,
                       output_id, qualified_quantity_ton, denominator_quantity_ton,
                       allocation_ratio, allocated_consumption, amount_yuan, unit_code,
                       daily_consumption, effective_unit_price, energy_price_id
                  FROM p2_energy_allocation WHERE cost_run_id = ?
                 ORDER BY energy_allocation_id
                """, (rs, rowNum) -> new Draft(
                        rs.getLong("workshop_id"),
                        rs.getDate("actual_date").toLocalDate(),
                        rs.getString("energy_type"),
                        null, null, null, null,
                        rs.getString("unit_code"),
                        rs.getBigDecimal("daily_consumption"),
                        (Long) rs.getObject("energy_price_id", Long.class),
                        rs.getBigDecimal("effective_unit_price"),
                        rs.getString("target_type"),
                        rs.getString("target_key"),
                        (Long) rs.getObject("order_id", Long.class),
                        (Long) rs.getObject("output_id", Long.class),
                        rs.getBigDecimal("qualified_quantity_ton"),
                        rs.getBigDecimal("denominator_quantity_ton"),
                        rs.getBigDecimal("allocation_ratio"),
                        rs.getBigDecimal("allocated_consumption"),
                        rs.getBigDecimal("amount_yuan"),
                        List.of()), costRunId);
    }

    /**
     * 写库前先自查约束。数据库那层的 CHECK 只会在 JDBC 层抛 SQL 语法/约束异常，
     * 上层很难分辨是「数据不合规」还是「库挂了」，所以在这里给出能定位的错误。
     */
    private static void validate(List<Draft> drafts) {
        for (Draft draft : drafts) {
            if (EnergyAllocationModels.TARGET_ORDER.equals(draft.targetType()) && draft.orderId() == null) {
                throw new IllegalStateException("订单分摊行缺少 order_id，车间 " + draft.workshopId()
                        + " 日期 " + draft.actualDate());
            }
            if (!EnergyAllocationModels.TARGET_ORDER.equals(draft.targetType()) && draft.orderId() != null) {
                throw new IllegalStateException("非订单分摊行不应带 order_id：" + draft.targetType());
            }
            if (draft.allocationRatio() != null
                    && (draft.allocationRatio().signum() < 0
                    || draft.allocationRatio().compareTo(BigDecimal.ONE) > 0)) {
                throw new IllegalStateException("分摊比例必须落在 0–1：" + draft.allocationRatio());
            }
            for (BigDecimal value : new BigDecimal[]{draft.dailyConsumption(), draft.effectiveUnitPrice(),
                    draft.qualifiedQuantityTon(), draft.denominatorQuantityTon(),
                    draft.allocatedConsumption(), draft.amountYuan()}) {
                if (value != null && value.signum() < 0) {
                    throw new IllegalStateException("分摊行出现负数：" + value);
                }
            }
        }
    }

    private static void setLong(PreparedStatement ps, int index, Long value) {
        try {
            if (value == null) {
                ps.setNull(index, Types.BIGINT);
            } else {
                ps.setLong(index, value);
            }
        } catch (Exception failure) {
            throw new IllegalStateException("写入分摊行失败：" + failure.getMessage(), failure);
        }
    }

    private static void setTime(PreparedStatement ps, int index, OffsetDateTime value) {
        try {
            if (value == null) {
                ps.setNull(index, Types.TIMESTAMP);
            } else {
                ps.setTimestamp(index, java.sql.Timestamp.valueOf(value.toLocalDateTime()));
            }
        } catch (Exception failure) {
            throw new IllegalStateException("写入分摊行失败：" + failure.getMessage(), failure);
        }
    }

    private static String json(Object value) {
        if (value == null) {
            return null;
        }
        try {
            return Json.MAPPER.writeValueAsString(value);
        } catch (Exception failure) {
            throw new IllegalStateException("分摊快照无法序列化：" + failure.getMessage(), failure);
        }
    }
}
