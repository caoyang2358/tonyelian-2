package cn.copper.phase2.costing;

import cn.copper.phase2.common.BusinessException;
import cn.copper.phase2.common.ErrorCode;
import cn.copper.phase2.common.IdempotencyService;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * 核算运行服务（S2-K05 受控补算、S2-K06 运行查询）。
 *
 * <p><b>本版是同步运行</b>：候选结果先在只读阶段算完，再在幂等事务里一次性写入运行记录、
 * 成本流水与订单计费项。这样「算到一半失败」不会留下半个批次——要么整套结果可见，要么
 * 一行都没有。异步工作者、{@code RUNNING} 回收与发布指针要等 {@code p2_cost_publication}
 * 这类 DDL 评审通过后再接。</p>
 *
 * <p>补算<b>只重算成本投影</b>：不再次投料、产出、领用，也不改库存数量与订单事实。
 * 影响范围由来源引用沿真实引用推导，不接受调用方直接指定余额、均价或利润。</p>
 */
@Service
public class CostRunService {
    private static final org.slf4j.Logger LOG = org.slf4j.LoggerFactory.getLogger(CostRunService.class);
    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");

    private final CostRepository repo;
    private final IdempotencyService idempotency;
    private final cn.copper.phase2.energy.EnergyAllocationPublisher allocationPublisher;

    public CostRunService(CostRepository repo, IdempotencyService idempotency,
                          cn.copper.phase2.energy.EnergyAllocationPublisher allocationPublisher) {
        this.repo = repo;
        this.idempotency = idempotency;
        this.allocationPublisher = allocationPublisher;
    }

    public Map<String, Object> recalculate(Map<String, Object> body) {
        String requestId = text(body, "requestId");
        String operator = requiredText(body, "operatorName", "经办人为必填项。");
        String reason = requiredText(body, "reason", "补算必须写明原因，便于追溯这次为什么重算。");
        if (operator.length() > 100) {
            throw BusinessException.invalid("operatorName", "经办人不得超过 100 个字符。");
        }

        ScopeRequest scope = scopeOf(body);
        // 候选结果先算好：它只读事实，失败时不写任何东西。
        Prepared prepared = prepare(scope);

        return idempotency.execute(requestId, "COSTING_RECALCULATION", body, Map.class,
                () -> persist(scope, prepared, operator, reason, body));
    }

