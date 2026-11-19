package cn.copper.phase2.costing;

import cn.copper.phase2.common.BusinessException;
import cn.copper.phase2.common.ErrorCode;
import cn.copper.phase2.common.Json;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 成本查询服务（S2-K01 物料成本视图、K02 成本流水、K03 订单核算列表、K04 订单核算依据）。
 *
 * <p>四个接口都只读。当前结果取「最近一次已提交结果」的核算运行；执行中与失败的候选
 * 都不能当作当前结果。资料不齐时金额返回 {@code null} 并在缺项里说明，不拿已知部分
 * 冒充完整成本，也不把「跑完了」当成「算清了」。</p>
 */
@Service
public class CostQueryService {
    private static final int DEFAULT_PAGE_SIZE = 20;
    private static final int MAX_PAGE_SIZE = 200;

    private final CostRepository repo;

    public CostQueryService(CostRepository repo) {
        this.repo = repo;
    }

    // ---------------------------------------------------------------- K01

    public Map<String, Object> stocks(Long materialId, String materialType, Integer page, Integer pageSize) {
        int pageValue = page == null || page < 1 ? 1 : page;
        int size = pageSize == null || pageSize < 1 ? DEFAULT_PAGE_SIZE
                : Math.min(pageSize, MAX_PAGE_SIZE);

        CostRepository.RunRow run = repo.latestPublishedRun().orElse(null);
        Map<Long, BigDecimal> reservations = repo.effectiveReservations();
        Map<Long, CostRepository.BalanceRow> balances = new LinkedHashMap<>();
        repo.balances().forEach(row -> balances.put(row.materialId(), row));
        Map<Long, CostRepository.MaterialValue> values = run == null
                ? Map.of() : repo.materialValues(run.costRunId());
        Map<Long, Map<String, Object>> snapshot = run == null
                ? Map.of() : snapshotByMaterial(run.inputSnapshot());

        List<Map<String, Object>> items = new ArrayList<>();
        int matched = 0;
        for (CostRepository.MaterialRow material : repo.materials()) {
            if (materialId != null && material.materialId() != materialId) {
                continue;
            }
            if (materialType != null && !materialType.isBlank()
                    && !materialType.equalsIgnoreCase(material.type())) {
                continue;
            }
            matched++;
            if (matched <= (pageValue - 1) * size || items.size() >= size) {
                continue;
            }
            items.add(stockItem(material, balances.get(material.materialId()), reservations,
                    values.get(material.materialId()), snapshot.get(material.materialId()), run));
        }

        Map<String, Object> envelope = new LinkedHashMap<>();
        envelope.put("items", items);
        envelope.put("page", pageValue);
        envelope.put("pageSize", size);
        envelope.put("total", matched);
        envelope.put("costRun", runSummary(run));
        envelope.put("unitCostBasis", "MOVING_WEIGHTED_AVERAGE");
        envelope.put("missingItems", run == null
                ? List.of(CostModels.missing(CODE_COST_RUN_MISSING_TEXT))
                : List.of());
        return envelope;
    }

    private static final String CODE_COST_RUN_MISSING_TEXT = "COST_RUN_MISSING";

