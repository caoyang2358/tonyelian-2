package cn.copper.phase2.maintenance;

import cn.copper.phase2.maintenance.RepairOrderModels.RepairAcceptanceCommand;
import cn.copper.phase2.maintenance.RepairOrderModels.RepairActionItem;
import cn.copper.phase2.maintenance.RepairOrderModels.RepairActionPage;
import cn.copper.phase2.maintenance.RepairOrderModels.RepairActionResult;
import cn.copper.phase2.maintenance.RepairOrderModels.RepairOrderCreateCommand;
import cn.copper.phase2.maintenance.RepairOrderModels.RepairOrderCreated;
import cn.copper.phase2.maintenance.RepairOrderModels.RepairOrderDetail;
import cn.copper.phase2.maintenance.RepairOrderModels.RepairOrderItem;
import cn.copper.phase2.maintenance.RepairOrderModels.RepairOrderPage;
import cn.copper.phase2.maintenance.RepairOrderModels.RepairOrderQuery;
import cn.copper.phase2.maintenance.RepairOrderModels.RepairOrderReviewCommand;
import cn.copper.phase2.maintenance.RepairOrderModels.RepairOrderSaveCommand;
import cn.copper.phase2.maintenance.RepairOrderModels.RepairOrderSaved;
import cn.copper.phase2.maintenance.RepairOrderModels.RepairOrderSubmitCommand;
import cn.copper.phase2.maintenance.RepairOrderModels.RepairResultCommand;
import cn.copper.phase2.maintenance.RepairOrderRepository.AlertSource;
import cn.copper.phase2.maintenance.RepairOrderRepository.InspectionSource;
import cn.copper.phase2.maintenance.RepairOrderRepository.OrderRow;
import cn.copper.phase2.maintenance.RepairOrderRepository.StatusRow;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 维修工单业务层：S1-R02 列表、R03 详情、R01 建单、R04 保存资料、R05 提交、
 * R06 审核、R07 维修结果、R08 验收、R09 动作历史。
 *
 * <p>写入遵循《接口通用约定》§3：requestId 幂等 → 行锁内校验版本与状态 →
 * 同一事务内写动作历史并推进状态。
 */
@Service
public class RepairOrderService {
    static final Set<String> REPAIR_ORDER_STATUSES = Set.of(
            "DRAFT", "REVIEW_PENDING", "RETURNED_FOR_INFO", "IN_REPAIR",
            "ACCEPTANCE_PENDING", "COMPLETED", "CLOSED");
    static final Set<String> REPAIR_SOURCE_TYPES = Set.of("ALERT_EVENT", "INSPECTION", "MANUAL");
    static final Set<String> REVIEW_RESULTS = Set.of("APPROVE", "RETURN_FOR_INFO", "NO_REPAIR", "DUPLICATE");
    static final Set<String> ACCEPTANCE_RESULTS = Set.of("PASS", "FAIL");
    /** 故障类别：机械、电气、仪表、工艺、其他。历史工单可能为空，界面显示"未分类"。 */
    static final Set<String> FAULT_CATEGORIES =
            Set.of("MECHANICAL", "ELECTRICAL", "INSTRUMENT", "PROCESS", "OTHER");

    static final String OPERATION_CREATE = "REPAIR_ORDER_CREATE";
    static final String OPERATION_SAVE = "REPAIR_ORDER_SAVE";
    static final String OPERATION_SUBMIT = "REPAIR_ORDER_SUBMIT";
    static final String OPERATION_REVIEW = "REPAIR_ORDER_REVIEW";
    static final String OPERATION_REPAIR_RESULT = "REPAIR_ORDER_REPAIR_RESULT";
    static final String OPERATION_ACCEPTANCE = "REPAIR_ORDER_ACCEPTANCE";

    /**
     * R03 内联返回的动作条数上限。单张工单的动作数通常个位数，
     * 这里仍设上限避免异常数据把详情响应撑大；完整历史走 R09 分页。
     */
    private static final int ACTION_HISTORY_LIMIT = 100;

    private static final Pattern UUID = Pattern.compile(
            "^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$");

