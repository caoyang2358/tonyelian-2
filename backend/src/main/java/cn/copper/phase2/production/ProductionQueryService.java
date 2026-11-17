package cn.copper.phase2.production;

import cn.copper.phase2.common.BusinessException;
import cn.copper.phase2.common.ErrorCode;
import cn.copper.phase2.common.ChangeLogModels.ChangeLogItem;
import cn.copper.phase2.common.ChangeLogRepository;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 生产记录查询侧：S2-R01 任务列表、R02 任务详情、R03 实际时间线、R04 记录详情。
 * 全部只读；未称重与明确零产出在投影中分开表达。
 */
@Service
public class ProductionQueryService {

    private final ProductionRepository repo;
    private final StockService stock;
    private final ChangeLogRepository changeLogs;

    public ProductionQueryService(ProductionRepository repo, StockService stock,
                                  ChangeLogRepository changeLogs) {
        this.repo = repo;
        this.stock = stock;
        this.changeLogs = changeLogs;
    }

    /** 页码从 1 开始，默认 20 条，最多 100 条。 */
    record PageSpec(int page, int pageSize) {
    }

    private static PageSpec normalizePage(Integer page, Integer pageSize) {
        int p = page == null || page < 1 ? 1 : page;
        int size = pageSize == null || pageSize < 1 ? 20 : Math.min(pageSize, 100);
        return new PageSpec(p, size);
    }

    // ---------------------------------------------------------------- R01

    public Map<String, Object> listTasks(Long workshopId, Long orderId, String plannedFrom,
                                         String plannedTo, String actualFrom, String actualTo,
                                         String executionState, Integer page, Integer pageSize) {
        LocalDate plannedStart = ProductionTypes.parseDate("plannedFrom", plannedFrom, false);
        LocalDate plannedEnd = ProductionTypes.parseDate("plannedTo", plannedTo, false);
        LocalDate actualStart = ProductionTypes.parseDate("actualFrom", actualFrom, false);
        LocalDate actualEnd = ProductionTypes.parseDate("actualTo", actualTo, false);
        PageSpec pageSpec = normalizePage(page, pageSize);
        ProductionRepository.TaskFilter filter = new ProductionRepository.TaskFilter(
                workshopId, orderId, plannedStart, plannedEnd, actualStart, actualEnd,
                executionState, pageSpec.page(), pageSpec.pageSize());
        List<ProductionRepository.TaskRow> rows = repo.listTasks(filter);
        long total = repo.countTasks(filter);
        List<Map<String, Object>> items = new ArrayList<>();
        for (ProductionRepository.TaskRow row : rows) {
            Map<String, Object> item = taskSummary(row);
            if (executionState != null && !executionState.isBlank()
                    && !executionState.equals(item.get("executionState"))) {
                continue;
            }
            items.add(item);
        }
        return PageResultEnvelope.of(items, pageSpec.page(), pageSpec.pageSize(),
                executionState != null && !executionState.isBlank() ? items.size() : total);
    }