    private Map<String, Object> stockItem(CostRepository.MaterialRow material,
                                          CostRepository.BalanceRow balance,
                                          Map<Long, BigDecimal> reservations,
                                          CostRepository.MaterialValue value,
                                          Map<String, Object> snapshot,
                                          CostRepository.RunRow run) {
        BigDecimal physical = balance == null
                ? BigDecimal.ZERO.setScale(CostModels.TON_SCALE) : balance.physicalQuantityTon();
        BigDecimal reserved = reservations.getOrDefault(material.materialId(),
                BigDecimal.ZERO.setScale(CostModels.TON_SCALE));

        List<String> missing = new ArrayList<>();
        if (run == null) {
            missing.add(CODE_COST_RUN_MISSING_TEXT);
        }
        // 把该物料在本次运行里的缺项带出来：否则页面只看到「完整总额为 null」，
        // 却不知道为什么不可得。
        if (snapshot != null && snapshot.get("missingItems") instanceof List<?> codes) {
            for (Object code : codes) {
                missing.add(String.valueOf(code));
            }
        }
        BigDecimal knownValue = value == null
                ? BigDecimal.ZERO.setScale(CostModels.AMOUNT_SCALE) : value.valueYuan();
        // 完整性看两处：成本流水里这一物料有没有缺项行，以及本次运行快照里该物料是否算完整。
        // 只看流水会把「快照判定不完整」的物料当成完整；只看快照则会和修正后的流水打架。
        boolean snapshotComplete = snapshot == null || !Boolean.FALSE.equals(snapshot.get("complete"));
        boolean complete = run != null && value != null && value.missingRows() == 0 && snapshotComplete;

        // 成本视图的实物量必须与余额账同源同值。不一致说明期间有待处理的数量事实，
        // 这时给均价只会把「数量差」藏进「单价」里。
        if (snapshot != null && snapshot.get("endQuantityTon") != null) {
            BigDecimal replayEnd = new BigDecimal(String.valueOf(snapshot.get("endQuantityTon")));
            if (replayEnd.compareTo(physical) != 0) {
                complete = false;
                missing.add("QUANTITY_MISMATCH");
            }
        }

        BigDecimal totalValue = complete ? knownValue : null;
        BigDecimal unitCost = complete && physical.signum() > 0
                ? knownValue.divide(physical, CostModels.UNIT_COST_SCALE, RoundingMode.HALF_UP)
                : null;

        Map<String, Object> item = new LinkedHashMap<>();
        item.put("materialId", material.materialId());
        item.put("materialCode", material.code());
        item.put("materialName", material.name());
        item.put("materialType", material.type());
        item.put("specification", material.specification());
        item.put("physicalQuantityTon", CostModels.ton(physical));
        item.put("reservedQuantityTon", CostModels.ton(reserved));
        item.put("availableQuantityTon", CostModels.ton(physical.subtract(reserved)));
        item.put("knownValueYuan", CostModels.money(knownValue));
        item.put("totalValueYuan", CostModels.money(totalValue));
        item.put("unitCostYuanPerTon", CostModels.unit(totalValue == null ? null : unitCost));
        item.put("knownOnly", !complete);
        item.put("costRunId", run == null ? null : run.costRunId());
        item.put("balanceVersion", balance == null ? 0L : balance.version());

        // 公共成品自由池要按规格单独记账（V6 没有这张表）。没有它就只能说「不可得」，
        // 不能把整个物料的均价当成公共池均价。
        if ("FINISHED_WIRE".equals(material.type())) {
            item.put("freePublicQuantityTon", null);
            item.put("freePublicValueYuan", null);
            item.put("freePublicUnitCostYuanPerTon", null);
            missing.add(CostModels.CODE_PUBLIC_POOL_NOT_TRACKED);
        } else {
            item.put("freePublicQuantityTon", CostModels.ton(physical.subtract(reserved)));
            item.put("freePublicValueYuan", CostModels.money(totalValue));
            item.put("freePublicUnitCostYuanPerTon", CostModels.unit(totalValue == null ? null : unitCost));
        }
        item.put("missingItems", missing.stream().distinct().map(CostModels::missing).toList());
        return item;
    }

    // ---------------------------------------------------------------- K02