    private final RepairOrderRepository repository;
    private final IdempotencyService idempotency;
    private final ObjectMapper objectMapper;

    public RepairOrderService(RepairOrderRepository repository, IdempotencyService idempotency,
                              ObjectMapper objectMapper) {
        this.repository = repository;
        this.idempotency = idempotency;
        this.objectMapper = objectMapper;
    }

    public RepairOrderPage repairOrders(RepairOrderQuery query, Integer page, Integer pageSize) {
        int resolvedPage = MaintenancePaging.page(page);
        int resolvedSize = MaintenancePaging.pageSize(pageSize);
        RepairOrderQuery validated = validate(query);

        long total = repository.countRepairOrders(validated);
        if (total == 0) {
            return new RepairOrderPage(List.of(), resolvedPage, resolvedSize, 0);
        }
        List<RepairOrderItem> items = repository.findRepairOrders(
                validated, MaintenancePaging.offset(resolvedPage, resolvedSize), resolvedSize);
        return new RepairOrderPage(items, resolvedPage, resolvedSize, total);
    }

    /** Called inside the knowledge write transaction; prevents a concurrent state change. */
    public RepairOrderDetail knowledgeSource(String repairOrderId) {
        long id = MaintenanceIds.parse("sourceRepairOrderId", repairOrderId);
        if (repository.lockOrder(id) == null) throw new MaintenanceNotFoundException("来源工单不存在");
        return detail(repairOrderId);
    }
    public RepairOrderDetail detail(String repairOrderId) {
        return detailOf(parseId("repairOrderId", repairOrderId));
    }

    public RepairActionPage actions(String repairOrderId, Integer page, Integer pageSize) {
        long id = parseId("repairOrderId", repairOrderId);
        if (repository.findOrder(id) == null) {
            throw new MaintenanceNotFoundException("工单不存在或已被删除，请刷新后重试。");
        }
        int resolvedPage = MaintenancePaging.page(page);
        int resolvedSize = MaintenancePaging.pageSize(pageSize);
        long total = repository.countActions(id);
        if (total == 0) {
            return new RepairActionPage(List.of(), resolvedPage, resolvedSize, 0);
        }
        List<RepairActionItem> items = repository.findActions(
                id, MaintenancePaging.offset(resolvedPage, resolvedSize), resolvedSize);
        return new RepairActionPage(items, resolvedPage, resolvedSize, total);
    }

    /** S1-R01 建单：来源决定设备与证据快照，来源快照在建单时冻结。 */
    @Transactional
    public RepairOrderCreated create(RepairOrderCreateCommand command) {
        RepairOrderCreateCommand request = validate(command);
        Optional<RepairOrderCreated> replayed = idempotency.claim(
                request.requestId(), OPERATION_CREATE, null, request, RepairOrderCreated.class);
        if (replayed.isPresent()) {
            return replayed.get();
        }

        long equipmentId;
        Long alertEventId = null;
        Long inspectionId = null;
        String snapshotJson = null;
        switch (request.sourceType()) {
            case "MANUAL" -> {
                equipmentId = parseId("equipmentId", request.equipmentId());
                if (!repository.equipmentExists(equipmentId)) {
                    throw new InvalidRequestException("equipmentId", "设备不存在，请重新选择。");
                }
            }
            case "ALERT_EVENT" -> {
                long eventId = parseId("sourceId", request.sourceId());
                AlertSource source = repository.findAlertSource(eventId);
                if (source == null) {
                    throw new MaintenanceNotFoundException("告警事件不存在或已被删除，请刷新后重试。");
                }
                equipmentId = Long.parseLong(source.equipmentId());
                alertEventId = eventId;
                snapshotJson = json(alertSnapshot(source));
            }
            case "INSPECTION" -> {
                long sourceId = parseId("sourceId", request.sourceId());
                InspectionSource source = repository.findInspectionSource(sourceId);
                if (source == null) {
                    throw new MaintenanceNotFoundException("巡检记录不存在或已被删除，请刷新后重试。");
                }
                equipmentId = Long.parseLong(source.equipmentId());
                inspectionId = sourceId;
                snapshotJson = json(inspectionSnapshot(source));
            }
            default -> throw new InvalidRequestException("sourceType",
                    "来源类型取值无效，应为 ALERT_EVENT / INSPECTION / MANUAL。");
        }

        LocalDateTime now = LocalDateTime.now(MaintenanceTimes.ZONE);
        long repairOrderId = repository.insertOrder("PENDING-" + request.requestId(), equipmentId,
                request.sourceType(), alertEventId, inspectionId, snapshotJson, request.faultCategory(),
                request.description(), request.reporterName(), now);
        repository.updateOrderNo(repairOrderId, orderNo(now, repairOrderId));
        repository.insertAction(repairOrderId, "CREATE", null, null, RepairOrderWorkflow.DRAFT,
                now, now, request.operatorName(), null, null);

        OrderRow row = repository.findOrder(repairOrderId);
        RepairOrderCreated created = new RepairOrderCreated(
                String.valueOf(repairOrderId), row.orderNo(), row.status(),
                parseSnapshot(snapshotJson),
                RepairOrderWorkflow.editableFields(row.status()), row.version());
        idempotency.complete(request.requestId(), created);
        return created;
    }

