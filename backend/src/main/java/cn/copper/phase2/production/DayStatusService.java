package cn.copper.phase2.production;

import cn.copper.phase2.common.BusinessException;
import cn.copper.phase2.common.ErrorCode;
import cn.copper.phase2.common.IdempotencyService;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Date;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 按车间实际日的录全状态与确认（S2-R18/R19）。
 * GET 无写入：无日根行时返回初始版本 0 与未确认状态。
 */
@Service
public class DayStatusService {

    private final JdbcTemplate jdbc;
    private final ProductionRepository repo;
    private final IdempotencyService idempotency;

    public DayStatusService(JdbcTemplate jdbc, ProductionRepository repo,
                            IdempotencyService idempotency) {
        this.jdbc = jdbc;
        this.repo = repo;
        this.idempotency = idempotency;
    }

    // ---------------------------------------------------------------- R18

    public Map<String, Object> dayStatus(long workshopId, String actualDateParam) {
        LocalDate actualDate = ProductionTypes.parseDate("actualDate", actualDateParam, true);
        ProductionRepository.DayStatusRow status = repo.findDayStatus(workshopId, actualDate);
        BigDecimal knownQualified = repo.workshopDayQualifiedTon(workshopId, actualDate);

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("workshopId", ProductionTypes.id(workshopId));
        data.put("actualDate", actualDate.toString());
        data.put("version", status == null ? 0L : status.version());
        data.put("sourceRevision", status == null ? 0L : status.sourceRevision());
        data.put("confirmedSourceRevision", status == null || status.confirmedSourceRevision() == null
                ? null : status.confirmedSourceRevision());
        data.put("recordsComplete", status != null && status.recordsComplete());
        data.put("confirmedBy", status == null ? null : status.confirmedBy());
        data.put("confirmedAt", ProductionTypes.time(status == null ? null : status.confirmedAt()));
        data.put("knownQualifiedQuantityTon", ProductionTypes.ton(knownQualified));
        data.put("blockingItems", blockingItems(workshopId, actualDate));
        data.put("missingItems", costMissingItems(workshopId, actualDate));
        return data;
    }