    public Map<String, Object> stockLedger(Long costRunId, Long materialId, Long orderId,
                                           String entryType, String from, String to,
                                           Integer page, Integer pageSize) {
        int pageValue = page == null || page < 1 ? 1 : page;
        int size = pageSize == null || pageSize < 1 ? DEFAULT_PAGE_SIZE
                : Math.min(pageSize, MAX_PAGE_SIZE);
        Long runId = costRunId;
        if (runId == null) {
            runId = repo.latestPublishedRun().map(CostRepository.RunRow::costRunId).orElse(null);
        }
        if (runId == null) {
            Map<String, Object> empty = new LinkedHashMap<>();
            empty.put("items", List.of());
            empty.put("page", pageValue);
            empty.put("pageSize", size);
            empty.put("total", 0);
            empty.put("costRunId", null);
            empty.put("missingItems", List.of(CostModels.missing(CODE_COST_RUN_MISSING_TEXT)));
            return empty;
        }

        LocalDate fromDate = parseDate("from", from);
        LocalDate toDate = parseDate("to", to);
        List<CostRepository.LedgerRow> rows = repo.ledger(runId, materialId, orderId, entryType,
                fromDate, toDate, size, (pageValue - 1) * size);
        long total = repo.countLedger(runId, materialId, orderId, entryType, fromDate, toDate);

        List<Map<String, Object>> items = new ArrayList<>(rows.size());
        for (CostRepository.LedgerRow row : rows) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("stockCostLedgerId", row.stockCostLedgerId());
            item.put("costRunId", row.costRunId());
            item.put("materialId", row.materialId());
            item.put("orderId", row.orderId());
            item.put("movementId", row.movementId());
            item.put("entryType", row.entryType());
            item.put("sourceType", row.sourceType());
            item.put("sourceId", row.sourceId());
            item.put("shareKey", row.shareKey());
            item.put("businessDate", row.businessDate().toString());
            item.put("quantityBasisTon", CostModels.ton(row.quantityBasisTon()));
            item.put("unitCostYuanPerTon", CostModels.unit(row.unitCostYuanPerTon()));
            item.put("costDeltaYuan", CostModels.money(row.costDeltaYuan()));
            List<String> missing = missingCodes(row.missingItems());
            item.put("missingItems", missing.stream().map(CostModels::missing).toList());
            item.put("missingReason", missing.isEmpty() ? null
                    : "本行金额只包含已知部分，完整金额待缺项补齐后重算。");
            items.add(item);
        }