    /**
     * S1-R04 保存资料：不推进状态，只推进版本，并把前后快照写入留痕。
     *
     * <p>按整份保存处理：未传的可选字段会被清空，调用方每次提交整张表单。
     * 故障说明与报修人允许为空（草稿可以先存不完整资料），但正式提交时由 R05 校验齐备。
     */
    @Transactional
    public RepairOrderSaved save(String repairOrderId, RepairOrderSaveCommand command) {
        long id = parseId("repairOrderId", repairOrderId);
        RepairOrderSaveCommand request = validate(command);
        Optional<RepairOrderSaved> replayed = idempotency.claim(
                request.requestId(), OPERATION_SAVE, String.valueOf(id), request, RepairOrderSaved.class);
        if (replayed.isPresent()) {
            return replayed.get();
        }

        StatusRow current = lockOrder(id);
        assertVersion(current, request.expectedVersion());
        if (!RepairOrderWorkflow.EDITABLE.contains(current.status())) {
            throw stateConflict(current, "当前状态不允许修改工单资料。");
        }

        List<Long> attachmentIds = parseIds("photoAttachmentIds", request.photoAttachmentIds());
        for (Long attachmentId : attachmentIds) {
            if (!repository.attachmentBelongsToOrder(attachmentId, id)) {
                throw new InvalidRequestException("photoAttachmentIds",
                        "照片 " + attachmentId + " 不属于该工单或已不存在，请重新上传。");
            }
        }

        RepairOrderDetail before = detailOf(id);
        repository.saveContent(id, request.description(), request.supplement(), request.reporterName(),
                request.faultCategory(), LocalDateTime.now(MaintenanceTimes.ZONE));
        repository.replacePhotos(id, attachmentIds);
        RepairOrderDetail after = detailOf(id);

        long changeLogId = repository.insertChangeLog("REPAIR_ORDER", String.valueOf(id), "REPAIR_ORDER_SAVE",
                json(before), json(after), request.reason(), request.operatorName());
        RepairOrderSaved saved = new RepairOrderSaved(after, String.valueOf(changeLogId));
        idempotency.complete(request.requestId(), saved);
        return saved;
    }