    private Map<String, Object> taskSummary(ProductionRepository.TaskRow row) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("taskId", ProductionTypes.id(row.taskId()));
        item.put("workshopId", ProductionTypes.id(row.workshopId()));
        item.put("materialId", ProductionTypes.id(row.materialId()));
        item.put("plannedDate", row.productionDate().toString());
        item.put("sequenceNo", row.sequenceNo());
        List<String> orderIds = repo.taskOrders(row.taskId()).stream()
                .map(o -> ProductionTypes.id(o.orderId())).distinct().toList();
        item.put("orderIds", orderIds);
        item.put("version", row.version());
        item.put("plannedQualifiedTon", ProductionTypes.ton(row.plannedQualifiedTon()));
        BigDecimal actual = qualifiedOrNull(row);
        item.put("actualQualifiedTon", ProductionTypes.ton(actual));
        item.put("varianceTon", actual == null ? null : ProductionTypes.ton(
                actual.subtract(row.plannedQualifiedTon())));
        String executionState = executionState(row);
        item.put("executionState", executionState);
        item.put("weighingState", weighingState(row));
        return item;
    }

    /** 未称重且未确认零产出时实际量与差额为 null（待统计）。 */
    private BigDecimal qualifiedOrNull(ProductionRepository.TaskRow row) {
        if (repo.taskHasWeighing(row.taskId())) {
            return repo.taskQualifiedTon(row.taskId());
        }
        if (repo.taskHasNoOutput(row.taskId())) {
            return BigDecimal.ZERO.setScale(3, RoundingMode.HALF_UP);
        }
        return null;
    }

    private String executionState(ProductionRepository.TaskRow row) {
        if ("COMPLETED".equals(row.status())) {
            return ProductionTypes.EXEC_COMPLETED;
        }
        List<ProductionRepository.ExecutionRow> fragments = repo.taskExecutions(row.taskId());
        if (fragments.isEmpty()) {
            return ProductionTypes.EXEC_NOT_STARTED;
        }
        boolean open = fragments.stream().anyMatch(f -> f.startedAt() != null && f.stoppedAt() == null);
        return open ? ProductionTypes.EXEC_IN_PROGRESS : ProductionTypes.EXEC_STOPPED;
    }

    private String weighingState(ProductionRepository.TaskRow row) {
        if (repo.taskHasWeighing(row.taskId())) {
            return ProductionTypes.WEIGH_RECORDED;
        }
        if (repo.taskHasNoOutput(row.taskId())) {
            return ProductionTypes.WEIGH_CONFIRMED_ZERO;
        }
        return ProductionTypes.WEIGH_PENDING;
    }

    // ---------------------------------------------------------------- R02

    public Map<String, Object> taskDetail(long taskId) {
        ProductionRepository.TaskRow task = repo.findTask(taskId);
        if (task == null) {
            throw BusinessException.notFound("任务不存在或已失效。");
        }
        Map<String, Object> data = taskSummary(task);
        data.put("planId", ProductionTypes.id(task.planId()));
        data.put("plannedPlateTon", ProductionTypes.ton(task.plannedPlateTon()));
        data.put("plannedRodUseTon", ProductionTypes.ton(task.plannedRodUseTon()));

        ProductionRepository.MaterialRow material = repo.findMaterial(task.materialId());
        data.put("materialName", material == null ? null : material.name());
        data.put("materialSpec", material == null ? null : material.specification());

        List<Map<String, Object>> orders = new ArrayList<>();
        for (ProductionRepository.TaskOrderRow link : repo.taskOrders(taskId)) {
            ProductionRepository.OrderRow order = repo.findOrder(link.orderId());
            if (order == null) {
                continue;
            }
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("orderId", ProductionTypes.id(order.orderId()));
            entry.put("orderNo", order.orderNo());
            entry.put("customerName", order.customerName());
            entry.put("demandKey", link.demandKey());
            entry.put("quantityKind", link.quantityKind());
            entry.put("quantityTon", ProductionTypes.ton(link.quantityTon()));
            entry.put("version", order.version());
            entry.put("productionResult", order.productionResult());
            orders.add(entry);
        }
        data.put("orders", orders);

        List<ProductionRepository.PlateInputRow> inputs = jdbcPlateInputs(taskId);
        BigDecimal inputTon = inputs.stream().map(ProductionRepository.PlateInputRow::inputTon)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        data.put("plateInputSummaryTon", ProductionTypes.ton(inputTon));
        data.put("plateInputCount", inputs.size());

        List<ProductionRepository.ExecutionRow> executions = repo.taskExecutions(taskId);
        data.put("executions", executions.stream().map(e -> {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("recordId", ProductionTypes.id(e.outputId()));
            entry.put("actualDate", e.actualDate().toString());
            entry.put("startedAt", ProductionTypes.time(e.startedAt()));
            entry.put("stoppedAt", ProductionTypes.time(e.stoppedAt()));
            entry.put("operatorName", e.operatorName());
            entry.put("version", e.version());
            return entry;
        }).toList());

        List<ProductionRepository.HandoverRow> handovers = jdbcTaskHandovers(taskId);
        data.put("handoverCount", handovers.size());

        List<Map<String, Object>> reservations = new ArrayList<>();
        for (StockService.ReservationRow r : stock.lockReservationsByTask(taskId)) {
            BigDecimal remaining = r.reservedTon().subtract(r.consumedTon()).subtract(r.releasedTon());
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("reservationId", ProductionTypes.id(r.reservationId()));
            entry.put("materialId", ProductionTypes.id(r.materialId()));
            entry.put("reservedTon", ProductionTypes.ton(r.reservedTon()));
            entry.put("remainingTon", ProductionTypes.ton(remaining));
            reservations.add(entry);
        }
        data.put("reservations", reservations);

        StockService.BalanceSnapshot plate = stock.readBalance(plateMaterialId());
        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("materialId", ProductionTypes.id(plate.materialId()));
        snapshot.put("physicalTon", ProductionTypes.ton(plate.physicalTon()));
        snapshot.put("version", plate.version());
        data.put("inventory", snapshot);

        List<String> missing = new ArrayList<>();
        if (!repo.taskHasWeighing(taskId) && !repo.taskHasNoOutput(taskId)) {
            missing.add("QUALIFIED_WEIGHING");
        }
        for (ProductionRepository.PlateInputRow input : inputs) {
            if (input.materialAmountYuan() == null) {
                missing.add("materialAmountYuan");
            }
        }
        for (ProductionRepository.HandoverRow handover : handovers) {
            if (handover.receivedTon() == null) {
                missing.add("receivedQuantityTon");
            }
        }
        data.put("missingItems", missing);
        data.put("availableActions", availableActions(task));
        return data;
    }

    private List<String> availableActions(ProductionRepository.TaskRow task) {
        List<String> actions = new ArrayList<>();
        if ("COMPLETED".equals(task.status())) {
            return actions;
        }
        actions.add("EXECUTION_RECORD");
        if (task.workshopId() == workshopRod()) {
            actions.add("PLATE_INPUT");
            actions.add("ROD_OUTPUT");
        } else {
            actions.add("ROD_HANDOVER");
            actions.add("WIRE_OUTPUT");
        }
        actions.add("NO_OUTPUT_CONFIRMATION");
        actions.add("TASK_COMPLETION");
        return actions;
    }

    private long workshopRod() {
        return workshopIdByCode("WS-ROD");
    }

    long workshopIdByCode(String code) {
        Long id = repo.workshopIdByCode(code);
        if (id == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "车间字典缺少 " + code + "。");
        }
        return id;
    }

    long plateMaterialId() {
        return materialIdByCode("COPPER-PLATE");
    }

    long rodMaterialId() {
        return materialIdByCode("COPPER-ROD-PHI8");
    }

    long materialIdByCode(String code) {
        Long id = repo.materialIdByCode(code);
        if (id == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "物料字典缺少 " + code + "。");
        }
        return id;
    }

    private List<ProductionRepository.PlateInputRow> jdbcPlateInputs(long taskId) {
        return repo.plateInputsByTask(taskId);
    }

    private List<ProductionRepository.HandoverRow> jdbcTaskHandovers(long taskId) {
        return repo.handoversByTask(taskId);
    }

    // ---------------------------------------------------------------- R03

    public Map<String, Object> listRecords(String recordType, Long taskId, Long productionGroupId,
                                           Long workshopId, Long orderId, String actualFrom,
                                           String actualTo, String source, Integer page,
                                           Integer pageSize) {
        validateRecordType(recordType);
        if (source != null && !source.isBlank()
                && !ProductionTypes.SOURCE_PLANNED.equals(source)
                && !ProductionTypes.SOURCE_UNPLANNED.equals(source)) {
            throw BusinessException.invalid("source", "来源只允许 PLANNED 或 UNPLANNED。");
        }
        PageSpec pageSpec = normalizePage(page, pageSize);
        ProductionRepository.RecordFilter filter = new ProductionRepository.RecordFilter(
                recordType, taskId, productionGroupId, workshopId, orderId,
                ProductionTypes.parseDate("actualFrom", actualFrom, false),
                ProductionTypes.parseDate("actualTo", actualTo, false),
                source, pageSpec.page(), pageSpec.pageSize());
        List<ProductionRepository.TimelineRow> rows = repo.timeline(filter,
                pageSpec.pageSize(), (int) ((pageSpec.page() - 1) * pageSpec.pageSize()));
        long total = repo.countTimeline(filter);
        List<Map<String, Object>> items = new ArrayList<>();
        for (ProductionRepository.TimelineRow row : rows) {
            items.add(timelineItem(row));
        }
        return PageResultEnvelope.of(items, pageSpec.page(), pageSpec.pageSize(), total);
    }

    private void validateRecordType(String recordType) {
        if (recordType == null || recordType.isBlank()) {
            return;
        }
        List<String> allowed = List.of(ProductionTypes.RT_EXECUTION, ProductionTypes.RT_PLATE_INPUT,
                ProductionTypes.RT_ROD_HANDOVER, ProductionTypes.RT_ROD_OUTPUT,
                ProductionTypes.RT_WIRE_OUTPUT, ProductionTypes.RT_NO_OUTPUT);
        if (!allowed.contains(recordType)) {
            throw BusinessException.invalid("recordType", "记录类型不在白名单内。");
        }
    }

    private Map<String, Object> timelineItem(ProductionRepository.TimelineRow row) {
        Map<String, Object> item = new LinkedHashMap<>();
        // 合格产出按实际物料对外区分铜杆/铜丝称重。
        String externalType = row.recordType();
        if (ProductionTypes.ROW_QUALIFIED_OUTPUT.equals(row.recordType())) {
            externalType = row.materialId() != null && row.materialId() == rodMaterialId()
                    ? ProductionTypes.RT_ROD_OUTPUT : ProductionTypes.RT_WIRE_OUTPUT;
        }
        item.put("recordType", externalType);
        item.put("recordId", ProductionTypes.id(row.recordId()));
        item.put("actualDate", row.actualDate().toString());
        item.put("recordedAt", ProductionTypes.time(row.recordedAt()));
        item.put("workshopId", ProductionTypes.id(row.workshopId()));
        item.put("materialId", ProductionTypes.id(row.materialId()));
        item.put("taskId", row.taskId() == null ? null
                : ProductionTypes.id(row.taskId()));
        item.put("operatorName", row.operatorName());
        item.put("source", row.taskId() == null ? ProductionTypes.SOURCE_UNPLANNED
                : ProductionTypes.SOURCE_PLANNED);
        if (row.groupId() != null) {
            ProductionRepository.GroupRow group = repo.findGroup(row.groupId());
            item.put("productionGroupId", ProductionTypes.id(row.groupId()));
            item.put("groupVersion", group == null ? null : group.version());
            item.put("anchorActualDate", group == null ? null : group.anchorActualDate().toString());
        }
        switch (externalType) {
            case ProductionTypes.RT_ROD_HANDOVER -> {
                item.put("sentQuantityTon", ProductionTypes.ton(row.sentTon()));
                item.put("receivedQuantityTon", ProductionTypes.ton(row.receivedTon()));
                item.put("orderId", ProductionTypes.id(row.orderId()));
            }
            case ProductionTypes.RT_NO_OUTPUT -> item.put("qualifiedQuantityTon", "0.000");
            case ProductionTypes.RT_EXECUTION -> { /* 执行片段无数量 */ }
            default -> item.put("quantityTon", ProductionTypes.ton(row.quantityTon()));
        }
        if (row.voidedAt() != null) {
            item.put("voidedAt", ProductionTypes.time(row.voidedAt()));
        }
        return item;
    }

    // ---------------------------------------------------------------- R04

    public Map<String, Object> recordDetail(String recordType, long recordId) {
        validateRecordType(recordType);
        if (recordType == null || recordType.isBlank()) {
            throw BusinessException.invalid("recordType", "查看记录详情必须指定记录类型。");
        }
        return switch (recordType) {
            case ProductionTypes.RT_EXECUTION, ProductionTypes.RT_ROD_OUTPUT,
                 ProductionTypes.RT_WIRE_OUTPUT, ProductionTypes.RT_NO_OUTPUT ->
                    outputDetail(recordType, recordId);
            case ProductionTypes.RT_PLATE_INPUT -> plateInputDetail(recordId);
            case ProductionTypes.RT_ROD_HANDOVER -> handoverDetail(recordId);
            default -> throw BusinessException.invalid("recordType", "记录类型不在白名单内。");
        };
    }

    private Map<String, Object> outputDetail(String recordType, long outputId) {
        ProductionRepository.OutputRow row = repo.findOutput(outputId);
        if (row == null) {
            throw BusinessException.notFound("产出记录不存在。");
        }
        String actualType = switch (row.recordType()) {
            case ProductionTypes.ROW_EXECUTION -> ProductionTypes.RT_EXECUTION;
            case ProductionTypes.ROW_NO_OUTPUT -> ProductionTypes.RT_NO_OUTPUT;
            default -> isRodMaterial(row.materialId()) ? ProductionTypes.RT_ROD_OUTPUT
                    : ProductionTypes.RT_WIRE_OUTPUT;
        };
        if (!actualType.equals(recordType)) {
            throw BusinessException.notFound("记录类型与该记录不符。");
        }
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("recordType", actualType);
        data.put("recordId", ProductionTypes.id(outputId));
        data.put("original", factSnapshot(row));
        data.put("current", factSnapshot(row));
        data.put("version", row.version());
        data.put("voidedAt", ProductionTypes.time(row.voidedAt()));
        data.put("corrections", changeLogs("P2_OUTPUT_RECORD", outputId));
        if (row.movementId() != null) {
            data.put("movement", movementSnapshot(row.movementId()));
        }
        data.put("taskShares", repo.outputShares(outputId).stream().map(s -> {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("taskId", ProductionTypes.id(s.taskId()));
            entry.put("quantityTon", ProductionTypes.ton(s.quantityTon()));
            return entry;
        }).toList());
        data.put("inputReferences", parseJsonArray(row.inputReferences()));
        data.put("allocations", repo.allocationsByOutput(outputId));
        data.put("missingItems", outputMissingItems(row));
        return data;
    }

    private boolean isRodMaterial(Long materialId) {
        return materialId != null && materialId == rodMaterialId();
    }

    private Map<String, Object> plateInputDetail(long inputId) {
        ProductionRepository.PlateInputRow row = repo.findPlateInput(inputId);
        if (row == null) {
            throw BusinessException.notFound("投料记录不存在。");
        }
        Map<String, Object> data = new LinkedHashMap<>();
        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("recordId", ProductionTypes.id(inputId));
        snapshot.put("taskId", ProductionTypes.id(row.taskId()));
        snapshot.put("actualDate", row.actualDate().toString());
        snapshot.put("inputQuantityTon", ProductionTypes.ton(row.inputTon()));
        snapshot.put("materialAmountYuan", ProductionTypes.yuan(row.materialAmountYuan()));
        snapshot.put("operatorName", row.operatorName());
        data.put("original", snapshot);
        data.put("current", snapshot);
        data.put("version", row.version());
        data.put("corrections", changeLogs("P2_PLATE_INPUT", inputId));
        data.put("movement", movementSnapshot(row.movementId()));
        List<String> missing = new ArrayList<>();
        if (row.materialAmountYuan() == null) {
            missing.add("materialAmountYuan");
        }
        data.put("missingItems", missing);
        return data;
    }

    private Map<String, Object> handoverDetail(long handoverId) {
        ProductionRepository.HandoverRow row = repo.findHandover(handoverId);
        if (row == null) {
            throw BusinessException.notFound("交接记录不存在。");
        }
        Map<String, Object> data = new LinkedHashMap<>();
        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("recordId", ProductionTypes.id(handoverId));
        snapshot.put("taskId", ProductionTypes.id(row.taskId()));
        snapshot.put("orderId", ProductionTypes.id(row.orderId()));
        snapshot.put("actualDate", row.actualDate() == null ? null : row.actualDate().toString());
        snapshot.put("sentQuantityTon", ProductionTypes.ton(row.sentTon()));
        snapshot.put("receivedQuantityTon", ProductionTypes.ton(row.receivedTon()));
        snapshot.put("sentAt", ProductionTypes.time(row.sentAt()));
        snapshot.put("receivedAt", ProductionTypes.time(row.receivedAt()));
        snapshot.put("releaseReason", row.releaseReason());
        snapshot.put("operatorName", row.operatorName());
        data.put("original", snapshot);
        data.put("current", snapshot);
        data.put("version", row.version());
        data.put("handoverState", handoverState(row));
        data.put("errorRate", errorRate(row));
        data.put("corrections", changeLogs("P2_ROD_HANDOVER", handoverId));
        data.put("movement", movementSnapshot(row.movementId()));
        List<String> missing = new ArrayList<>();
        if (row.receivedTon() == null) {
            missing.add("receivedQuantityTon");
            missing.add("receivedAt");
        }
        data.put("missingItems", missing);
        return data;
    }

    private String handoverState(ProductionRepository.HandoverRow row) {
        if (row.receivedTon() == null) {
            return ProductionTypes.HANDOVER_PENDING_RECEIPT;
        }
        BigDecimal sent = row.sentTon();
        if (sent.compareTo(BigDecimal.ZERO) > 0 && row.releaseReason() != null
                && row.receivedTon().subtract(sent).abs().multiply(BigDecimal.valueOf(100))
                .compareTo(sent.multiply(BigDecimal.valueOf(5))) > 0) {
            return ProductionTypes.HANDOVER_RELEASED;
        }
        return ProductionTypes.HANDOVER_CONFIRMED;
    }

    private String errorRate(ProductionRepository.HandoverRow row) {
        if (row.receivedTon() == null || row.sentTon().compareTo(BigDecimal.ZERO) == 0) {
            return null;
        }
        BigDecimal rate = row.receivedTon().subtract(row.sentTon()).abs()
                .divide(row.sentTon(), 6, RoundingMode.HALF_UP);
        return rate.toPlainString();
    }

    // ---------------------------------------------------------------- 组装辅助

    private Map<String, Object> factSnapshot(ProductionRepository.OutputRow row) {
        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("recordId", ProductionTypes.id(row.outputId()));
        snapshot.put("recordType", row.recordType());
        snapshot.put("workshopId", ProductionTypes.id(row.workshopId()));
        snapshot.put("taskId", ProductionTypes.id(row.taskId()));
        snapshot.put("materialId", ProductionTypes.id(row.materialId()));
        snapshot.put("actualDate", row.actualDate().toString());
        snapshot.put("startedAt", ProductionTypes.time(row.startedAt()));
        snapshot.put("stoppedAt", ProductionTypes.time(row.stoppedAt()));
        snapshot.put("qualifiedQuantityTon", ProductionTypes.ton(row.qualifiedTon()));
        snapshot.put("reason", row.reason());
        snapshot.put("operatorName", row.operatorName());
        return snapshot;
    }

    private List<Map<String, Object>> changeLogs(String objectType, long objectId) {
        List<Map<String, Object>> items = new ArrayList<>();
        for (ChangeLogItem item : changeLogs.page(objectType, String.valueOf(objectId), 1, 50)) {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("changeLogId", item.id());
            entry.put("action", item.operation());
            entry.put("before", item.before());
            entry.put("after", item.after());
            entry.put("reason", item.reason());
            entry.put("operatorName", item.operatorName());
            entry.put("recordedAt", item.recordedAt());
            items.add(entry);
        }
        return items;
    }

    private Map<String, Object> movementSnapshot(long movementId) {
        return repo.movementSnapshot(movementId);
    }

    private List<String> outputMissingItems(ProductionRepository.OutputRow row) {
        List<String> missing = new ArrayList<>();
        if (ProductionTypes.ROW_QUALIFIED_OUTPUT.equals(row.recordType())) {
            List<Map<String, Object>> allocations = repo.allocationsByOutput(row.outputId());
            BigDecimal allocated = allocations.stream()
                    .map(a -> new BigDecimal(String.valueOf(a.get("quantityTon"))))
                    .reduce(BigDecimal.ZERO, BigDecimal::add);
            if (row.qualifiedTon() != null && allocated.compareTo(row.qualifiedTon()) < 0) {
                missing.add("PUBLIC_QUANTITY_UNALLOCATED");
            }
        }
        return missing;
    }

    private List<Map<String, Object>> parseJsonArray(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            JsonNode node = cn.copper.phase2.common.Json.MAPPER.readTree(json);
            List<Map<String, Object>> items = new ArrayList<>();
            if (node.isArray()) {
                node.forEach(child -> items.add(cn.copper.phase2.common.Json.MAPPER.convertValue(child,
                        Map.class)));
            }
            return items;
        } catch (Exception error) {
            return List.of();
        }
    }

    /** 简单分页信封。 */
    static final class PageResultEnvelope {
        private PageResultEnvelope() {
        }

        static Map<String, Object> of(List<Map<String, Object>> items, int page, int pageSize,
                                      long total) {
            Map<String, Object> envelope = new LinkedHashMap<>();
            envelope.put("items", items);
            envelope.put("page", page);
            envelope.put("pageSize", pageSize);
            envelope.put("total", total);
            return envelope;
        }
    }
}