    /** 影响产量分母的阻塞项：已发生执行/投料/交接但缺称重且无零确认。 */
    private List<Map<String, Object>> blockingItems(long workshopId, LocalDate actualDate) {
        List<Map<String, Object>> items = new ArrayList<>();
        for (ProductionRepository.PendingWeighingTask pending : repo.dayPendingWeighings(workshopId,
                actualDate)) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("code", "WEIGHING_PENDING");
            item.put("taskId", ProductionTypes.id(pending.taskId()));
            item.put("message", "任务 " + pending.taskId() + " 当日已发生实际但尚缺合格称重，也未确认零产出。");
            items.add(item);
        }
        boolean hasFact = hasAnyFact(workshopId, actualDate);
        boolean hasZero = repo.workshopDayHasValidZero(workshopId, actualDate, null);
        boolean hasWeighing = repo.workshopDayHasPositiveOutput(workshopId, actualDate);
        if (!hasFact && !hasZero) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("code", "NO_EVIDENCE");
            item.put("message", "当日尚无任何生产事实，也没有整日零产出确认，无法声明录全。");
            items.add(item);
        }
        if (hasFact && !hasWeighing && !hasZero) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("code", "ZERO_CONFIRMATION_MISSING");
            item.put("message", "当日已有事实但无合格称重，请先确认对应范围的零产出。");
            items.add(item);
        }
        return items;
    }

    private boolean hasAnyFact(long workshopId, LocalDate actualDate) {
        Long outputs = jdbc.queryForObject("""
                SELECT COUNT(*) FROM p2_output_record
                WHERE workshop_id = ? AND actual_date = ? AND voided_at IS NULL
                """, Long.class, workshopId, Date.valueOf(actualDate));
        if (outputs != null && outputs > 0) {
            return true;
        }
        Long inputs = jdbc.queryForObject("""
                SELECT COUNT(*) FROM p2_plate_input i
                LEFT JOIN p2_plan_task t ON t.task_id = i.task_id
                LEFT JOIN p2_production_group g ON g.production_group_id = i.production_group_id
                WHERE COALESCE(t.workshop_id, g.workshop_id) = ? AND i.actual_date = ?
                """, Long.class, workshopId, Date.valueOf(actualDate));
        if (inputs != null && inputs > 0) {
            return true;
        }
        Long handovers = jdbc.queryForObject("""
                SELECT COUNT(*) FROM p2_rod_handover h
                LEFT JOIN p2_plan_task t ON t.task_id = h.task_id
                LEFT JOIN p2_production_group g ON g.production_group_id = h.production_group_id
                WHERE COALESCE(t.workshop_id, g.workshop_id) = ? AND DATE(h.sent_at) = ?
                """, Long.class, workshopId, Date.valueOf(actualDate));
        return handovers != null && handovers > 0;
    }

    /** 成本类缺项不阻塞录全，仅提示。 */
    private List<Map<String, Object>> costMissingItems(long workshopId, LocalDate actualDate) {
        List<Map<String, Object>> items = new ArrayList<>();
        Long pendingAmounts = jdbc.queryForObject("""
                SELECT COUNT(*) FROM p2_plate_input i
                LEFT JOIN p2_plan_task t ON t.task_id = i.task_id
                LEFT JOIN p2_production_group g ON g.production_group_id = i.production_group_id
                WHERE COALESCE(t.workshop_id, g.workshop_id) = ? AND i.actual_date = ?
                  AND i.material_amount_yuan IS NULL
                """, Long.class, workshopId, Date.valueOf(actualDate));
        if (pendingAmounts != null && pendingAmounts > 0) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("code", "MATERIAL_AMOUNT_MISSING");
            item.put("message", "材料金额待补，相关成本待核算。");
            items.add(item);
        }
        Map<String, Object> energy = new LinkedHashMap<>();
        energy.put("code", "ENERGY_PRICE_MISSING");
        energy.put("message", "能源价格待补，成本待核算。");
        items.add(energy);
        return items;
    }

    // ---------------------------------------------------------------- R19

    public Map<String, Object> confirmDay(long workshopId, String actualDateParam,
                                          Map<String, Object> body) {
        if (body.get("requestId") == null || String.valueOf(body.get("requestId")).isBlank()) {
            throw BusinessException.invalid("requestId", "请求编号为必填项。");
        }
        return idempotency.execute(String.valueOf(body.get("requestId")),
                "PRODUCTION_DAY_RECORD_COMPLETION", body, Map.class,
                () -> confirmDayTx(workshopId, actualDateParam, body));
    }

    private Map<String, Object> confirmDayTx(long workshopId, String actualDateParam,
                                             Map<String, Object> body) {
        LocalDate actualDate = ProductionTypes.parseDate("actualDate", actualDateParam, true);
        if (actualDate.isAfter(LocalDate.now(ProductionTypes.ZONE))) {
            throw BusinessException.invalid("actualDate", "未来日期不能确认录全。");
        }
        String operatorName = String.valueOf(body.get("operatorName"));
        if (operatorName == null || operatorName.isBlank() || "null".equals(operatorName)) {
            throw BusinessException.invalid("operatorName", "确认经办人为必填项。");
        }
        long expectedVersion = requiredLong(body, "expectedVersion");
        long expectedRevision = requiredLong(body, "expectedSourceRevision");

        // 车间日锁：锁定既有状态行并重新读取事实。
        ProductionRepository.DayStatusRow status = repo.lockDayStatus(workshopId, actualDate);
        if (status == null) {
            repo.insertDayStatus(workshopId, actualDate);
            status = repo.lockDayStatus(workshopId, actualDate);
        }
        if (expectedVersion != status.version() || expectedRevision != status.sourceRevision()) {
            throw BusinessException.versionConflict(expectedVersion, status.version());
        }

        List<Map<String, Object>> blocking = blockingItems(workshopId, actualDate);
        if (!blocking.isEmpty()) {
            throw new BusinessException(ErrorCode.MISSING_REQUIRED_DATA,
                    "当日仍有影响产量分母的事实未核实，不能确认录全。",
                    Map.of("blockingItems", blocking));
        }

        long sourceRevision = status.sourceRevision();
        repo.confirmDay(workshopId, actualDate, sourceRevision, operatorName,
                StockService.now(), status.version());
        ProductionRepository.DayStatusRow after = repo.findDayStatus(workshopId, actualDate);
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("workshopId", ProductionTypes.id(workshopId));
        data.put("actualDate", actualDate.toString());
        data.put("version", after.version());
        data.put("sourceRevision", after.sourceRevision());
        data.put("confirmedSourceRevision", after.confirmedSourceRevision());
        data.put("recordsComplete", after.recordsComplete());
        data.put("confirmedBy", after.confirmedBy());
        data.put("confirmedAt", ProductionTypes.time(after.confirmedAt()));
        data.put("knownQualifiedQuantityTon", ProductionTypes.ton(
                repo.workshopDayQualifiedTon(workshopId, actualDate)));
        data.put("blockingItems", List.of());
        data.put("missingItems", costMissingItems(workshopId, actualDate));
        return data;
    }

    private long requiredLong(Map<String, Object> body, String key) {
        Object value = body.get(key);
        if (value == null) {
            throw BusinessException.invalid(key, "该项为必填的版本号。");
        }
        try {
            return Long.parseLong(String.valueOf(value));
        } catch (NumberFormatException error) {
            throw BusinessException.invalid(key, "版本号必须是非负整数。");
        }
    }

    // 保留精度口径引用，避免误改。

    @SuppressWarnings("unused")
    private static BigDecimal scale3(BigDecimal value) {
        return value.setScale(3, RoundingMode.HALF_UP);
    }
}