    /** S1-R05 正式提交：资料齐备才允许进入待审核，首次提交时间只记一次。 */
    @Transactional
    public RepairActionResult submit(String repairOrderId, RepairOrderSubmitCommand command) {
        long id = parseId("repairOrderId", repairOrderId);
        RepairOrderSubmitCommand request = validate(command);
        String key = String.valueOf(id);
        Optional<RepairActionResult> replayed = idempotency.claim(
                request.requestId(), OPERATION_SUBMIT, key, request, RepairActionResult.class);
        if (replayed.isPresent()) {
            return replayed.get();
        }

        StatusRow current = lockOrder(id);
        assertVersion(current, request.expectedVersion());
        OrderRow row = repository.findOrder(id);
        if (blankToNull(row.description()) == null) {
            throw new InvalidRequestException("description", "正式提交前必须填写故障说明。");
        }
        if (blankToNull(row.reporterName()) == null) {
            throw new InvalidRequestException("reporterName", "正式提交前必须填写报修人。");
        }
        String next = requireTransition(current, RepairOrderWorkflow.ACTION_SUBMIT);

        LocalDateTime now = LocalDateTime.now(MaintenanceTimes.ZONE);
        long actionId = repository.insertAction(id, RepairOrderWorkflow.actionType(RepairOrderWorkflow.ACTION_SUBMIT),
                null, current.status(), next, now, now, request.operatorName(), request.reason(), null);
        repository.advanceStatus(id, next, now, now, null);
        return complete(request.requestId(), id, next, actionId);
    }

    /** S1-R06 审核：通过进入维修中，退回补充，无需维修或重复则直接关闭。 */
    @Transactional
    public RepairActionResult review(String repairOrderId, RepairOrderReviewCommand command) {
        long id = parseId("repairOrderId", repairOrderId);
        RepairOrderReviewCommand request = validate(command);
        String key = String.valueOf(id);
        Optional<RepairActionResult> replayed = idempotency.claim(
                request.requestId(), OPERATION_REVIEW, key, request, RepairActionResult.class);
        if (replayed.isPresent()) {
            return replayed.get();
        }

        StatusRow current = lockOrder(id);
        assertVersion(current, request.expectedVersion());
        String action = switch (request.result()) {
            case "APPROVE" -> RepairOrderWorkflow.ACTION_APPROVE;
            case "RETURN_FOR_INFO" -> RepairOrderWorkflow.ACTION_RETURN_FOR_INFO;
            case "NO_REPAIR" -> RepairOrderWorkflow.ACTION_NO_REPAIR;
            default -> RepairOrderWorkflow.ACTION_DUPLICATE;
        };
        String next = requireTransition(current, action);

        LocalDateTime now = LocalDateTime.now(MaintenanceTimes.ZONE);
        long actionId = repository.insertAction(id, RepairOrderWorkflow.actionType(action), request.result(),
                current.status(), next, now, now, request.operatorName(), request.reason(), null);
        repository.advanceStatus(id, next, now, null, null);
        return complete(request.requestId(), id, next, actionId);
    }

    /** S1-R07 维修结果：保存本轮实际处理内容，工单转入待验收。 */
    @Transactional
    public RepairActionResult recordRepairResult(String repairOrderId, RepairResultCommand command) {
        long id = parseId("repairOrderId", repairOrderId);
        RepairResultCommand request = validate(command);
        String key = String.valueOf(id);
        Optional<RepairActionResult> replayed = idempotency.claim(
                request.requestId(), OPERATION_REPAIR_RESULT, key, request, RepairActionResult.class);
        if (replayed.isPresent()) {
            return replayed.get();
        }

        StatusRow current = lockOrder(id);
        assertVersion(current, request.expectedVersion());
        String next = requireTransition(current, RepairOrderWorkflow.ACTION_REPAIR_RESULT);

        LocalDateTime performedAt = toLocal(MaintenanceTimes.parseMoment("performedAt", request.performedAt()));
        LocalDateTime now = LocalDateTime.now(MaintenanceTimes.ZONE);
        long actionId = repository.insertAction(id, RepairOrderWorkflow.actionType(RepairOrderWorkflow.ACTION_REPAIR_RESULT),
                null, current.status(), next, performedAt, now,
                request.operatorName(), request.reason(), request.description());

        List<Long> attachmentIds = parseIds("attachmentIds", request.attachmentIds());
        for (Long attachmentId : attachmentIds) {
            if (!repository.attachmentBelongsToOrder(attachmentId, id)) {
                throw new InvalidRequestException("attachmentIds",
                        "附件 " + attachmentId + " 不属于该工单或已不存在，请重新上传。");
            }
            repository.linkActionAttachment(actionId, attachmentId);
        }

        repository.advanceStatus(id, next, now, null, null);
        return complete(request.requestId(), id, next, actionId);
    }