    private Map<String, Object> persist(ScopeRequest scope, Prepared prepared, String operator,
                                        String reason, Map<String, Object> body) {
        CostRepository.RunRow previous = repo.latestPublishedRun().orElse(null);
        Map<String, Object> affected = new LinkedHashMap<>();
        affected.put("materialIds", prepared.scope.materialIds());
        affected.put("orderIds", prepared.scope.orderIds());
        affected.put("from", prepared.scope.from() == null ? null : prepared.scope.from().toString());
        affected.put("to", prepared.scope.to() == null ? null : prepared.scope.to().toString());
        affected.put("sourceReferences", scope.sourceReferences());

        Map<String, Object> snapshot = snapshotOf(prepared.inputs, prepared.outcome);

        long runId = repo.insertRun(previous == null ? null : previous.costRunId(), CostModels.RUN_RUNNING,
                CostModels.TRIGGER_MANUAL, affected, snapshot, null, reason, operator);

        // 分摊必须先落库：成本引擎的铜杆、铜丝入库成本都要用它的金额。
        // 分摊行挂在本次运行下，所以顺序只能是「先开运行 → 写分摊 → 再回放成本」。
        LocalDate energyFrom = prepared.scope.from() == null
                ? outputDateBound(prepared.inputs, true) : prepared.scope.from();
        LocalDate energyTo = prepared.scope.to() == null
                ? outputDateBound(prepared.inputs, false) : prepared.scope.to();
        int allocationRows = allocationPublisher.publish(runId, energyFrom, energyTo);
        CostEngine.Outcome outcome = allocationRows == 0 ? prepared.outcome
                : CostEngine.replay(withEnergy(prepared.inputs, repo.energyAllocations(runId)),
                        prepared.allocations);
        if (allocationRows != 0) {
            // 分摊改变了回放结果，快照必须跟着改：否则 K01 读到的还是「分摊前」的缺项，
            // 明明能源已经计入成本，页面却仍显示 WORKSHOP1_ENERGY_MISSING。
            repo.updateInputSnapshot(runId, snapshotOf(prepared.inputs, outcome));
        }

        Map<Long, Long> ledgerByMovement = new LinkedHashMap<>();
        for (CostEngine.MaterialResult material : outcome.materials()) {
            for (CostEngine.LedgerDraft draft : material.ledger()) {
                long ledgerId = repo.insertLedger(runId, draft);
                if (draft.movementId() != null) {
                    ledgerByMovement.put(draft.movementId(), ledgerId);
                }
            }
        }
        for (CostEngine.OrderDraft draft : outcome.orders()) {
            Long ledgerId = draft.movementId() == null ? null : ledgerByMovement.get(draft.movementId());
            try {
                repo.insertOrderItem(runId, draft, ledgerId);
            } catch (org.springframework.dao.DataAccessException rejected) {
                // 表上有 CHECK：领用项必须有成本流水、能源项必须有分摊行。写不进去时
                // 必须说清是哪一类、引用了什么，否则只看到一句「数据库不可用」。
                LOG.warn("写入订单计费项被拒绝：itemType={}, orderId={}, ledgerId={}, allocationId={}, "
                                + "amount={}, sqlState={}, vendorCode={}",
                        draft.itemType(), draft.orderId(), ledgerId, draft.energyAllocationId(),
                        draft.amountYuan(), sqlState(rejected), vendorCode(rejected));
                throw new IllegalStateException("写入订单计费项被数据库拒绝：" + draft.itemType(), rejected);
            }
        }

        List<String> missing = new ArrayList<>(outcome.missingItems());
        Set<Long> uncovered = repo.ordersWithUncoveredDirectOutput(runId);
        if (!uncovered.isEmpty()) {
            missing.add(CostModels.CODE_DIRECT_OUTPUT_COST_NOT_COVERED);
        }
        boolean revisionRequested = body.get("expectedSourceRevision") != null
                || body.get("expectedPublishedCostRunId") != null;
        if (revisionRequested) {
            missing.add(CostModels.CODE_SOURCE_REVISION_NOT_TRACKED);
        }
        String status = outcome.complete() && missing.isEmpty()
                ? CostModels.RUN_COMPLETED : CostModels.RUN_INCOMPLETE;
        repo.finishRun(runId, status, missing);

        applyAccountingStates(outcome.orders(), uncovered);

        Map<String, Object> receipt = new LinkedHashMap<>();
        receipt.put("costRunId", runId);
        receipt.put("previousCostRunId", previous == null ? null : previous.costRunId());
        receipt.put("status", status);
        receipt.put("ruleVersion", CostModels.RULE_VERSION);
        receipt.put("affectedScope", affected);
        receipt.put("missingItems", missing.stream().map(CostModels::missing).toList());
        receipt.put("ledgerRows", outcome.materials().stream()
                .mapToInt(material -> material.ledger().size()).sum());
        receipt.put("orderItemRows", outcome.orders().size());
        receipt.put("energyAllocationRows", allocationRows);
        receipt.put("sourceRevisionChecked", !revisionRequested ? null : Boolean.FALSE);
        receipt.put("note", revisionRequested
                ? "本版没有来源修订号，无法核对「计算期间来源是否变化」；结果按读取时刻的事实计算，已在缺项中标明。"
                : "同步补算：结果已提交，可直接查询。");
        return receipt;
    }

    /**
     * 订单核算状态只随成本结果更新，不动生产与交付事实。
     * <p>还有缺项的批次记 {@code INCOMPLETE}；不能因为「跑完了」就写 {@code COMPLETED}。</p>
     * <p>另外：成品直接归属订单但原料成本没有计费项写入方时，本运行只归集了能源费，
     * 同样必须记 {@code INCOMPLETE}（接口清单 4.4），否则会拿已知部分冒充完整成本。</p>
     */
    private void applyAccountingStates(List<CostEngine.OrderDraft> drafts, Set<Long> uncovered) {
        Map<Long, Boolean> completeByOrder = new LinkedHashMap<>();
        for (CostEngine.OrderDraft draft : drafts) {
            boolean complete = draft.missingItems().isEmpty() && draft.amountYuan() != null;
            completeByOrder.merge(draft.orderId(), complete, (left, right) -> left && right);
        }
        for (Long orderId : uncovered) {
            completeByOrder.put(orderId, false);
        }
        completeByOrder.forEach((orderId, complete) ->
                repo.updateAccountingState(orderId, complete ? "COMPLETED" : "INCOMPLETE"));
    }