        Map<String, Object> envelope = new LinkedHashMap<>();
        envelope.put("items", items);
        envelope.put("page", pageValue);
        envelope.put("pageSize", size);
        envelope.put("total", total);
        envelope.put("costRunId", runId);
        return envelope;
    }

    // ---------------------------------------------------------------- K03

    public Map<String, Object> orders(Long orderId, String accountingState, Integer page, Integer pageSize) {
        int pageValue = page == null || page < 1 ? 1 : page;
        int size = pageSize == null || pageSize < 1 ? DEFAULT_PAGE_SIZE
                : Math.min(pageSize, MAX_PAGE_SIZE);

        CostRepository.RunRow run = repo.latestPublishedRun().orElse(null);
        Map<Long, CostRepository.OrderCostTotal> totals = new LinkedHashMap<>();
        if (run != null) {
            repo.orderCostTotals(run.costRunId()).forEach(total -> totals.put(total.orderId(), total));
        }
        List<CostRepository.OrderRow> rows = repo.orders(orderId, accountingState, size,
                (pageValue - 1) * size);
        long total = repo.countOrders(orderId, accountingState);

        List<Map<String, Object>> items = new ArrayList<>(rows.size());
        for (CostRepository.OrderRow row : rows) {
            items.add(orderItem(row, totals.get(row.orderId()), run));
        }
        Map<String, Object> envelope = new LinkedHashMap<>();
        envelope.put("items", items);
        envelope.put("page", pageValue);
        envelope.put("pageSize", size);
        envelope.put("total", total);
        envelope.put("costRun", runSummary(run));
        envelope.put("calculationScope", "MATERIAL_AND_ENERGY_ONLY");
        return envelope;
    }

    private Map<String, Object> orderItem(CostRepository.OrderRow order,
                                          CostRepository.OrderCostTotal total,
                                          CostRepository.RunRow run) {
        BigDecimal amount = order.agreedAmountYuan() != null
                ? order.agreedAmountYuan() : order.contractAmountYuan();
        BigDecimal knownCost = total == null ? null : total.amountYuan();
        boolean amountReady = order.amountConfirmed() && order.agreedAmountYuan() != null;
        boolean cancelled = order.cancelledAt() != null;
        boolean costReady = total != null && total.missingCount() == 0;
        BigDecimal totalCost = costReady ? total.amountYuan() : null;

        String blockedReason;
        if (cancelled) {
            blockedReason = "CANCELLED";
        } else if (run == null) {
            blockedReason = "COST_RUN_MISSING";
        } else if (!amountReady) {
            blockedReason = "AMOUNT_UNCONFIRMED";
        } else if (!costReady) {
            blockedReason = "COST_INCOMPLETE";
        } else if (!productionEnded(order.productionResult())) {
            blockedReason = "PRODUCTION_NOT_COMPLETED";
        } else {
            blockedReason = null;
        }
        boolean displayable = blockedReason == null;
        BigDecimal profit = displayable ? amount.subtract(totalCost) : null;

        Map<String, Object> item = new LinkedHashMap<>();
        item.put("orderId", order.orderId());
        item.put("orderNo", order.orderNo());
        item.put("customerName", order.customerName());
        item.put("wireMaterialId", order.wireMaterialId());
        item.put("requiredQuantityTon", CostModels.ton(order.requiredQuantityTon()));
        item.put("dueDate", order.dueDate().toString());
        item.put("productionResult", order.productionResult());
        item.put("deliveryResult", order.deliveryResult());
        item.put("accountingState", order.accountingState());
        item.put("contractAmountYuan", CostModels.money(order.contractAmountYuan()));
        item.put("agreedAmountYuan", CostModels.money(order.agreedAmountYuan()));
        item.put("amountConfirmed", order.amountConfirmed());
        item.put("applicableAmountYuan", CostModels.money(amount));
        item.put("knownCostAmountYuan", CostModels.money(knownCost));
        item.put("totalCostAmountYuan", CostModels.money(totalCost));
        item.put("estimatedProfitYuan", CostModels.money(profit));
        item.put("profitDisplayable", displayable);
        item.put("profitBlockedReason", blockedReason);
        item.put("costItemCount", total == null ? 0 : total.itemCount());
        item.put("costRunId", run == null ? null : run.costRunId());
        item.put("version", order.version());
        item.put("missingItems", displayable ? List.of()
                : List.of(CostModels.missing(blockedReason == null ? "UNKNOWN" : blockedReason)));
        return item;
    }

    /**
     * 生产是否已结束。S2-R16 写入的是 {@code NORMAL_COMPLETED} / {@code SHORTFALL_COMPLETED}
     * （不足量结束也是结束），这里原先只认 {@code COMPLETED}，导致所有正常结束的订单都被判为
     * 「生产尚未结束」、利润永远不出现——按结果码集合判断，不按某一个字面量。
     */
    private static boolean productionEnded(String productionResult) {
        return switch (productionResult == null ? "" : productionResult) {
            case "NORMAL_COMPLETED", "SHORTFALL_COMPLETED", "NO_PRODUCTION", "COMPLETED" -> true;
            default -> false;
        };
    }

    // ---------------------------------------------------------------- K04

    public Map<String, Object> orderDetail(long orderId) {
        CostRepository.OrderRow order = repo.order(orderId)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "订单不存在。"));
        CostRepository.RunRow run = repo.latestPublishedRun().orElse(null);

        List<Map<String, Object>> items = new ArrayList<>();
        LinkedHashSet<String> missing = new LinkedHashSet<>();
        BigDecimal known = null;
        if (run != null) {
            List<CostRepository.OrderItemRow> rows = repo.orderItems(run.costRunId(), orderId);
            for (CostRepository.OrderItemRow row : rows) {
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("orderCostItemId", row.orderCostItemId());
                item.put("itemType", row.itemType());
                item.put("sourceKey", row.sourceKey());
                item.put("businessDate", row.businessDate().toString());
                item.put("amountYuan", CostModels.money(row.amountYuan()));
                item.put("stockCostLedgerId", row.stockCostLedgerId());
                item.put("energyAllocationId", row.energyAllocationId());
                List<String> rowMissing = missingCodes(row.missingItems());
                item.put("missingItems", rowMissing.stream().map(CostModels::missing).toList());
                items.add(item);
                if (row.amountYuan() != null) {
                    known = known == null ? row.amountYuan() : known.add(row.amountYuan());
                }
                missing.addAll(rowMissing);
            }
        }

        BigDecimal amount = order.agreedAmountYuan() != null
                ? order.agreedAmountYuan() : order.contractAmountYuan();
        boolean cancelled = order.cancelledAt() != null;
        BigDecimal totalCost = missing.isEmpty() && run != null ? known : null;

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("orderId", order.orderId());
        data.put("orderNo", order.orderNo());
        data.put("customerName", order.customerName());
        data.put("wireMaterialId", order.wireMaterialId());
        data.put("requiredQuantityTon", CostModels.ton(order.requiredQuantityTon()));
        data.put("dueDate", order.dueDate().toString());
        data.put("productionResult", order.productionResult());
        data.put("deliveryResult", order.deliveryResult());
        data.put("accountingState", order.accountingState());
        data.put("cancelled", cancelled);
        // 这里必须用允许 null 的映射：约定金额未确认、完整成本缺项时，这些字段本来就该是 null，
        // 用 Map.of 会直接抛 NPE，把「如实返回缺项」变成「接口报错」。
        Map<String, Object> amountBlock = new LinkedHashMap<>();
        amountBlock.put("contractAmountYuan", CostModels.money(order.contractAmountYuan()));
        amountBlock.put("agreedAmountYuan", CostModels.money(order.agreedAmountYuan()));
        amountBlock.put("confirmed", order.amountConfirmed());
        amountBlock.put("applicableAmountYuan", CostModels.money(amount));
        data.put("amount", amountBlock);
        Map<String, Object> costBlock = new LinkedHashMap<>();
        costBlock.put("knownCostAmountYuan", CostModels.money(known));
        costBlock.put("totalCostAmountYuan", CostModels.money(totalCost));
        costBlock.put("costRunId", run == null ? null : run.costRunId());
        data.put("cost", costBlock);
        data.put("costItems", items);
        List<String> missingList = new ArrayList<>(missing);
        if (run == null) {
            missingList.add(CODE_COST_RUN_MISSING_TEXT);
        } else if (items.isEmpty()) {
            // 有运行但没有本单的成本项：不是「成本为零」，而是这单还没发生可核算的领料或能源归属。
            missingList.add("ORDER_COST_NOT_COVERED");
        }
        data.put("missingItems", missingList.stream().map(CostModels::missing).toList());
        data.put("recalculationPending", totalCost == null);
        data.put("calculationScope", "MATERIAL_AND_ENERGY_ONLY");
        data.put("version", order.version());
        return data;
    }

    // ---------------------------------------------------------------- 工具

    private Map<String, Object> runSummary(CostRepository.RunRow run) {
        if (run == null) {
            Map<String, Object> empty = new LinkedHashMap<>();
            empty.put("costRunId", null);
            empty.put("status", "NOT_RUN");
            empty.put("ruleVersion", null);
            empty.put("completedAt", null);
            return empty;
        }
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("costRunId", run.costRunId());
        summary.put("status", run.status());
        summary.put("ruleVersion", run.ruleVersion());
        summary.put("completedAt", run.completedAt());
        return summary;
    }

    private Map<Long, Map<String, Object>> snapshotByMaterial(String inputSnapshot) {
        Map<Long, Map<String, Object>> result = new LinkedHashMap<>();
        Object parsed = parseJson(inputSnapshot);
        if (!(parsed instanceof Map<?, ?> map) || !(map.get("materials") instanceof List<?> list)) {
            return result;
        }
        for (Object raw : list) {
            if (raw instanceof Map<?, ?> item && item.get("materialId") != null) {
                Map<String, Object> value = new LinkedHashMap<>();
                item.forEach((key, entry) -> value.put(String.valueOf(key), entry));
                result.put(Long.parseLong(String.valueOf(item.get("materialId"))), value);
            }
        }
        return result;
    }

    private static List<String> missingCodes(String json) {
        Object parsed = parseJson(json);
        if (!(parsed instanceof List<?> list)) {
            return List.of();
        }
        List<String> codes = new ArrayList<>(list.size());
        for (Object raw : list) {
            codes.add(String.valueOf(raw));
        }
        return codes;
    }

    private static Object parseJson(String json) {
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            return Json.MAPPER.readValue(json, Object.class);
        } catch (Exception unreadable) {
            return null;
        }
    }

    private static LocalDate parseDate(String field, String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return LocalDate.parse(value);
        } catch (RuntimeException error) {
            throw BusinessException.invalid(field, "日期格式应为 YYYY-MM-DD：" + value);
        }
    }
}