    /** S1-R08 验收：通过即完成，不通过回到维修中继续下一轮。 */
    @Transactional
    public RepairActionResult accept(String repairOrderId, RepairAcceptanceCommand command) {
        long id = parseId("repairOrderId", repairOrderId);
        RepairAcceptanceCommand request = validate(command);
        String key = String.valueOf(id);
        Optional<RepairActionResult> replayed = idempotency.claim(
                request.requestId(), OPERATION_ACCEPTANCE, key, request, RepairActionResult.class);
        if (replayed.isPresent()) {
            return replayed.get();
        }

        StatusRow current = lockOrder(id);
        assertVersion(current, request.expectedVersion());
        String action = "PASS".equals(request.result())
                ? RepairOrderWorkflow.ACTION_ACCEPT_PASS
                : RepairOrderWorkflow.ACTION_ACCEPT_FAIL;
        String next = requireTransition(current, action);

        LocalDateTime acceptedAt = toLocal(MaintenanceTimes.parseMoment("acceptedAt", request.acceptedAt()));
        // 验收时刻不能早于首次提交：否则概览的处理时长会算出负数，等于用脏数据污染统计。
        if (current.firstSubmittedAt() != null && acceptedAt.isBefore(current.firstSubmittedAt())) {
            throw new InvalidRequestException("acceptedAt", "验收时间不能早于首次提交时间。");
        }
        LocalDateTime now = LocalDateTime.now(MaintenanceTimes.ZONE);
        long actionId = repository.insertAction(id, RepairOrderWorkflow.actionType(action), request.result(),
                current.status(), next, acceptedAt, now, request.operatorName(), request.reason(), null);
        repository.advanceStatus(id, next, now, null,
                RepairOrderWorkflow.COMPLETED.equals(next) ? acceptedAt : null);
        return complete(request.requestId(), id, next, actionId);
    }

    // ---- 内部实现 ----

    private RepairActionResult complete(String requestId, long repairOrderId, String status, long actionId) {
        OrderRow row = repository.findOrder(repairOrderId);
        RepairActionResult result = new RepairActionResult(
                String.valueOf(repairOrderId), status, String.valueOf(actionId), row.version(), row.updatedAt());
        idempotency.complete(requestId, result);
        return result;
    }

    private RepairOrderDetail detailOf(long id) {
        OrderRow row = repository.findOrder(id);
        if (row == null) {
            throw new MaintenanceNotFoundException("工单不存在或已被删除，请刷新后重试。");
        }
        return new RepairOrderDetail(
                row.repairOrderId(),
                row.orderNo(),
                row.equipmentId(),
                row.equipmentName(),
                row.sourceType(),
                row.sourceId(),
                parseSnapshot(row.sourceSnapshotJson()),
                row.faultCategory(),
                row.status(),
                row.description(),
                row.supplement(),
                row.reporterName(),
                repository.findPhotoIds(id),
                RepairOrderWorkflow.availableActions(row.status()),
                repository.findActions(id, 0, ACTION_HISTORY_LIMIT),
                row.createdAt(),
                row.updatedAt(),
                row.firstSubmittedAt(),
                row.completedAt(),
                row.version());
    }

    private StatusRow lockOrder(long id) {
        StatusRow current = repository.lockOrder(id);
        if (current == null) {
            throw new MaintenanceNotFoundException("工单不存在或已被删除，请刷新后重试。");
        }
        return current;
    }

    /** 版本先于状态校验：版本不符时前端首要动作是刷新，报版本冲突更能指导操作。 */
    private static void assertVersion(StatusRow current, int expectedVersion) {
        if (current.version() != expectedVersion) {
            throw new MaintenanceConflictException("VERSION_CONFLICT", "该工单已被更新，请刷新后重试。",
                    Map.of("currentVersion", current.version(), "status", current.status()));
        }
    }

    private static String requireTransition(StatusRow current, String action) {
        String next = RepairOrderWorkflow.nextStatus(current.status(), action);
        if (next == null) {
            throw stateConflict(current, "当前状态不允许该操作，请刷新后重试。");
        }
        return next;
    }