    public Map<String, Object> run(long costRunId) {
        CostRepository.RunRow row = repo.run(costRunId)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "核算运行不存在。"));
        CostRepository.RunSummary summary = repo.runSummary(costRunId);
        Optional<Long> next = repo.nextRun(costRunId);

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("costRunId", row.costRunId());
        data.put("previousCostRunId", row.previousCostRunId());
        data.put("nextCostRunId", next.orElse(null));
        data.put("status", row.status());
        data.put("retryable", CostModels.RUN_FAILED.equals(row.status())
                || CostModels.RUN_INCOMPLETE.equals(row.status()));
        data.put("triggerType", row.triggerType());
        data.put("ruleVersion", row.ruleVersion());
        data.put("affectedScope", parseJson(row.affectedScope()));
        data.put("inputSnapshot", parseJson(row.inputSnapshot()));
        data.put("missingItems", missingItems(row.missingItems()));
        data.put("reason", row.reason());
        data.put("operatorName", row.operatorName());
        data.put("requestedAt", row.requestedAt());
        data.put("startedAt", row.startedAt());
        data.put("completedAt", row.completedAt());
        data.put("version", row.version());
        Map<String, Object> resultBlock = new LinkedHashMap<>();
        resultBlock.put("ledgerRows", summary.ledgerRows());
        resultBlock.put("costDeltaYuan", CostModels.money(summary.costDeltaYuan()));
        resultBlock.put("missingRows", summary.missingRows());
        resultBlock.put("materialCount", summary.materialCount());
        resultBlock.put("orderItemRows", summary.orderItemCount());
        resultBlock.put("orderAmountYuan", CostModels.money(summary.orderAmountYuan()));
        data.put("result", resultBlock);
        return data;
    }

    // ---------------------------------------------------------------- 只读准备

    /** 从 JDBC 异常里取 SQLState；连接类异常没有，返回 null。只取码，不取 SQL 文本。 */
    private static String sqlState(org.springframework.dao.DataAccessException error) {
        Throwable cause = error.getMostSpecificCause();
        return cause instanceof java.sql.SQLException sql && sql.getSQLState() != null
                ? sql.getSQLState() : null;
    }

    private static Integer vendorCode(org.springframework.dao.DataAccessException error) {
        Throwable cause = error.getMostSpecificCause();
        return cause instanceof java.sql.SQLException sql ? sql.getErrorCode() : null;
    }

    /** 本次回放涉及产出的最早或最晚实际日；没有产出时返回 null，由分摊发布端跳过。 */
    private static LocalDate outputDateBound(CostEngine.Inputs inputs, boolean earliest) {
        LocalDate bound = null;
        for (CostEngine.OutputFact output : inputs.outputs()) {
            LocalDate date = output.actualDate();
            if (date == null) {
                continue;
            }
            if (bound == null || (earliest ? date.isBefore(bound) : date.isAfter(bound))) {
                bound = date;
            }
        }
        return bound;
    }

    private static CostEngine.Inputs withEnergy(CostEngine.Inputs inputs, List<CostEngine.EnergyFact> energy) {
        return new CostEngine.Inputs(inputs.materials(), inputs.movements(), inputs.outputs(),
                inputs.plateInputs(), inputs.handovers(), energy);
    }

    private record Prepared(CostRepository.Scope scope, CostEngine.Inputs inputs, CostEngine.Outcome outcome,
                            List<CostEngine.FinishedAllocationFact> allocations) {
    }

    /** 调用方给的补算范围；来源引用只用于收窄范围，不用于指定金额。 */
    private record ScopeRequest(List<Long> materialIds, List<Long> orderIds, LocalDate from, LocalDate to,
                                List<Map<String, Object>> sourceReferences) {
    }

    /**
     * 运行快照。分摊落库前后各算一次，保证快照描述的是「最终结果」而不是「分摊前的候选」。
     *
     * <p>每个物料的期末数量与完整性：K01 用它核对成本视图的实物量与余额账是否同源同值。
     * 两边不一致时不给出均价，而不是让均价掩盖数量差异。</p>
     */
    private static Map<String, Object> snapshotOf(CostEngine.Inputs inputs, CostEngine.Outcome outcome) {
        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("ruleVersion", CostModels.RULE_VERSION);
        snapshot.put("movementCount", inputs.movements().size());
        snapshot.put("outputCount", inputs.outputs().size());
        snapshot.put("plateInputCount", inputs.plateInputs().size());
        snapshot.put("handoverCount", inputs.handovers().size());
        snapshot.put("valuationOrder", "business_date, movement_id");
        List<Map<String, Object>> materialSnapshot = new ArrayList<>();
        for (CostEngine.MaterialResult material : outcome.materials()) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("materialId", material.materialId());
            item.put("endQuantityTon", material.result() == null ? null
                    : CostModels.ton(material.result().endQuantityTon()));
            item.put("knownValueYuan", material.result() == null ? null
                    : CostModels.money(material.result().endKnownValueYuan()));
            item.put("complete", material.complete());
            item.put("missingItems", material.missingItems());
            materialSnapshot.add(item);
        }
        snapshot.put("materials", materialSnapshot);
        return snapshot;
    }

    private Prepared prepare(ScopeRequest request) {
        CostRepository.Scope scope = narrow(request);
        List<CostRepository.MovementRow> movementRows = repo.movements(scope);

        List<CostEngine.Movement> movements = movementRows.stream()
                .map(row -> new CostEngine.Movement(row.movementId(), row.materialId(), row.orderId(),
                        row.taskId(), row.movementType(), row.sourceType(), row.sourceId(),
                        row.businessDate(), row.quantityDelta(), row.correctsMovementId(),
                        row.costAmountYuan()))
                .toList();

        Set<Long> movementIds = new LinkedHashSet<>();
        movements.forEach(movement -> movementIds.add(movement.movementId()));
        List<CostRepository.OutputRow> outputRows = repo.outputs(movementIds);

        Set<Long> taskIds = new LinkedHashSet<>();
        Set<String> groupKeys = new LinkedHashSet<>();
        for (CostRepository.OutputRow output : outputRows) {
            if (output.taskId() != null) {
                taskIds.add(output.taskId());
            }
            groupKeys.add(output.groupKey());
        }
        List<CostRepository.PlateInputRow> plateRows = repo.plateInputs(taskIds, groupKeys);

        Set<Long> handoverIds = new LinkedHashSet<>();
        outputRows.forEach(output -> output.rodRefs().forEach(ref -> handoverIds.add(ref.handoverId())));
        List<CostRepository.HandoverRow> handoverRows = repo.handovers(handoverIds);

        List<CostEngine.Material> materials = repo.materials().stream()
                .map(row -> new CostEngine.Material(row.materialId(), row.code(), row.name(), row.type(),
                        row.specification()))
                .toList();

        CostEngine.Inputs inputs = new CostEngine.Inputs(materials, movements,
                outputRows.stream().map(row -> new CostEngine.OutputFact(row.outputId(), row.materialId(),
                                row.taskId(), row.groupKey(), row.actualDate(), row.qualifiedTon(),
                                row.recordType(), row.movementId(), row.rodRefs()))
                        .toList(),
                plateRows.stream().map(row -> new CostEngine.PlateInputFact(row.taskId(), row.groupKey(),
                        row.actualDate(), row.amountYuan())).toList(),
                handoverRows.stream().map(row -> new CostEngine.HandoverFact(row.handoverId(),
                        row.movementId(), row.sentQuantityTon())).toList(),
                List.of());

        Set<Long> outputIds = new LinkedHashSet<>();
        outputRows.forEach(output -> outputIds.add(output.outputId()));
        List<CostEngine.FinishedAllocationFact> allocations = repo.directOutputAllocations(outputIds).stream()
                .map(row -> new CostEngine.FinishedAllocationFact(row.allocationId(), row.orderId(),
                        row.materialId(), row.outputId(), row.effectiveTon()))
                .toList();

        return new Prepared(scope, inputs, CostEngine.replay(inputs, allocations), allocations);
    }

    /**
     * 范围收窄：显式范围优先；只给来源引用时，沿 {@code p2_stock_movement} 反查物料与日期，
     * 让「补算这一笔投料」不至于重算整库。
     */
    private CostRepository.Scope narrow(ScopeRequest request) {
        if (!request.materialIds().isEmpty() || request.from() != null || request.to() != null) {
            return new CostRepository.Scope(request.materialIds(), request.orderIds(),
                    request.from(), request.to());
        }
        if (request.sourceReferences().isEmpty()) {
            return new CostRepository.Scope(List.of(), request.orderIds(), null, null);
        }
        List<Long> movementIds = new ArrayList<>();
        for (Map<String, Object> reference : request.sourceReferences()) {
            String type = String.valueOf(reference.get("sourceType"));
            Object id = reference.get("sourceId");
            if (("STOCK_MOVEMENT".equalsIgnoreCase(type) || "MOVEMENT".equalsIgnoreCase(type)) && id != null) {
                movementIds.add(Long.parseLong(String.valueOf(id)));
            }
        }
        if (movementIds.isEmpty()) {
            return CostRepository.Scope.all();
        }
        List<CostRepository.MovementRow> rows = repo.movementsByIds(movementIds);
        if (rows.isEmpty()) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "来源引用指向的库存流水不存在。");
        }
        Set<Long> materialIds = new LinkedHashSet<>();
        LocalDate from = null;
        LocalDate to = null;
        for (CostRepository.MovementRow row : rows) {
            materialIds.add(row.materialId());
            from = from == null || row.businessDate().isBefore(from) ? row.businessDate() : from;
            to = to == null || row.businessDate().isAfter(to) ? row.businessDate() : to;
        }
        return new CostRepository.Scope(List.copyOf(materialIds), request.orderIds(), from, to);
    }

    private ScopeRequest scopeOf(Map<String, Object> body) {
        List<Long> materialIds = List.of();
        List<Long> orderIds = List.of();
        LocalDate from = null;
        LocalDate to = null;
        if (body.get("requestedScope") instanceof Map<?, ?> scope) {
            materialIds = ids(scope, "materialIds");
            orderIds = ids(scope, "orderIds");
            from = date(scope, "from");
            to = date(scope, "to");
            if (from != null && to != null && from.isAfter(to)) {
                throw BusinessException.invalid("requestedScope", "起始日期不能晚于结束日期。");
            }
        }
        List<Map<String, Object>> references = new ArrayList<>();
        if (body.get("sourceReferences") instanceof List<?> list) {
            for (Object raw : list) {
                if (raw instanceof Map<?, ?> map) {
                    Map<String, Object> reference = new LinkedHashMap<>();
                    map.forEach((key, value) -> reference.put(String.valueOf(key), value));
                    references.add(reference);
                }
            }
        }
        return new ScopeRequest(materialIds, orderIds, from, to, references);
    }

    // ---------------------------------------------------------------- 解析工具

    private static String text(Map<String, Object> body, String key) {
        Object value = body.get(key);
        return value == null ? null : String.valueOf(value);
    }

    private static String requiredText(Map<String, Object> body, String key, String message) {
        String value = text(body, key);
        if (value == null || value.isBlank()) {
            throw BusinessException.invalid(key, message);
        }
        return value.trim();
    }

    private static List<Long> ids(Map<?, ?> source, String key) {
        if (!(source.get(key) instanceof List<?> list)) {
            return List.of();
        }
        List<Long> ids = new ArrayList<>();
        for (Object raw : list) {
            try {
                ids.add(Long.parseLong(String.valueOf(raw)));
            } catch (NumberFormatException error) {
                throw BusinessException.invalid(key, "编号必须是整数：" + raw);
            }
        }
        return List.copyOf(ids);
    }

    private static LocalDate date(Map<?, ?> source, String key) {
        Object value = source.get(key);
        if (value == null || String.valueOf(value).isBlank()) {
            return null;
        }
        try {
            return LocalDate.parse(String.valueOf(value));
        } catch (RuntimeException error) {
            throw BusinessException.invalid(key, "日期格式应为 YYYY-MM-DD：" + value);
        }
    }

    private static Object parseJson(String json) {
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            return cn.copper.phase2.common.Json.MAPPER.readValue(json, Object.class);
        } catch (Exception unreadable) {
            return null;
        }
    }

    private static List<Map<String, Object>> missingItems(String json) {
        Object parsed = parseJson(json);
        if (!(parsed instanceof List<?> list)) {
            return List.of();
        }
        List<Map<String, Object>> items = new ArrayList<>();
        for (Object raw : list) {
            items.add(CostModels.missing(String.valueOf(raw)));
        }
        return items;
    }

    private static String toJson(Object value) {
        try {
            return cn.copper.phase2.common.Json.MAPPER.writeValueAsString(value);
        } catch (Exception failure) {
            throw new IllegalStateException("结果无法序列化：" + failure.getMessage(), failure);
        }
    }
}