    private static MaintenanceConflictException stateConflict(StatusRow current, String message) {
        return new MaintenanceConflictException("STATE_CONFLICT", message,
                Map.of("status", current.status(), "version", current.version()));
    }

    /** 单号形如 RO-20261103-000123，后六位取自自增主键，天然唯一。 */
    private static String orderNo(LocalDateTime now, long repairOrderId) {
        return "RO-" + now.toLocalDate().toString().replace("-", "")
                + "-" + String.format("%06d", repairOrderId);
    }

    private Map<String, Object> alertSnapshot(AlertSource source) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("eventId", source.eventId());
        map.put("equipmentId", source.equipmentId());
        map.put("equipmentName", source.equipmentName());
        map.put("sensorId", source.sensorId());
        map.put("sensorCode", source.sensorCode());
        map.put("sensorName", source.sensorName());
        map.put("parameterId", source.parameterId());
        map.put("parameterCode", source.parameterCode());
        map.put("parameterName", source.parameterName());
        map.put("startedAt", text(source.startedAt()));
        map.put("triggeredAt", text(source.triggeredAt()));
        map.put("triggerValue", source.triggerValue());
        map.put("unitCode", source.unitCode());
        map.put("endedAt", text(source.endedAt()));
        map.put("endReason", source.endReason());
        map.put("eventVersion", source.version());
        return map;
    }

    private Map<String, Object> inspectionSnapshot(InspectionSource source) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("inspectionId", source.inspectionId());
        map.put("equipmentId", source.equipmentId());
        map.put("equipmentName", source.equipmentName());
        map.put("inspectedAt", text(source.inspectedAt()));
        map.put("inspectorName", source.inspectorName());
        map.put("result", source.result());
        map.put("content", source.content());
        map.put("description", source.description());
        map.put("inspectionVersion", source.version());
        return map;
    }

    private static String text(OffsetDateTime moment) {
        return moment == null ? null : moment.toString();
    }

    private Object parseSnapshot(String json) {
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            return objectMapper.readValue(json, Object.class);
        } catch (JsonProcessingException error) {
            throw new IllegalStateException("来源快照无法解析", error);
        }
    }

    private String json(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException error) {
            throw new IllegalStateException("留痕内容无法序列化", error);
        }
    }

    private static LocalDateTime toLocal(OffsetDateTime moment) {
        return MaintenanceTimes.toLocal(moment);
    }

    // ---- 参数校验 ----

    private static RepairOrderCreateCommand validate(RepairOrderCreateCommand command) {
        if (command == null) {
            throw new InvalidRequestException("body", "请求体不能为空。");
        }
        String sourceType = requiredText("sourceType", command.sourceType(), 16, "来源类型");
        if (!REPAIR_SOURCE_TYPES.contains(sourceType)) {
            throw new InvalidRequestException("sourceType", "来源类型取值无效，应为 ALERT_EVENT / INSPECTION / MANUAL。");
        }
        String sourceId = blankToNull(command.sourceId());
        String equipmentId = blankToNull(command.equipmentId());
        if ("MANUAL".equals(sourceType)) {
            if (sourceId != null) {
                throw new InvalidRequestException("sourceId", "手工建单不需要来源 ID，请改传 equipmentId。");
            }
            requiredDigits("equipmentId", equipmentId);
        } else {
            requiredDigits("sourceId", sourceId);
        }
        String faultCategory = optionalText("faultCategory", command.faultCategory(), 32);
        if (faultCategory != null) {
            faultCategory = faultCategory.toUpperCase(java.util.Locale.ROOT);
            if (!FAULT_CATEGORIES.contains(faultCategory)) {
                throw new InvalidRequestException("faultCategory",
                        "故障类别取值无效，应为 MECHANICAL / ELECTRICAL / INSTRUMENT / PROCESS / OTHER。");
            }
        }
        return new RepairOrderCreateCommand(
                requestId(command.requestId()),
                operatorName(command.operatorName()),
                sourceType,
                "MANUAL".equals(sourceType) ? null : sourceId,
                "MANUAL".equals(sourceType) ? equipmentId : null,
                faultCategory,
                optionalText("description", command.description(), 5000),
                optionalText("reporterName", command.reporterName(), 100));
    }

    private static RepairOrderSaveCommand validate(RepairOrderSaveCommand command) {
        if (command == null) {
            throw new InvalidRequestException("body", "请求体不能为空。");
        }
        return new RepairOrderSaveCommand(
                requestId(command.requestId()),
                operatorName(command.operatorName()),
                expectedVersion(command.expectedVersion()),
                optionalText("reason", command.reason(), 1000),
                optionalText("description", command.description(), 5000),
                optionalText("supplement", command.supplement(), 5000),
                optionalText("reporterName", command.reporterName(), 100),
                optionalText("faultCategory", command.faultCategory(), 32),
                command.photoAttachmentIds());
    }

    private static RepairOrderSubmitCommand validate(RepairOrderSubmitCommand command) {
        if (command == null) {
            throw new InvalidRequestException("body", "请求体不能为空。");
        }
        return new RepairOrderSubmitCommand(
                requestId(command.requestId()),
                operatorName(command.operatorName()),
                expectedVersion(command.expectedVersion()),
                optionalText("reason", command.reason(), 1000));
    }

    private static RepairOrderReviewCommand validate(RepairOrderReviewCommand command) {
        if (command == null) {
            throw new InvalidRequestException("body", "请求体不能为空。");
        }
        String result = requiredText("result", command.result(), 32, "审核结果");
        if (!REVIEW_RESULTS.contains(result)) {
            throw new InvalidRequestException("result",
                    "审核结果取值无效，应为 APPROVE / RETURN_FOR_INFO / NO_REPAIR / DUPLICATE。");
        }
        String reason = optionalText("reason", command.reason(), 1000);
        if (!"APPROVE".equals(result) && reason == null) {
            throw new InvalidRequestException("reason", "退回补充、无需维修和重复关闭都必须填写原因。");
        }
        return new RepairOrderReviewCommand(
                requestId(command.requestId()),
                operatorName(command.operatorName()),
                expectedVersion(command.expectedVersion()),
                result,
                reason);
    }

    private static RepairResultCommand validate(RepairResultCommand command) {
        if (command == null) {
            throw new InvalidRequestException("body", "请求体不能为空。");
        }
        return new RepairResultCommand(
                requestId(command.requestId()),
                operatorName(command.operatorName()),
                expectedVersion(command.expectedVersion()),
                optionalText("reason", command.reason(), 1000),
                requiredMoment("performedAt", command.performedAt()),
                requiredText("description", command.description(), 5000, "维修内容"),
                command.attachmentIds());
    }

    private static RepairAcceptanceCommand validate(RepairAcceptanceCommand command) {
        if (command == null) {
            throw new InvalidRequestException("body", "请求体不能为空。");
        }
        String result = requiredText("result", command.result(), 16, "验收结果");
        if (!ACCEPTANCE_RESULTS.contains(result)) {
            throw new InvalidRequestException("result", "验收结果取值无效，应为 PASS 或 FAIL。");
        }
        String reason = optionalText("reason", command.reason(), 1000);
        if ("FAIL".equals(result) && reason == null) {
            throw new InvalidRequestException("reason", "验收不通过必须填写原因。");
        }
        return new RepairAcceptanceCommand(
                requestId(command.requestId()),
                operatorName(command.operatorName()),
                expectedVersion(command.expectedVersion()),
                result,
                requiredMoment("acceptedAt", command.acceptedAt()),
                reason);
    }

    private static RepairOrderQuery validate(RepairOrderQuery query) {
        String equipmentId = blankToNull(query.equipmentId());
        if (equipmentId != null) {
            if (!equipmentId.chars().allMatch(Character::isDigit)) {
                throw new InvalidRequestException("equipmentId", "设备 ID 必须是十进制数字字符串。");
            }
            if (Long.parseLong(equipmentId) <= 0) {
                throw new InvalidRequestException("equipmentId", "设备 ID 必须大于 0。");
            }
        }

        String status = blankToNull(query.status());
        if (status != null && !REPAIR_ORDER_STATUSES.contains(status)) {
            throw new InvalidRequestException("status",
                    "工单状态取值无效，应为 " + String.join(" / ", REPAIR_ORDER_STATUSES.stream().sorted().toList()) + "。");
        }

        String sourceType = blankToNull(query.sourceType());
        if (sourceType != null && !REPAIR_SOURCE_TYPES.contains(sourceType)) {
            throw new InvalidRequestException("sourceType", "来源类型取值无效，应为 ALERT_EVENT / INSPECTION / MANUAL。");
        }

        String from = blankToNull(query.from());
        String to = blankToNull(query.to());
        OffsetDateTime fromMoment = parseMomentOrNull("from", from);
        OffsetDateTime toMoment = parseMomentOrNull("to", to);
        if (fromMoment != null && toMoment != null && !fromMoment.isBefore(toMoment)) {
            throw new InvalidRequestException("from", "开始时间必须早于结束时间。");
        }

        return new RepairOrderQuery(equipmentId, status, sourceType, from, to);
    }

    private static int expectedVersion(Integer value) {
        if (value == null) {
            throw new InvalidRequestException("expectedVersion", "该操作必须携带 expectedVersion。");
        }
        if (value < 0) {
            throw new InvalidRequestException("expectedVersion", "expectedVersion 不能为负数。");
        }
        return value;
    }

    private static String requestId(String value) {
        String text = blankToNull(value);
        if (text == null) {
            throw new InvalidRequestException("requestId", "requestId 必填，用于防止重复提交。");
        }
        if (!UUID.matcher(text).matches()) {
            throw new InvalidRequestException("requestId", "requestId 应为 36 位 UUID。");
        }
        return text;
    }

    private static String operatorName(String value) {
        return requiredText("operatorName", value, 100, "经办人");
    }

    private static String requiredText(String field, String value, int maxLength, String label) {
        String text = blankToNull(value);
        if (text == null) {
            throw new InvalidRequestException(field, label + "不能为空。");
        }
        if (text.length() > maxLength) {
            throw new InvalidRequestException(field, label + "不能超过 " + maxLength + " 个字符。");
        }
        return text;
    }

    private static String optionalText(String field, String value, int maxLength) {
        String text = blankToNull(value);
        if (text != null && text.length() > maxLength) {
            throw new InvalidRequestException(field, "内容不能超过 " + maxLength + " 个字符。");
        }
        return text;
    }

    private static String requiredDigits(String field, String value) {
        String text = blankToNull(value);
        if (text == null) {
            throw new InvalidRequestException(field, "ID 不能为空。");
        }
        parseId(field, text);
        return text;
    }

    private static String requiredMoment(String field, String value) {
        String text = blankToNull(value);
        if (text == null) {
            throw new InvalidRequestException(field, "时间不能为空。");
        }
        MaintenanceTimes.parseMoment(field, text);
        return text;
    }

    private static OffsetDateTime parseMomentOrNull(String field, String text) {
        return text == null ? null : MaintenanceTimes.parseMoment(field, text);
    }

    static long parseId(String field, String value) {
        String text = blankToNull(value);
        if (text == null) {
            throw new InvalidRequestException(field, "ID 不能为空。");
        }
        if (!text.chars().allMatch(Character::isDigit)) {
            throw new InvalidRequestException(field, "ID 必须是十进制数字字符串。");
        }
        long id = Long.parseLong(text);
        if (id <= 0) {
            throw new InvalidRequestException(field, "ID 必须大于 0。");
        }
        return id;
    }

    /** 附件 ID 列表按去重后的顺序解析，重复项只处理一次。 */
    private static List<Long> parseIds(String field, List<String> values) {
        if (values == null || values.isEmpty()) {
            return List.of();
        }
        List<Long> ids = new ArrayList<>(new LinkedHashSet<>(
                values.stream().map(value -> parseId(field, value)).toList()));
        return ids;
    }

    private static String blankToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
