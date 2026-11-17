package cn.copper.phase2.production;

import cn.copper.phase2.common.BusinessException;
import cn.copper.phase2.common.ErrorCode;
import cn.copper.phase2.common.IdempotencyService;
import cn.copper.phase2.common.Json;
import cn.copper.phase2.common.ChangeLogModels.ChangeLogWrite;
import cn.copper.phase2.common.ChangeLogRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Date;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 生产记录命令侧：S2-R05–R17。全部命令经统一幂等执行器；
 * 事实、库存流水、预留、归属与日状态失效在同一事务提交。
 */
@Service
public class ProductionCommandService {

    private final JdbcTemplate jdbc;
    private final ProductionRepository repo;
    private final StockService stock;
    private final IdempotencyService idempotency;
    private final ChangeLogRepository changeLogs;
    private final ProductionQueryService queries;

    public ProductionCommandService(JdbcTemplate jdbc, ProductionRepository repo, StockService stock,
                                    IdempotencyService idempotency, ChangeLogRepository changeLogs,
                                    ProductionQueryService queries) {
        this.jdbc = jdbc;
        this.repo = repo;
        this.stock = stock;
        this.idempotency = idempotency;
        this.changeLogs = changeLogs;
        this.queries = queries;
    }

    // ---------------------------------------------------------------- 公共校验

    private void requireNotFuture(LocalDate actualDate) {
        if (actualDate.isAfter(LocalDate.now(ProductionTypes.ZONE))) {
            throw BusinessException.invalid("actualDate", "尚未发生的实际不能登记。");
        }
    }

    private void requireOperator(String operatorName) {
        if (operatorName == null || operatorName.isBlank()) {
            throw BusinessException.invalid("operatorName", "实际经办人为必填项。");
        }
    }

    private void requireReason(String reason, String what) {
        if (reason == null || reason.isBlank()) {
            throw BusinessException.invalid("reason", what + "必须填写原因。");
        }
    }

    private void requireRequestId(String requestId) {
        if (requestId == null || requestId.isBlank()) {
            throw BusinessException.invalid("requestId", "请求编号为必填项。");
        }
    }

    private String toJson(Object value) {
        if (value == null) {
            return null;
        }
        try {
            return Json.MAPPER.writeValueAsString(value);
        } catch (JsonProcessingException error) {
            throw new IllegalStateException("引用集合无法序列化。");
        }
    }

    // ---------------------------------------------------------------- R05/R06 实际起止

    public Map<String, Object> recordExecution(Map<String, Object> body) {
        requireRequestId(str(body, "requestId"));
        requireOperator(str(body, "operatorName"));
        return idempotency.execute(str(body, "requestId"), "PRODUCTION_EXECUTION_RECORD", body,
                Map.class, () -> recordExecutionTx(body));
    }

    private Map<String, Object> recordExecutionTx(Map<String, Object> body) {
        long taskId = requireId(body, "taskId");
        ProductionRepository.TaskRow task = repo.lockTask(taskId);
        if (task == null) {
            throw BusinessException.notFound("任务不存在或已失效。");
        }
        stock.checkVersion(requireVersion(body, "expectedVersion"), task.version(), "任务版本");
        LocalDate actualDate = ProductionTypes.parseDate("actualDate", str(body, "actualDate"), true);
        requireNotFuture(actualDate);
        LocalDateTime startedAt = ProductionTypes.toDbTime(
                ProductionTypes.parseTime("startedAt", str(body, "startedAt"), false));
        LocalDateTime stoppedAt = ProductionTypes.toDbTime(
                ProductionTypes.parseTime("stoppedAt", str(body, "stoppedAt"), false));
        if (startedAt == null && stoppedAt == null) {
            throw BusinessException.invalid("startedAt", "起止时间至少登记一项。");
        }
        if (startedAt != null && stoppedAt != null && stoppedAt.isBefore(startedAt)) {
            throw BusinessException.invalid("stoppedAt", "结束时间不得早于开始时间。");
        }
        if ("COMPLETED".equals(task.status())) {
            throw new BusinessException(ErrorCode.STATE_CONFLICT, "任务已登记结束，不能再补充执行片段。");
        }
        jdbc.update("""
                INSERT INTO p2_output_record
                    (record_type, workshop_id, task_id, actual_date, started_at, stopped_at,
                     reason, operator_name)
                VALUES ('EXECUTION', ?, ?, ?, ?, ?, ?, ?)
                """, task.workshopId(), taskId, Date.valueOf(actualDate), startedAt, stoppedAt,
                str(body, "notes"), str(body, "operatorName"));
        long recordId = jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
        repo.bumpTaskVersion(taskId);
        repo.bumpDayRevision(task.workshopId(), actualDate);
        return executionResult(recordId, task.workshopId());
    }

    private Map<String, Object> executionResult(long recordId, long workshopId) {
        ProductionRepository.OutputRow row = repo.findOutput(recordId);
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("recordId", ProductionTypes.id(recordId));
        data.put("version", row.version());
        data.put("actualDate", row.actualDate().toString());
        data.put("startedAt", ProductionTypes.time(row.startedAt()));
        data.put("stoppedAt", ProductionTypes.time(row.stoppedAt()));
        data.put("workshopId", ProductionTypes.id(workshopId));
        return data;
    }

    public Map<String, Object> correctExecution(long recordId, Map<String, Object> body) {
        requireRequestId(str(body, "requestId"));
        requireOperator(str(body, "operatorName"));
        return idempotency.execute(str(body, "requestId"), "PRODUCTION_EXECUTION_CORRECTION", body,
                Map.class, () -> correctExecutionTx(recordId, body));
    }

    private Map<String, Object> correctExecutionTx(long recordId, Map<String, Object> body) {
        ProductionRepository.OutputRow row = repo.lockOutput(recordId);
        if (row == null || !ProductionTypes.ROW_EXECUTION.equals(row.recordType())) {
            throw BusinessException.notFound("执行记录不存在。");
        }
        stock.checkVersion(requireVersion(body, "expectedVersion"), row.version(), "执行记录版本");
        requireReason(str(body, "reason"), "更正执行记录");
        LocalDateTime startedAt = row.startedAt();
        LocalDateTime stoppedAt = row.stoppedAt();
        if (body.containsKey("startedAt")) {
            startedAt = ProductionTypes.toDbTime(
                    ProductionTypes.parseTime("startedAt", str(body, "startedAt"), false));
        }
        if (body.containsKey("stoppedAt")) {
            stoppedAt = ProductionTypes.toDbTime(
                    ProductionTypes.parseTime("stoppedAt", str(body, "stoppedAt"), false));
        }
        if (startedAt != null && stoppedAt != null && stoppedAt.isBefore(startedAt)) {
            throw BusinessException.invalid("stoppedAt", "结束时间不得早于开始时间。");
        }
        LocalDate newDate = row.actualDate();
        if (body.containsKey("actualDate")) {
            newDate = ProductionTypes.parseDate("actualDate", str(body, "actualDate"), true);
            requireNotFuture(newDate);
        }
        jdbc.update("""
                UPDATE p2_output_record SET actual_date = ?, started_at = ?, stopped_at = ?,
                    version = version + 1 WHERE output_id = ?
                """, Date.valueOf(newDate), startedAt, stoppedAt, recordId);
        changeLogs.insert(new ChangeLogWrite("P2_OUTPUT_RECORD", String.valueOf(recordId), "UPDATE",
                snapshotOf(row), snapshotOf(repo.findOutput(recordId)), str(body, "reason"),
                str(body, "operatorName"), (int) row.version(), (int) (row.version() + 1),
                str(body, "requestId")));
        if (!newDate.equals(row.actualDate())) {
            repo.bumpDayRevision(row.workshopId(), row.actualDate());
            repo.bumpDayRevision(row.workshopId(), newDate);
        } else {
            repo.bumpDayRevision(row.workshopId(), newDate);
        }
        if (row.taskId() != null) {
            repo.bumpTaskVersion(row.taskId());
        }
        bumpGroupIfPresent(row.groupId());
        return executionResult(recordId, row.workshopId());
    }

    // ---------------------------------------------------------------- R07/R08 铜板投料

    public Map<String, Object> recordPlateInput(long taskId, Map<String, Object> body) {
        requireRequestId(str(body, "requestId"));
        requireOperator(str(body, "operatorName"));
        return idempotency.execute(str(body, "requestId"), "PRODUCTION_PLATE_INPUT", body,
                Map.class, () -> recordPlateInputTx(taskId, body));
    }

    private Map<String, Object> recordPlateInputTx(long taskId, Map<String, Object> body) {
        ProductionRepository.TaskRow task = repo.lockTask(taskId);
        if (task == null) {
            throw BusinessException.notFound("任务不存在或已失效。");
        }
        stock.checkVersion(requireVersion(body, "expectedVersion"), task.version(), "任务版本");
        if (task.workshopId() != queries.workshopIdByCode("WS-ROD")) {
            throw new BusinessException(ErrorCode.STATE_CONFLICT, "铜板投料只属于车间一（铜杆生产）。");
        }
        if ("COMPLETED".equals(task.status())) {
            throw new BusinessException(ErrorCode.STATE_CONFLICT, "任务已登记结束，不能再登记投料。");
        }
        LocalDate actualDate = ProductionTypes.parseDate("actualDate", str(body, "actualDate"), true);
        requireNotFuture(actualDate);
        BigDecimal inputTon = ProductionTypes.parseTon("inputQuantityTon",
                str(body, "inputQuantityTon"), true);
        if (inputTon.compareTo(BigDecimal.ZERO) <= 0) {
            throw BusinessException.invalid("inputQuantityTon", "本次投入量必须大于零。");
        }
        BigDecimal amountYuan = ProductionTypes.parseYuan("materialAmountYuan",
                str(body, "materialAmountYuan"), false);
        requireOperator(str(body, "operatorName"));

        long plateMaterial = queries.plateMaterialId();
        // 先过账流水（来源键=请求编号防重复），再落事实行。
        StockService.MovementResult movement = stock.postMovement(plateMaterial, "PRODUCTION_ISSUE",
                null, taskId, "PLATE_INPUT", String.valueOf(str(body, "requestId")), 1,
                str(body, "requestId"), actualDate, inputTon.negate(), str(body, "operatorName"),
                null, null);
        jdbc.update("""
                INSERT INTO p2_plate_input
                    (task_id, material_id, movement_id, actual_date, input_quantity_ton,
                     material_amount_yuan, operator_name)
                VALUES (?, ?, ?, ?, ?, ?, ?)
                """, taskId, plateMaterial, movement.movementId(), Date.valueOf(actualDate),
                inputTon, amountYuan, str(body, "operatorName"));
        long inputId = jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);

        // 核销本任务的铜板预留（按需求键匹配，不足部分保持缺项）。
        BigDecimal consumed = BigDecimal.ZERO;
        for (StockService.ReservationRow reservation : stock.lockReservationsByTask(taskId)) {
            if (reservation.materialId() != plateMaterial) {
                continue;
            }
            BigDecimal remaining = inputTon.subtract(consumed);
            if (remaining.compareTo(BigDecimal.ZERO) <= 0) {
                break;
            }
            consumed = consumed.add(stock.consumeReservation(reservation, remaining));
        }

        // 可随真实事实同时登记已经发生的实际开始。
        Long executionRecordId = null;
        if (body.get("execution") instanceof Map<?, ?> execution) {
            @SuppressWarnings("unchecked")
            Map<String, Object> exec = (Map<String, Object>) execution;
            executionRecordId = insertExecutionFragment(task, exec);
        }

        repo.bumpTaskVersion(taskId);
        repo.bumpDayRevision(task.workshopId(), actualDate);
        return plateInputResult(inputId, movement.movementId(), consumed, executionRecordId);
    }

    private long insertExecutionFragment(ProductionRepository.TaskRow task, Map<String, Object> exec) {
        LocalDateTime startedAt = ProductionTypes.toDbTime(
                ProductionTypes.parseTime("startedAt", str(exec, "startedAt"), false));
        LocalDateTime stoppedAt = ProductionTypes.toDbTime(
                ProductionTypes.parseTime("stoppedAt", str(exec, "stoppedAt"), false));
        if (startedAt == null && stoppedAt == null) {
            throw BusinessException.invalid("execution.startedAt", "随附执行片段至少要有起或止时间。");
        }
        jdbc.update("""
                INSERT INTO p2_output_record
                    (record_type, workshop_id, task_id, actual_date, started_at, stopped_at,
                     reason, operator_name)
                VALUES ('EXECUTION', ?, ?, ?, ?, ?, ?, ?)
                """, task.workshopId(), task.taskId(),
                Date.valueOf(task.productionDate()), startedAt, stoppedAt, str(exec, "notes"),
                str(exec, "operatorName") == null ? str(exec, "operatorName") : str(exec, "operatorName"));
        return jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
    }

    private Map<String, Object> plateInputResult(long inputId, long movementId, BigDecimal consumed,
                                                 Long executionRecordId) {
        ProductionRepository.PlateInputRow row = repo.findPlateInput(inputId);
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("source", ProductionTypes.SOURCE_PLANNED);
        data.put("recordType", ProductionTypes.RT_PLATE_INPUT);
        data.put("inputId", ProductionTypes.id(inputId));
        data.put("recordId", ProductionTypes.id(inputId));
        data.put("version", row.version());
        data.put("inputQuantityTon", ProductionTypes.ton(row.inputTon()));
        data.put("movementId", ProductionTypes.id(movementId));
        data.put("stockDeductedTon", ProductionTypes.ton(row.inputTon()));
        data.put("reservationConsumedTon", ProductionTypes.ton(consumed));
        data.put("accountingState", row.materialAmountYuan() == null ? "PENDING" : "READY");
        List<String> missing = new ArrayList<>();
        if (row.materialAmountYuan() == null) {
            missing.add("materialAmountYuan");
        }
        data.put("missingItems", missing);
        if (executionRecordId != null) {
            data.put("executionRecordId", ProductionTypes.id(executionRecordId));
        }
        return data;
    }

    public Map<String, Object> correctPlateInput(long inputId, Map<String, Object> body) {
        requireRequestId(str(body, "requestId"));
        requireOperator(str(body, "operatorName"));
        return idempotency.execute(str(body, "requestId"), "PRODUCTION_PLATE_INPUT_CORRECTION", body,
                Map.class, () -> correctPlateInputTx(inputId, body));
    }

    private Map<String, Object> correctPlateInputTx(long inputId, Map<String, Object> body) {
        ProductionRepository.PlateInputRow row = repo.lockPlateInput(inputId);
        if (row == null) {
            throw BusinessException.notFound("投料记录不存在。");
        }
        stock.checkVersion(requireVersion(body, "expectedVersion"), row.version(), "投料记录版本");
        requireReason(str(body, "reason"), "更正投料记录");

        // 仅补材料金额：无数量流水。
        if (body.containsKey("materialAmountYuan") && !body.containsKey("inputQuantityTon")) {
            BigDecimal amount = ProductionTypes.parseYuan("materialAmountYuan",
                    str(body, "materialAmountYuan"), false);
            jdbc.update("UPDATE p2_plate_input SET material_amount_yuan = ?, version = version + 1 "
                    + "WHERE input_id = ?", amount, inputId);
            changeLogs.insert(new ChangeLogWrite("P2_PLATE_INPUT", String.valueOf(inputId), "UPDATE",
                    Map.of("materialAmountYuan", ProductionTypes.yuan(row.materialAmountYuan())),
                    Map.of("materialAmountYuan", ProductionTypes.yuan(amount)), str(body, "reason"),
                    str(body, "operatorName"), (int) row.version(), (int) (row.version() + 1),
                    str(body, "requestId")));
            bumpDayForPlate(row);
            return plateInputResult(inputId, row.movementId(), BigDecimal.ZERO, null);
        }

        BigDecimal newTon = ProductionTypes.parseTon("inputQuantityTon",
                str(body, "inputQuantityTon"), true);
        if (newTon.compareTo(BigDecimal.ZERO) <= 0) {
            throw BusinessException.invalid("inputQuantityTon", "更正后的投入量必须大于零。");
        }
        BigDecimal delta = newTon.subtract(row.inputTon());
        long correctsMovementId = row.movementId();
        jdbc.update("UPDATE p2_plate_input SET input_quantity_ton = ?, version = version + 1 "
                + "WHERE input_id = ?", newTon, inputId);
        if (delta.compareTo(BigDecimal.ZERO) != 0) {
            LocalDate movementDate = row.actualDate();
            if (body.containsKey("actualDate")) {
                movementDate = ProductionTypes.parseDate("actualDate", str(body, "actualDate"), true);
                requireNotFuture(movementDate);
            }
            stock.postMovement(row.materialId(), "CORRECTION", null, row.taskId(),
                    "PLATE_INPUT_CORRECTION", String.valueOf(str(body, "requestId")), 1,
                    str(body, "requestId"), movementDate, delta.negate(), str(body, "operatorName"),
                    str(body, "reason"), correctsMovementId);
        }
        if (body.containsKey("materialAmountYuan")) {
            jdbc.update("UPDATE p2_plate_input SET material_amount_yuan = ? WHERE input_id = ?",
                    ProductionTypes.parseYuan("materialAmountYuan", str(body, "materialAmountYuan"),
                            false), inputId);
        }
        changeLogs.insert(new ChangeLogWrite("P2_PLATE_INPUT", String.valueOf(inputId), "UPDATE",
                snapshotOf(row), snapshotOf(repo.findPlateInput(inputId)), str(body, "reason"),
                str(body, "operatorName"), (int) row.version(), (int) (row.version() + 1),
                str(body, "requestId")));
        bumpDayForPlate(row);
        if (row.taskId() != null) {
            repo.bumpTaskVersion(row.taskId());
        }
        return plateInputResult(inputId, row.movementId(), BigDecimal.ZERO, null);
    }

    private void bumpDayForPlate(ProductionRepository.PlateInputRow row) {
        long workshopId = resolvePlateWorkshop(row);
        repo.bumpDayRevision(workshopId, row.actualDate());
    }

    private long resolvePlateWorkshop(ProductionRepository.PlateInputRow row) {
        if (row.taskId() != null) {
            ProductionRepository.TaskRow task = repo.findTask(row.taskId());
            if (task != null) {
                return task.workshopId();
            }
        }
        if (row.groupKey() != null) {
            Long groupId = jdbc.queryForObject("""
                    SELECT production_group_id FROM p2_production_group
                    WHERE production_group_id = (SELECT MIN(production_group_id) FROM p2_production_group)
                    """, Long.class);
            // 组键场景沿事实行上的组外键；此处兜底为车间一。
            ProductionRepository.GroupRow group = row.groupKey() != null && groupId != null
                    ? repo.findGroup(groupId) : null;
            if (group != null) {
                return group.workshopId();
            }
        }
        return queries.workshopIdByCode("WS-ROD");
    }

    // ---------------------------------------------------------------- R09 铜杆称重

    public Map<String, Object> recordRodOutput(long taskId, Map<String, Object> body) {
        requireRequestId(str(body, "requestId"));
        requireOperator(str(body, "operatorName"));
        return idempotency.execute(str(body, "requestId"), "PRODUCTION_ROD_OUTPUT", body,
                Map.class, () -> recordRodOutputTx(taskId, body));
    }

    private Map<String, Object> recordRodOutputTx(long taskId, Map<String, Object> body) {
        ProductionRepository.TaskRow task = repo.lockTask(taskId);
        if (task == null) {
            throw BusinessException.notFound("任务不存在或已失效。");
        }
        stock.checkVersion(requireVersion(body, "expectedVersion"), task.version(), "任务版本");
        if (task.workshopId() != queries.workshopIdByCode("WS-ROD")) {
            throw new BusinessException(ErrorCode.STATE_CONFLICT, "Φ8 铜杆称重只属于车间一。");
        }
        LocalDate actualDate = ProductionTypes.parseDate("actualDate", str(body, "actualDate"), true);
        requireNotFuture(actualDate);
        BigDecimal qualified = ProductionTypes.parseTon("qualifiedQuantityTon",
                str(body, "qualifiedQuantityTon"), true);
        if (qualified.compareTo(BigDecimal.ZERO) <= 0) {
            throw BusinessException.invalid("qualifiedQuantityTon", "新增称重必须大于零。");
        }
        long rodMaterial = queries.rodMaterialId();

        List<Map<String, Object>> inputRefs = new ArrayList<>();
        if (body.get("inputIds") instanceof List<?> inputIds) {
            for (Object raw : inputIds) {
                long inputId = Long.parseLong(String.valueOf(raw));
                ProductionRepository.PlateInputRow input = repo.findPlateInput(inputId);
                if (input == null || !Long.valueOf(taskId).equals(input.taskId())) {
                    throw BusinessException.invalid("inputIds", "投料依据必须属于本任务。");
                }
                Map<String, Object> ref = new LinkedHashMap<>();
                ref.put("inputId", inputId);
                ref.put("inputQuantityTon", ProductionTypes.ton(input.inputTon()));
                inputRefs.add(ref);
            }
        }

        StockService.MovementResult movement = stock.postMovement(rodMaterial, "PRODUCTION_RECEIPT",
                null, taskId, "ROD_OUTPUT", String.valueOf(str(body, "requestId")), 1,
                str(body, "requestId"), actualDate, qualified, str(body, "operatorName"), null, null);
        jdbc.update("""
                INSERT INTO p2_output_record
                    (record_type, workshop_id, task_id, material_id, movement_id, actual_date,
                     qualified_quantity_ton, operator_name)
                VALUES ('QUALIFIED_OUTPUT', ?, ?, ?, ?, ?, ?, ?)
                """, task.workshopId(), taskId, rodMaterial, movement.movementId(),
                Date.valueOf(actualDate), qualified, str(body, "operatorName"));
        long outputId = jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
        jdbc.update("UPDATE p2_output_record SET input_references = CAST(? AS JSON) "
                + "WHERE output_id = ?", toJson(inputRefs), outputId);

        // 供料承诺兑现：本任务产出兑现指向本任务的承诺。
        BigDecimal fulfilled = BigDecimal.ZERO;
        for (StockService.CommitmentRow commitment : stock.lockCommitmentsBySourceTask(taskId)) {
            fulfilled = fulfilled.add(stock.fulfillCommitment(commitment, qualified.subtract(fulfilled)));
            if (fulfilled.compareTo(qualified) >= 0) {
                break;
            }
        }

        Long executionRecordId = null;
        if (body.get("execution") instanceof Map<?, ?> execution) {
            @SuppressWarnings("unchecked")
            Map<String, Object> exec = (Map<String, Object>) execution;
            executionRecordId = insertExecutionFragment(task, exec);
        }

        repo.bumpTaskVersion(taskId);
        repo.bumpDayRevision(task.workshopId(), actualDate);
        return rodOutputResult(outputId, task, qualified, movement.movementId(), fulfilled,
                executionRecordId);
    }

    private Map<String, Object> rodOutputResult(long outputId, ProductionRepository.TaskRow task,
                                                BigDecimal qualified, long movementId,
                                                BigDecimal fulfilled, Long executionRecordId) {
        BigDecimal cumulative = repo.taskQualifiedTon(task.taskId());
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("source", ProductionTypes.SOURCE_PLANNED);
        data.put("recordType", ProductionTypes.RT_ROD_OUTPUT);
        data.put("outputId", ProductionTypes.id(outputId));
        data.put("recordId", ProductionTypes.id(outputId));
        data.put("version", repo.findOutput(outputId).version());
        data.put("qualifiedQuantityTon", ProductionTypes.ton(qualified));
        data.put("movementId", ProductionTypes.id(movementId));
        data.put("taskCumulativeTon", ProductionTypes.ton(cumulative));
        data.put("varianceTon", ProductionTypes.ton(cumulative.subtract(task.plannedQualifiedTon())));
        data.put("supplyFulfilledTon", ProductionTypes.ton(fulfilled));
        data.put("accountingState", "PENDING");
        if (executionRecordId != null) {
            data.put("executionRecordId", ProductionTypes.id(executionRecordId));
        }
        return data;
    }

    // ---------------------------------------------------------------- R10/R11 领杆交接

    public Map<String, Object> recordHandover(long taskId, Map<String, Object> body) {
        requireRequestId(str(body, "requestId"));
        requireOperator(str(body, "operatorName"));
        return idempotency.execute(str(body, "requestId"), "PRODUCTION_ROD_HANDOVER", body,
                Map.class, () -> recordHandoverTx(taskId, body));
    }

    private Map<String, Object> recordHandoverTx(long taskId, Map<String, Object> body) {
        ProductionRepository.TaskRow task = repo.lockTask(taskId);
        if (task == null) {
            throw BusinessException.notFound("任务不存在或已失效。");
        }
        stock.checkVersion(requireVersion(body, "expectedVersion"), task.version(), "任务版本");
        if (task.workshopId() != queries.workshopIdByCode("WS-WIRE")) {
            throw new BusinessException(ErrorCode.STATE_CONFLICT, "领杆交出只属于车间二（铜丝拉制）。");
        }
        if ("COMPLETED".equals(task.status())) {
            throw new BusinessException(ErrorCode.STATE_CONFLICT, "任务已登记结束，不能再登记交接。");
        }
        LocalDate actualDate = ProductionTypes.parseDate("actualDate", str(body, "actualDate"), true);
        requireNotFuture(actualDate);
        BigDecimal sentTon = ProductionTypes.parseTon("sentQuantityTon", str(body, "sentQuantityTon"),
                true);
        if (sentTon.compareTo(BigDecimal.ZERO) <= 0) {
            throw BusinessException.invalid("sentQuantityTon", "实际交出量必须大于零。");
        }
        LocalDateTime sentAt = ProductionTypes.toDbTime(
                ProductionTypes.parseTime("sentAt", str(body, "sentAt"), true));
        BigDecimal receivedTon = ProductionTypes.parseTon("receivedQuantityTon",
                str(body, "receivedQuantityTon"), false);
        LocalDateTime receivedAt = ProductionTypes.toDbTime(
                ProductionTypes.parseTime("receivedAt", str(body, "receivedAt"), false));
        validateReceivedPair(receivedTon, receivedAt, sentAt);

        Long orderId = optionalId(body, "orderId");
        if (orderId != null && repo.findOrder(orderId) == null) {
            throw BusinessException.notFound("关联订单不存在。");
        }

        long rodMaterial = queries.rodMaterialId();
        // 铜杆按交出量扣库一次，并核销本任务适用的杆预留。
        StockService.MovementResult movement = stock.postMovement(rodMaterial, "PRODUCTION_ISSUE",
                orderId, taskId, "ROD_HANDOVER", String.valueOf(str(body, "requestId")), 1,
                str(body, "requestId"), actualDate, sentTon.negate(), str(body, "operatorName"),
                null, null);
        BigDecimal consumed = BigDecimal.ZERO;
        for (StockService.ReservationRow reservation : stock.lockReservationsByTask(taskId)) {
            if (reservation.materialId() != rodMaterial) {
                continue;
            }
            BigDecimal remaining = sentTon.subtract(consumed);
            if (remaining.compareTo(BigDecimal.ZERO) <= 0) {
                break;
            }
            consumed = consumed.add(stock.consumeReservation(reservation, remaining));
        }

        ReleaseDecision release = checkRelease(sentTon, receivedTon, body, str(body, "operatorName"));
        jdbc.update("""
                INSERT INTO p2_rod_handover
                    (task_id, order_id, movement_id, sent_quantity_ton, received_quantity_ton,
                     sent_at, received_at, release_reason, released_by, released_at, operator_name)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, taskId, orderId, movement.movementId(), sentTon, receivedTon, sentAt, receivedAt,
                release.reason(), release.releasedBy(), release.releasedAt(), str(body, "operatorName"));
        long handoverId = jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
        repo.bumpTaskVersion(taskId);
        repo.bumpDayRevision(task.workshopId(), actualDate);
        return handoverResult(handoverId, movement.movementId(), sentTon, consumed);
    }

    private void validateReceivedPair(BigDecimal receivedTon, LocalDateTime receivedAt,
                                      LocalDateTime sentAt) {
        boolean hasQty = receivedTon != null;
        boolean hasTime = receivedAt != null;
        if (hasQty != hasTime) {
            throw BusinessException.invalid("receivedQuantityTon", "实际接收数量和接收时刻必须成对出现。");
        }
        if (hasTime && sentAt != null && receivedAt.isBefore(sentAt)) {
            throw BusinessException.invalid("receivedAt", "接收时刻不得早于交出。");
        }
    }

    private record ReleaseDecision(String reason, String releasedBy, LocalDateTime releasedAt) {
        static final ReleaseDecision NONE = new ReleaseDecision(null, null, null);
    }

    private ReleaseDecision checkRelease(BigDecimal sentTon, BigDecimal receivedTon,
                                         Map<String, Object> body, String operatorName) {
        if (receivedTon == null) {
            return ReleaseDecision.NONE;
        }
        BigDecimal error = receivedTon.subtract(sentTon).abs();
        boolean overTolerance = error.multiply(BigDecimal.valueOf(100))
                .compareTo(sentTon.multiply(BigDecimal.valueOf(5))) > 0;
        if (!overTolerance) {
            return ReleaseDecision.NONE;
        }
        boolean confirmed = Boolean.TRUE.equals(body.get("releaseConfirmed"));
        String reason = str(body, "releaseReason");
        if (!confirmed || reason == null || reason.isBlank()) {
            throw new BusinessException(ErrorCode.HANDOVER_REASON_REQUIRED,
                    "交接误差超过 5%，需要确认放行并填写原因。",
                    Map.of("errorRate", errorRateString(sentTon, receivedTon)));
        }
        return new ReleaseDecision(reason, operatorName, StockService.now());
    }

    private String errorRateString(BigDecimal sentTon, BigDecimal receivedTon) {
        if (sentTon == null || receivedTon == null || sentTon.compareTo(BigDecimal.ZERO) == 0) {
            return null;
        }
        return receivedTon.subtract(sentTon).abs()
                .divide(sentTon, 6, RoundingMode.HALF_UP).toPlainString();
    }

    private Map<String, Object> handoverResult(long handoverId, long movementId, BigDecimal sentTon,
                                               BigDecimal consumed) {
        ProductionRepository.HandoverRow row = repo.findHandover(handoverId);
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("handoverId", ProductionTypes.id(handoverId));
        data.put("version", row.version());
        data.put("taskId", ProductionTypes.id(row.taskId()));
        data.put("sentQuantityTon", ProductionTypes.ton(sentTon));
        data.put("receivedQuantityTon", ProductionTypes.ton(row.receivedTon()));
        data.put("errorRate", errorRateString(row.sentTon(), row.receivedTon()));
        data.put("handoverState", row.receivedTon() == null
                ? ProductionTypes.HANDOVER_PENDING_RECEIPT
                : (row.releaseReason() == null ? ProductionTypes.HANDOVER_CONFIRMED
                : ProductionTypes.HANDOVER_RELEASED));
        data.put("movementId", ProductionTypes.id(movementId));
        data.put("stockDeductedTon", ProductionTypes.ton(sentTon));
        data.put("reservationConsumedTon", ProductionTypes.ton(consumed));
        data.put("accountingState", "PENDING");
        List<String> missing = new ArrayList<>();
        if (row.receivedTon() == null) {
            missing.add("receivedQuantityTon");
            missing.add("receivedAt");
        }
        missing.add("rodUnitCost");
        data.put("missingItems", missing);
        if (row.taskId() != null) {
            ProductionRepository.TaskRow task = repo.findTask(row.taskId());
            if (task != null) {
                data.put("taskVersion", task.version());
            }
        }
        return data;
    }

    public Map<String, Object> correctHandover(long handoverId, Map<String, Object> body) {
        requireRequestId(str(body, "requestId"));
        requireOperator(str(body, "operatorName"));
        return idempotency.execute(str(body, "requestId"), "PRODUCTION_ROD_HANDOVER_CORRECTION", body,
                Map.class, () -> correctHandoverTx(handoverId, body));
    }

    private Map<String, Object> correctHandoverTx(long handoverId, Map<String, Object> body) {
        ProductionRepository.HandoverRow row = repo.lockHandover(handoverId);
        if (row == null) {
            throw BusinessException.notFound("交接记录不存在。");
        }
        stock.checkVersion(requireVersion(body, "expectedVersion"), row.version(), "交接记录版本");
        requireReason(str(body, "reason"), "更正交接记录");

        BigDecimal newSent = row.sentTon();
        if (body.containsKey("sentQuantityTon")) {
            newSent = ProductionTypes.parseTon("sentQuantityTon", str(body, "sentQuantityTon"), true);
            if (newSent.compareTo(BigDecimal.ZERO) <= 0) {
                throw BusinessException.invalid("sentQuantityTon", "交出量必须大于零。");
            }
        }
        BigDecimal newReceived = row.receivedTon();
        LocalDateTime newReceivedAt = row.receivedAt();
        if (body.containsKey("receivedQuantityTon") || body.containsKey("receivedAt")) {
            newReceived = ProductionTypes.parseTon("receivedQuantityTon",
                    str(body, "receivedQuantityTon"), false);
            newReceivedAt = ProductionTypes.toDbTime(
                    ProductionTypes.parseTime("receivedAt", str(body, "receivedAt"), false));
            validateReceivedPair(newReceived, newReceivedAt, row.sentAt());
        }

        // 已被铜丝产出引用的交出份额不得超过新交出量。
        BigDecimal referenced = repo.referencedRodUseTon(handoverId);
        if (referenced.compareTo(newSent) > 0) {
            throw new BusinessException(ErrorCode.STATE_CONFLICT,
                    "新交出量低于已被产出引用的份额，请先更正相关产出。",
                    Map.of("conflicts", List.of(Map.of("recordType", "ROD_HANDOVER",
                            "referencedTon", ProductionTypes.ton(referenced)))));
        }

        ReleaseDecision release = checkRelease(newSent, newReceived, body, str(body, "operatorName"));

        // 只改交出量时按新旧差量修正库存（一次净扣差额），引用原流水。
        if (newSent.compareTo(row.sentTon()) != 0) {
            BigDecimal delta = newSent.subtract(row.sentTon());
            stock.postMovement(queries.rodMaterialId(), "CORRECTION", row.orderId(), row.taskId(),
                    "ROD_HANDOVER_CORRECTION", String.valueOf(str(body, "requestId")), 1,
                    str(body, "requestId"),
                    row.actualDate() == null ? LocalDate.now(ProductionTypes.ZONE) : row.actualDate(),
                    delta.negate(), str(body, "operatorName"), str(body, "reason"), row.movementId());
        }

        jdbc.update("""
                UPDATE p2_rod_handover SET sent_quantity_ton = ?, received_quantity_ton = ?,
                    received_at = ?, release_reason = ?, released_by = ?, released_at = ?,
                    version = version + 1 WHERE handover_id = ?
                """, newSent, newReceived, newReceivedAt, release.reason(), release.releasedBy(),
                release.releasedAt(), handoverId);
        changeLogs.insert(new ChangeLogWrite("P2_ROD_HANDOVER", String.valueOf(handoverId), "UPDATE",
                snapshotOf(row), snapshotOf(repo.findHandover(handoverId)), str(body, "reason"),
                str(body, "operatorName"), (int) row.version(), (int) (row.version() + 1),
                str(body, "requestId")));
        if (row.taskId() != null) {
            repo.bumpTaskVersion(row.taskId());
        }
        bumpGroupIfPresent(row.groupId());
        return handoverResult(handoverId, row.movementId(), newSent, BigDecimal.ZERO);
    }

    // ---------------------------------------------------------------- R12 铜丝称重

    public Map<String, Object> recordWireOutput(Map<String, Object> body) {
        requireRequestId(str(body, "requestId"));
        requireOperator(str(body, "operatorName"));
        return idempotency.execute(str(body, "requestId"), "PRODUCTION_WIRE_OUTPUT", body,
                Map.class, () -> recordWireOutputTx(body));
    }

    private Map<String, Object> recordWireOutputTx(Map<String, Object> body) {
        List<Long> taskIds = requireIdList(body, "taskIds");
        if (taskIds.isEmpty()) {
            throw BusinessException.invalid("taskIds", "铜丝称重必须关联至少一个计划任务。");
        }
        long wireWorkshop = queries.workshopIdByCode("WS-WIRE");
        List<ProductionRepository.TaskRow> tasks = new ArrayList<>();
        for (long taskId : taskIds) {
            ProductionRepository.TaskRow task = repo.lockTask(taskId);
            if (task == null) {
                throw BusinessException.notFound("任务 " + taskId + " 不存在或已失效。");
            }
            if (task.workshopId() != wireWorkshop) {
                throw new BusinessException(ErrorCode.STATE_CONFLICT, "铜丝称重只属于车间二。");
            }
            if ("COMPLETED".equals(task.status())) {
                throw new BusinessException(ErrorCode.STATE_CONFLICT,
                        "任务 " + taskId + " 已登记结束，不能再登记称重。");
            }
            tasks.add(task);
        }
        validateExpectedVersions(body, tasks);

        long materialId = requireId(body, "materialId");
        ProductionRepository.MaterialRow material = repo.findMaterial(materialId);
        if (material == null || !"FINISHED_WIRE".equals(material.type())) {
            throw BusinessException.invalid("materialId", "铜丝称重的物料必须是成品铜丝规格。");
        }
        LocalDate actualDate = ProductionTypes.parseDate("actualDate", str(body, "actualDate"), true);
        requireNotFuture(actualDate);
        BigDecimal qualified = ProductionTypes.parseTon("qualifiedQuantityTon",
                str(body, "qualifiedQuantityTon"), true);
        if (qualified.compareTo(BigDecimal.ZERO) <= 0) {
            throw BusinessException.invalid("qualifiedQuantityTon", "新增称重必须大于零。");
        }

        // 任务份额：已知份额之和不得超过总量；全部任务份额明确时必须相等。
        Map<Long, BigDecimal> shares = parseShares(body.get("taskShares"));
        boolean singleTask = tasks.size() == 1;
        if (!shares.isEmpty()) {
            BigDecimal shareSum = shares.values().stream().reduce(BigDecimal.ZERO, BigDecimal::add);
            if (shareSum.compareTo(qualified) > 0) {
                throw BusinessException.invalid("taskShares", "已知任务份额之和不得超过实测总量。");
            }
            if (shares.keySet().containsAll(taskIds) && shareSum.compareTo(qualified) != 0) {
                throw BusinessException.invalid("taskShares", "全部任务份额明确时合计必须等于实测总量。");
            }
        } else if (!singleTask) {
            throw BusinessException.invalid("taskShares", "多任务合并称重必须提供已知任务份额或留待补。");
        }

        // 执行依据：引用已有片段或随附停止时刻。
        String executionRefs = null;
        if (body.get("executionRecordId") != null) {
            long executionId = Long.parseLong(String.valueOf(body.get("executionRecordId")));
            ProductionRepository.OutputRow execution = repo.findOutput(executionId);
            if (execution == null || !ProductionTypes.ROW_EXECUTION.equals(execution.recordType())) {
                throw BusinessException.invalid("executionRecordId", "引用的执行片段不存在。");
            }
            Map<String, Object> ref = new LinkedHashMap<>();
            ref.put("executionRecordId", executionId);
            executionRefs = toJson(List.of(ref));
        }
        LocalDateTime stoppedAt = ProductionTypes.toDbTime(
                ProductionTypes.parseTime("stoppedAt", str(body, "stoppedAt"), false));

        // 领杆来源引用。
        List<Map<String, Object>> rodUseRefs = parseRodUseReferences(body.get("rodUseReferences"));
        Long singleHandoverId = autoDeriveRodUse(rodUseRefs, tasks);

        // 订单归属与版本。
        List<Map<String, Object>> allocationCommands = parseAllocations(body.get("allocations"));
        Map<Long, ProductionRepository.OrderRow> lockedOrders = lockAllocationOrders(body,
                allocationCommands);

        StockService.MovementResult movement = stock.postMovement(materialId, "PRODUCTION_RECEIPT",
                null, null, "WIRE_OUTPUT", String.valueOf(str(body, "requestId")), 1,
                str(body, "requestId"), actualDate, qualified, str(body, "operatorName"), null, null);
        jdbc.update("""
                INSERT INTO p2_output_record
                    (record_type, workshop_id, task_id, material_id, movement_id, actual_date,
                     stopped_at, qualified_quantity_ton, operator_name)
                VALUES ('QUALIFIED_OUTPUT', ?, ?, ?, ?, ?, ?, ?, ?)
                """, wireWorkshop, singleTask && shares.isEmpty() ? tasks.get(0).taskId() : null,
                materialId, movement.movementId(), Date.valueOf(actualDate), stoppedAt, qualified,
                str(body, "operatorName"));
        long outputId = jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);

        // 任务份额落表；单任务无份额时直接落在主任务列。
        for (Map.Entry<Long, BigDecimal> share : shares.entrySet()) {
            jdbc.update("""
                    INSERT INTO p2_output_task_share (output_id, task_id, qualified_quantity_ton)
                    VALUES (?, ?, ?)
                    """, outputId, share.getKey(), share.getValue());
        }

        // 订单归属：受订单可归属余量限制，超出部分转公共份额并返回调整说明。
        List<Map<String, Object>> adjustments = new ArrayList<>();
        List<Map<String, Object>> allocationResults = new ArrayList<>();
        BigDecimal allocatedTotal = BigDecimal.ZERO;
        for (Map<String, Object> command : allocationCommands) {
            long orderId = requireId(command, "orderId");
            ProductionRepository.OrderRow order = lockedOrders.get(orderId);
            if (order == null) {
                throw BusinessException.notFound("归属订单 " + orderId + " 不存在。");
            }
            if (order.wireMaterialId() != materialId) {
                throw BusinessException.invalid("allocations", "订单归属的规格必须与本次称重一致。");
            }
            BigDecimal requested = ProductionTypes.parseTon("quantityTon",
                    str(command, "quantityTon"), true);
            if (requested.compareTo(BigDecimal.ZERO) <= 0) {
                throw BusinessException.invalid("allocations", "归属数量必须大于零。");
            }
            BigDecimal remaining = repo.orderRemainingTon(order);
            BigDecimal applied = requested.min(remaining);
            if (applied.compareTo(BigDecimal.ZERO) > 0) {
                long taskIdForAllocation = optionalId(command, "taskId") == null
                        ? tasks.get(0).taskId() : optionalId(command, "taskId");
                jdbc.update("""
                        INSERT INTO p2_stock_reservation
                            (material_id, order_id, task_id, source_type, source_id, source_line_no,
                             reserved_quantity_ton)
                        VALUES (?, ?, ?, 'DIRECT_OUTPUT', ?, 1, ?)
                        """, materialId, orderId, taskIdForAllocation,
                        "WIRE_OUTPUT:" + outputId, applied);
                long reservationId = jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
                jdbc.update("""
                        INSERT INTO p2_finished_allocation
                            (order_id, material_id, output_id, reservation_id, source_type,
                             original_quantity_ton, allocated_quantity_ton, operator_name)
                        VALUES (?, ?, ?, ?, 'DIRECT_OUTPUT', ?, ?, ?)
                        """, orderId, materialId, outputId, reservationId, applied, applied,
                        str(body, "operatorName"));
                allocatedTotal = allocatedTotal.add(applied);
                Map<String, Object> result = new LinkedHashMap<>();
                result.put("orderId", ProductionTypes.id(orderId));
                result.put("taskId", ProductionTypes.id(taskIdForAllocation));
                result.put("allocationId", String.valueOf(jdbc.queryForObject(
                        "SELECT LAST_INSERT_ID()", Long.class)));
                result.put("reservationId", ProductionTypes.id(reservationId));
                result.put("quantityTon", ProductionTypes.ton(applied));
                allocationResults.add(result);
            }
            if (applied.compareTo(requested) < 0) {
                Map<String, Object> adjustment = new LinkedHashMap<>();
                adjustment.put("orderId", ProductionTypes.id(orderId));
                adjustment.put("code", "ORDER_REMAINING_LIMIT");
                adjustment.put("requestedQuantityTon", ProductionTypes.ton(requested));
                adjustment.put("allocatedQuantityTon", ProductionTypes.ton(applied));
                adjustments.add(adjustment);
            }
        }
        if (allocatedTotal.compareTo(qualified) > 0) {
            throw BusinessException.invalid("allocations", "归属总量超过实测总量。");
        }
        BigDecimal publicQuantity = qualified.subtract(allocatedTotal);

        if (!rodUseRefs.isEmpty() || singleHandoverId != null) {
            jdbc.update("UPDATE p2_output_record SET input_references = CAST(? AS JSON) "
                    + "WHERE output_id = ?", toJson(rodUseRefs), outputId);
        }
        jdbc.update("UPDATE p2_output_record SET movement_id = ?, execution_references = "
                + (executionRefs == null ? "NULL" : "CAST(? AS JSON)") + " WHERE output_id = ?",
                executionRefs == null
                        ? new Object[]{movement.movementId(), outputId}
                        : new Object[]{movement.movementId(), executionRefs, outputId});

        for (ProductionRepository.TaskRow task : tasks) {
            repo.bumpTaskVersion(task.taskId());
        }
        repo.bumpDayRevision(wireWorkshop, actualDate);
        return wireOutputResult(outputId, materialId, qualified, publicQuantity, movement.movementId(),
                rodUseRefs, allocationResults, adjustments);
    }

    private void validateExpectedVersions(Map<String, Object> body,
                                          List<ProductionRepository.TaskRow> tasks) {
        if (!(body.get("expectedVersions") instanceof Map<?, ?> expected)) {
            return;
        }
        if (expected.get("tasks") instanceof List<?> taskVersions) {
            Map<Long, Long> versions = new LinkedHashMap<>();
            for (Object raw : taskVersions) {
                if (raw instanceof Map<?, ?> entry) {
                    versions.put(Long.parseLong(String.valueOf(entry.get("id"))),
                            Long.parseLong(String.valueOf(entry.get("version"))));
                }
            }
            for (ProductionRepository.TaskRow task : tasks) {
                Long expectedVersion = versions.get(task.taskId());
                if (expectedVersion != null) {
                    stock.checkVersion(expectedVersion, task.version(), "任务版本");
                }
            }
        }
    }

    private Map<Long, ProductionRepository.OrderRow> lockAllocationOrders(
            Map<String, Object> body, List<Map<String, Object>> allocationCommands) {
        Map<Long, Long> expectedOrderVersions = new LinkedHashMap<>();
        if (body.get("expectedVersions") instanceof Map<?, ?> expected
                && expected.get("orders") instanceof List<?> orderVersions) {
            for (Object raw : orderVersions) {
                if (raw instanceof Map<?, ?> entry) {
                    expectedOrderVersions.put(Long.parseLong(String.valueOf(entry.get("id"))),
                            Long.parseLong(String.valueOf(entry.get("version"))));
                }
            }
        }
        Map<Long, ProductionRepository.OrderRow> orders = new LinkedHashMap<>();
        for (Map<String, Object> command : allocationCommands) {
            long orderId = requireId(command, "orderId");
            if (orders.containsKey(orderId)) {
                continue;
            }
            ProductionRepository.OrderRow order = repo.lockOrder(orderId);
            if (order == null) {
                throw BusinessException.notFound("归属订单 " + orderId + " 不存在。");
            }
            Long expectedVersion = expectedOrderVersions.get(orderId);
            if (expectedVersion != null) {
                stock.checkVersion(expectedVersion, order.version(), "订单版本");
            }
            orders.put(orderId, order);
        }
        return orders;
    }

    @SuppressWarnings("unchecked")
    private Map<Long, BigDecimal> parseShares(Object raw) {
        Map<Long, BigDecimal> shares = new LinkedHashMap<>();
        if (raw instanceof List<?> list) {
            for (Object item : list) {
                if (item instanceof Map<?, ?> entry) {
                    long taskId = Long.parseLong(String.valueOf(entry.get("taskId")));
                    BigDecimal ton = ProductionTypes.parseTon("taskShares.quantityTon",
                            String.valueOf(entry.get("quantityTon")), true);
                    shares.put(taskId, ton);
                }
            }
        }
        return shares;
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> parseAllocations(Object raw) {
        List<Map<String, Object>> allocations = new ArrayList<>();
        if (raw instanceof List<?> list) {
            for (Object item : list) {
                if (item instanceof Map<?, ?> entry) {
                    allocations.add((Map<String, Object>) entry);
                }
            }
        }
        return allocations;
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> parseRodUseReferences(Object raw) {
        List<Map<String, Object>> refs = new ArrayList<>();
        if (raw instanceof List<?> list) {
            for (Object item : list) {
                if (item instanceof Map<?, ?> entry) {
                    Map<String, Object> ref = new LinkedHashMap<>();
                    long handoverId = Long.parseLong(String.valueOf(entry.get("handoverId")));
                    BigDecimal ton = ProductionTypes.parseTon("rodUseReferences.sentQuantityTon",
                            String.valueOf(entry.get("sentQuantityTon")), true);
                    if (ton.compareTo(BigDecimal.ZERO) <= 0) {
                        throw BusinessException.invalid("rodUseReferences",
                                "用杆来源份额必须为正数。");
                    }
                    ref.put("handoverId", handoverId);
                    ref.put("sentQuantityTon", ProductionTypes.ton(ton));
                    refs.add(ref);
                }
            }
        }
        return refs;
    }

    /** 唯一明确来源推导：仅有一次未引用完的交接时自动补出依据。 */
    private Long autoDeriveRodUse(List<Map<String, Object>> refs, List<ProductionRepository.TaskRow> tasks) {
        if (!refs.isEmpty() || tasks.isEmpty()) {
            return null;
        }
        Long found = null;
        for (ProductionRepository.TaskRow task : tasks) {
            for (ProductionRepository.HandoverRow handover : repo.handoversByTask(task.taskId())) {
                BigDecimal referenced = repo.referencedRodUseTon(handover.handoverId());
                if (referenced.compareTo(handover.sentTon()) < 0) {
                    if (found != null) {
                        return null;
                    }
                    found = handover.handoverId();
                }
            }
        }
        if (found == null) {
            return null;
        }
        ProductionRepository.HandoverRow handover = repo.findHandover(found);
        BigDecimal available = handover.sentTon().subtract(repo.referencedRodUseTon(found));
        Map<String, Object> ref = new LinkedHashMap<>();
        ref.put("handoverId", found);
        ref.put("sentQuantityTon", ProductionTypes.ton(available));
        refs.add(ref);
        return found;
    }

    private Map<String, Object> wireOutputResult(long outputId, long materialId, BigDecimal qualified,
                                                 BigDecimal publicQuantity, long movementId,
                                                 List<Map<String, Object>> rodUseRefs,
                                                 List<Map<String, Object>> allocations,
                                                 List<Map<String, Object>> adjustments) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("source", ProductionTypes.SOURCE_PLANNED);
        data.put("recordType", ProductionTypes.RT_WIRE_OUTPUT);
        data.put("outputId", ProductionTypes.id(outputId));
        data.put("recordId", ProductionTypes.id(outputId));
        data.put("version", repo.findOutput(outputId).version());
        data.put("materialId", ProductionTypes.id(materialId));
        data.put("qualifiedQuantityTon", ProductionTypes.ton(qualified));
        data.put("publicQuantityTon", ProductionTypes.ton(publicQuantity));
        data.put("movementId", ProductionTypes.id(movementId));
        data.put("rodUseReferences", rodUseRefs);
        data.put("allocations", allocations);
        data.put("adjustments", adjustments);
        data.put("accountingState", rodUseRefs.isEmpty() ? "PENDING" : "PENDING");
        return data;
    }

    // ---------------------------------------------------------------- R13 产出更正

    public Map<String, Object> correctOutput(long outputId, Map<String, Object> body) {
        requireRequestId(str(body, "requestId"));
        requireOperator(str(body, "operatorName"));
        return idempotency.execute(str(body, "requestId"), "PRODUCTION_OUTPUT_CORRECTION", body,
                Map.class, () -> correctOutputTx(outputId, body));
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> correctOutputTx(long outputId, Map<String, Object> body) {
        ProductionRepository.OutputRow row = repo.lockOutput(outputId);
        if (row == null) {
            throw BusinessException.notFound("产出记录不存在。");
        }
        stock.checkVersion(requireVersion(body, "expectedVersion"), row.version(), "产出记录版本");
        requireReason(str(body, "reason"), "更正产出记录");

        Map<String, Object> changes = body.get("changes") instanceof Map<?, ?> raw
                ? (Map<String, Object>) raw : Map.of();

        // NO_OUTPUT 行作废。
        if (Boolean.TRUE.equals(changes.get("voided"))) {
            if (!ProductionTypes.ROW_NO_OUTPUT.equals(row.recordType())) {
                throw new BusinessException(ErrorCode.STATE_CONFLICT, "只有零产出确认可以作废。");
            }
            if (row.voidedAt() != null) {
                throw new BusinessException(ErrorCode.STATE_CONFLICT, "该零产出确认已作废。");
            }
            LocalDateTime voidedAt = StockService.now();
            jdbc.update("UPDATE p2_output_record SET voided_at = ?, version = version + 1 "
                    + "WHERE output_id = ?", voidedAt, outputId);
            changeLogs.insert(new ChangeLogWrite("P2_OUTPUT_RECORD", String.valueOf(outputId), "VOID",
                    snapshotOf(row), snapshotOf(repo.findOutput(outputId)), str(body, "reason"),
                    str(body, "operatorName"), (int) row.version(), (int) (row.version() + 1),
                    str(body, "requestId")));
            repo.bumpDayRevision(row.workshopId(), row.actualDate());
            if (row.taskId() != null) {
                repo.bumpTaskVersion(row.taskId());
            }
            bumpGroupIfPresent(row.groupId());
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("recordId", ProductionTypes.id(outputId));
            data.put("version", row.version() + 1);
            data.put("voidedAt", ProductionTypes.time(voidedAt));
            data.put("changeLogId", null);
            return data;
        }

        if (ProductionTypes.ROW_NO_OUTPUT.equals(row.recordType())) {
            throw new BusinessException(ErrorCode.STATE_CONFLICT, "零产出确认不通过本命令修改数量。");
        }

        // 称重更正：数量差额过账，实际日影响原新两日。
        BigDecimal newTon = row.qualifiedTon();
        if (changes.containsKey("qualifiedQuantityTon")) {
            newTon = ProductionTypes.parseTon("changes.qualifiedQuantityTon",
                    String.valueOf(changes.get("qualifiedQuantityTon")), true);
            if (newTon.compareTo(BigDecimal.ZERO) <= 0) {
                throw BusinessException.invalid("changes.qualifiedQuantityTon", "更正后的称重必须大于零。");
            }
            // 已归属的成品份额不得超过新总量。
            BigDecimal allocated = repo.allocationsByOutput(outputId).stream()
                    .map(a -> new BigDecimal(String.valueOf(a.get("quantityTon"))))
                    .reduce(BigDecimal.ZERO, BigDecimal::add);
            if (newTon.compareTo(allocated) < 0) {
                throw new BusinessException(ErrorCode.STATE_CONFLICT,
                        "更正后的重量低于已归属订单份额，存在下游交付冲突。",
                        Map.of("conflicts", List.of(Map.of("recordType", "WIRE_OUTPUT",
                                "allocatedTon", ProductionTypes.ton(allocated)))));
            }
        }
        LocalDate newDate = row.actualDate();
        if (changes.containsKey("actualDate")) {
            newDate = ProductionTypes.parseDate("changes.actualDate",
                    String.valueOf(changes.get("actualDate")), true);
            requireNotFuture(newDate);
        }

        jdbc.update("""
                UPDATE p2_output_record SET qualified_quantity_ton = ?, actual_date = ?,
                    version = version + 1 WHERE output_id = ?
                """, newTon, Date.valueOf(newDate), outputId);
        if (newTon.compareTo(row.qualifiedTon()) != 0 && row.movementId() != null) {
            BigDecimal delta = newTon.subtract(row.qualifiedTon());
            stock.postMovement(row.materialId(), "CORRECTION", null, row.taskId(),
                    "OUTPUT_CORRECTION", String.valueOf(str(body, "requestId")), 1,
                    str(body, "requestId"), newDate, delta, str(body, "operatorName"),
                    str(body, "reason"), row.movementId());
        }
        changeLogs.insert(new ChangeLogWrite("P2_OUTPUT_RECORD", String.valueOf(outputId), "UPDATE",
                snapshotOf(row), snapshotOf(repo.findOutput(outputId)), str(body, "reason"),
                str(body, "operatorName"), (int) row.version(), (int) (row.version() + 1),
                str(body, "requestId")));
        if (!newDate.equals(row.actualDate())) {
            repo.bumpDayRevision(row.workshopId(), row.actualDate());
        }
        repo.bumpDayRevision(row.workshopId(), newDate);
        if (row.taskId() != null) {
            repo.bumpTaskVersion(row.taskId());
        }
        bumpGroupIfPresent(row.groupId());

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("recordId", ProductionTypes.id(outputId));
        data.put("version", row.version() + 1);
        data.put("actualDate", newDate.toString());
        data.put("qualifiedQuantityTon", ProductionTypes.ton(newTon));
        data.put("stockDeltaTon", newTon.compareTo(row.qualifiedTon()) == 0 ? "0.000"
                : ProductionTypes.ton(newTon.subtract(row.qualifiedTon())));
        return data;
    }

    // ---------------------------------------------------------------- R14 零产出确认

    public Map<String, Object> confirmNoOutput(Map<String, Object> body) {
        requireRequestId(str(body, "requestId"));
        requireOperator(str(body, "operatorName"));
        return idempotency.execute(str(body, "requestId"), "PRODUCTION_NO_OUTPUT_CONFIRMATION", body,
                Map.class, () -> confirmNoOutputTx(body));
    }

    private Map<String, Object> confirmNoOutputTx(Map<String, Object> body) {
        String scope = str(body, "scope");
        if (!"TASK".equals(scope) && !"WORKSHOP_DAY".equals(scope)) {
            throw BusinessException.invalid("scope", "范围只允许 TASK 或 WORKSHOP_DAY。");
        }
        requireReason(str(body, "reason"), "确认无合格产出");
        LocalDate actualDate = ProductionTypes.parseDate("actualDate", str(body, "actualDate"), true);
        requireNotFuture(actualDate);
        long workshopId;
        Long taskId = null;
        if ("TASK".equals(scope)) {
            taskId = requireId(body, "taskId");
            ProductionRepository.TaskRow task = repo.lockTask(taskId);
            if (task == null) {
                throw BusinessException.notFound("任务不存在或已失效。");
            }
            stock.checkVersion(requireVersion(body, "expectedVersion"), task.version(), "任务版本");
            workshopId = task.workshopId();
            if (repo.workshopDayHasValidZero(workshopId, actualDate, taskId)) {
                throw new BusinessException(ErrorCode.STATE_CONFLICT, "该任务当日已存在有效的零产出确认。");
            }
            if (taskHasPositiveOutput(taskId, actualDate)) {
                throw new BusinessException(ErrorCode.STATE_CONFLICT,
                        "该任务当日已有合格称重，不能确认零产出。",
                        Map.of("conflicts", List.of(Map.of("recordType", "ROD_OUTPUT",
                                "scope", "TASK", "actualDate", actualDate.toString()))));
            }
        } else {
            workshopId = requireId(body, "workshopId");
            if (repo.workshopDayHasValidZero(workshopId, actualDate, null)) {
                throw new BusinessException(ErrorCode.STATE_CONFLICT, "该车间日已存在有效的整日零产出确认。");
            }
            if (repo.workshopDayHasPositiveOutput(workshopId, actualDate)) {
                throw new BusinessException(ErrorCode.STATE_CONFLICT,
                        "该车间当日已有合格称重，不能确认整日零产出。",
                        Map.of("conflicts", List.of(Map.of("recordType", "WIRE_OUTPUT",
                                "scope", "WORKSHOP_DAY", "actualDate", actualDate.toString()))));
            }
        }
        jdbc.update("""
                INSERT INTO p2_output_record
                    (record_type, workshop_id, task_id, actual_date, qualified_quantity_ton,
                     reason, operator_name)
                VALUES ('NO_OUTPUT', ?, ?, ?, 0, ?, ?)
                """, workshopId, taskId, Date.valueOf(actualDate), str(body, "reason"),
                str(body, "operatorName"));
        long recordId = jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
        repo.bumpDayRevision(workshopId, actualDate);
        if (taskId != null) {
            repo.bumpTaskVersion(taskId);
        }
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("recordId", ProductionTypes.id(recordId));
        data.put("recordType", ProductionTypes.RT_NO_OUTPUT);
        data.put("version", repo.findOutput(recordId).version());
        data.put("scope", scope);
        data.put("workshopId", ProductionTypes.id(workshopId));
        data.put("actualDate", actualDate.toString());
        data.put("qualifiedQuantityTon", "0.000");
        data.put("missingItems", List.of());
        return data;
    }

    private boolean taskHasPositiveOutput(long taskId, LocalDate actualDate) {
        Long direct = jdbc.queryForObject("""
                SELECT COUNT(*) FROM p2_output_record
                WHERE task_id = ? AND actual_date = ? AND record_type = 'QUALIFIED_OUTPUT'
                  AND voided_at IS NULL
                """, Long.class, taskId, Date.valueOf(actualDate));
        Long shared = jdbc.queryForObject("""
                SELECT COUNT(*) FROM p2_output_task_share s
                JOIN p2_output_record o ON o.output_id = s.output_id
                WHERE s.task_id = ? AND o.actual_date = ? AND o.record_type = 'QUALIFIED_OUTPUT'
                  AND o.voided_at IS NULL
                """, Long.class, taskId, Date.valueOf(actualDate));
        return (direct != null && direct > 0) || (shared != null && shared > 0);
    }

    // ---------------------------------------------------------------- R15 任务结束

    public Map<String, Object> completeTask(long taskId, Map<String, Object> body) {
        requireRequestId(str(body, "requestId"));
        requireOperator(str(body, "operatorName"));
        return idempotency.execute(str(body, "requestId"), "PRODUCTION_TASK_COMPLETION", body,
                Map.class, () -> completeTaskTx(taskId, body));
    }

    private Map<String, Object> completeTaskTx(long taskId, Map<String, Object> body) {
        ProductionRepository.TaskRow task = repo.lockTask(taskId);
        if (task == null) {
            throw BusinessException.notFound("任务不存在或已失效。");
        }
        stock.checkVersion(requireVersion(body, "expectedVersion"), task.version(), "任务版本");
        if ("COMPLETED".equals(task.status())) {
            throw new BusinessException(ErrorCode.STATE_CONFLICT, "任务已登记结束。");
        }
        LocalDate actualDate = ProductionTypes.parseDate("actualDate", str(body, "actualDate"), true);
        requireNotFuture(actualDate);
        LocalDateTime stoppedAt = ProductionTypes.toDbTime(
                ProductionTypes.parseTime("stoppedAt", str(body, "stoppedAt"), true));
        boolean hasWeighing = repo.taskHasWeighing(taskId);
        if (!hasWeighing && !repo.taskHasNoOutput(taskId)) {
            requireReason(str(body, "reason"), "任务尚未称重即结束，登记待统计");
        }

        // 停止事实入时间线。
        jdbc.update("""
                INSERT INTO p2_output_record
                    (record_type, workshop_id, task_id, actual_date, stopped_at, reason, operator_name)
                VALUES ('EXECUTION', ?, ?, ?, ?, ?, ?)
                """, task.workshopId(), taskId, Date.valueOf(actualDate), stoppedAt,
                str(body, "notes"), str(body, "operatorName"));
        repo.completeTask(taskId);

        // 未用预留释放；成品交付预留（DIRECT_OUTPUT）由成品归属支撑，不随任务结束释放。
        BigDecimal released = BigDecimal.ZERO;
        if (body.get("releaseUnusedReservations") == null
                || Boolean.TRUE.equals(body.get("releaseUnusedReservations"))) {
            for (StockService.ReservationRow reservation : stock.lockReservationsByTask(taskId)) {
                if ("DIRECT_OUTPUT".equals(reservation.sourceType())) {
                    continue;
                }
                BigDecimal remaining = reservation.reservedTon().subtract(reservation.consumedTon())
                        .subtract(reservation.releasedTon());
                released = released.add(stock.releaseReservation(reservation, remaining));
            }
        }

        ProductionRepository.TaskRow after = repo.findTask(taskId);
        BigDecimal actual = hasWeighing ? repo.taskQualifiedTon(taskId) : null;
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("taskId", ProductionTypes.id(taskId));
        data.put("taskVersion", after.version());
        data.put("actualDate", actualDate.toString());
        data.put("stoppedAt", ProductionTypes.time(stoppedAt));
        data.put("plannedQualifiedTon", ProductionTypes.ton(task.plannedQualifiedTon()));
        data.put("actualQualifiedTon", ProductionTypes.ton(actual));
        data.put("varianceTon", actual == null ? null
                : ProductionTypes.ton(actual.subtract(task.plannedQualifiedTon())));
        data.put("releasedReservationTon", ProductionTypes.ton(released));
        data.put("weighingState", hasWeighing ? ProductionTypes.WEIGH_RECORDED
                : (repo.taskHasNoOutput(taskId) ? ProductionTypes.WEIGH_CONFIRMED_ZERO
                : ProductionTypes.WEIGH_PENDING));
        List<String> pending = new ArrayList<>();
        if (!hasWeighing && !repo.taskHasNoOutput(taskId)) {
            pending.add("QUALIFIED_WEIGHING");
        }
        data.put("pendingItems", pending);
        return data;
    }

    // ---------------------------------------------------------------- R16 订单生产结束

    public Map<String, Object> completeOrder(long orderId, Map<String, Object> body) {
        requireRequestId(str(body, "requestId"));
        requireOperator(str(body, "operatorName"));
        return idempotency.execute(str(body, "requestId"), "PRODUCTION_ORDER_COMPLETION", body,
                Map.class, () -> completeOrderTx(orderId, body));
    }

    private Map<String, Object> completeOrderTx(long orderId, Map<String, Object> body) {
        ProductionRepository.OrderRow order = repo.lockOrder(orderId);
        if (order == null) {
            throw BusinessException.notFound("订单不存在。");
        }
        stock.checkVersion(requireVersion(body, "expectedVersion"), order.version(), "订单版本");
        if (!"PENDING".equals(order.productionResult())) {
            throw new BusinessException(ErrorCode.STATE_CONFLICT, "订单生产已结束，不能重复操作。");
        }
        String endingType = str(body, "endingType");
        if (!"NORMAL".equals(endingType) && !"SHORTFALL".equals(endingType)) {
            throw BusinessException.invalid("endingType", "结束类型只允许 NORMAL 或 SHORTFALL。");
        }
        BigDecimal allocated = repo.orderAllocatedTon(orderId);
        if ("NORMAL".equals(endingType)) {
            if (allocated.compareTo(order.requiredTon()) < 0) {
                throw new BusinessException(ErrorCode.MISSING_REQUIRED_DATA,
                        "合格成品归属尚未达到订单要求量，不能按正常结束；确属不再追加时请选择不足量结束。",
                        Map.of("requiredQuantityTon", ProductionTypes.ton(order.requiredTon()),
                                "allocatedQuantityTon", ProductionTypes.ton(allocated)));
            }
        } else {
            requireReason(str(body, "reason"), "不足量结束");
        }
        String result = "NORMAL".equals(endingType) ? "NORMAL_COMPLETED" : "SHORTFALL_COMPLETED";
        LocalDateTime completedAt = StockService.now();
        repo.completeOrder(orderId, result, str(body, "reason"), completedAt);
        ProductionRepository.OrderRow after = repo.lockOrder(orderId);
        BigDecimal shortfall = order.requiredTon().subtract(allocated).max(BigDecimal.ZERO);
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("orderId", ProductionTypes.id(orderId));
        data.put("productionResult", result);
        data.put("requiredQuantityTon", ProductionTypes.ton(order.requiredTon()));
        data.put("actualQualifiedQuantityTon", ProductionTypes.ton(allocated));
        data.put("shortfallTon", ProductionTypes.ton(shortfall));
        data.put("deliveryResult", after.deliveryResult());
        data.put("accountingState", after.accountingState());
        data.put("version", after.version());
        data.put("completedAt", ProductionTypes.time(completedAt));
        return data;
    }

    // ---------------------------------------------------------------- R17 计划外补录

    public Map<String, Object> recordUnplanned(Map<String, Object> body) {
        requireRequestId(str(body, "requestId"));
        requireOperator(str(body, "operatorName"));
        String recordType = str(body, "recordType");
        requireReason(str(body, "reason"), "计划外补录");
        return idempotency.execute(str(body, "requestId"), "PRODUCTION_UNPLANNED_RECORD", body,
                Map.class, () -> recordUnplannedTx(body, recordType));
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> recordUnplannedTx(Map<String, Object> body, String recordType) {
        Map<String, Object> fact = body.get("fact") instanceof Map<?, ?> raw
                ? (Map<String, Object>) raw : null;
        if (fact == null) {
            throw BusinessException.invalid("fact", "必须提供首笔事实内容。");
        }
        Long groupId = optionalId(body, "productionGroupId");
        ProductionRepository.GroupRow group = null;
        long workshopId;
        long materialId;
        if (groupId != null) {
            // 追加沿用组车间与产品；请求里若显式给出则须一致。
            group = repo.lockGroup(groupId);
            if (group == null) {
                throw BusinessException.notFound("归集组不存在。");
            }
            stock.checkVersion(requireVersion(body, "expectedVersion"), group.version(), "归集组版本");
            workshopId = group.workshopId();
            materialId = group.materialId();
            if (body.get("workshopId") != null && optionalId(body, "workshopId") != workshopId) {
                throw BusinessException.invalid("workshopId", "追加事实必须沿用归集组的车间。");
            }
            if (body.get("materialId") != null && optionalId(body, "materialId") != materialId) {
                throw BusinessException.invalid("materialId", "追加事实必须沿用归集组的产品。");
            }
        } else {
            workshopId = requireId(body, "workshopId");
            materialId = requireId(body, "materialId");
            validateGroupProduct(workshopId, materialId);
        }
        LocalDate anchorDate = ProductionTypes.parseDate("anchorActualDate",
                str(body, "anchorActualDate"), true);
        LocalDate factDate = ProductionTypes.parseDate("fact.actualDate",
                str(fact, "actualDate"), true);
        requireNotFuture(factDate);
        if (!anchorDate.equals(factDate)) {
            throw BusinessException.invalid("anchorActualDate", "首笔事实的实际日必须与归集首日一致。");
        }

        return switch (recordType) {
            case ProductionTypes.RT_EXECUTION -> unplannedExecution(group, workshopId, materialId,
                    anchorDate, factDate, fact, body, groupId == null);
            case ProductionTypes.RT_PLATE_INPUT -> unplannedPlateInput(group, workshopId, materialId,
                    anchorDate, factDate, fact, body, groupId == null);
            case ProductionTypes.RT_ROD_HANDOVER -> unplannedHandover(group, workshopId, materialId,
                    anchorDate, factDate, fact, body, groupId == null);
            case ProductionTypes.RT_ROD_OUTPUT -> unplannedRodOutput(group, workshopId, materialId,
                    anchorDate, factDate, fact, body, groupId == null);
            case ProductionTypes.RT_WIRE_OUTPUT -> unplannedWireOutput(group, workshopId, materialId,
                    anchorDate, factDate, fact, body, groupId == null);
            default -> throw BusinessException.invalid("recordType", "计划外补录不支持该记录类型。");
        };
    }

    private void validateGroupProduct(long workshopId, long materialId) {
        long rodWorkshop = queries.workshopIdByCode("WS-ROD");
        if (workshopId == rodWorkshop) {
            if (materialId != queries.rodMaterialId()) {
                throw BusinessException.invalid("materialId", "车间一归集组的产出产品固定为 Φ8 铜杆。");
            }
            return;
        }
        ProductionRepository.MaterialRow material = repo.findMaterial(materialId);
        if (material == null || !"FINISHED_WIRE".equals(material.type())) {
            throw BusinessException.invalid("materialId", "车间二归集组的产出产品必须为铜丝规格。");
        }
    }

    private long ensureGroup(ProductionRepository.GroupRow group, long workshopId, long materialId,
                             LocalDate anchorDate, Map<String, Object> body) {
        if (group != null) {
            return group.groupId();
        }
        return repo.insertGroup(ProductionTypes.GROUP_ORIGIN_UNPLANNED, workshopId, materialId,
                anchorDate, str(body, "operatorName"), str(body, "reason"));
    }

    private Map<String, Object> unplannedGroupEnvelope(long groupId, long groupVersion,
                                                       String recordType, long recordId,
                                                       Map<String, Object> extra) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("source", ProductionTypes.SOURCE_UNPLANNED);
        data.put("productionGroupId", ProductionTypes.id(groupId));
        data.put("groupVersion", groupVersion);
        data.put("recordType", recordType);
        data.put("recordId", ProductionTypes.id(recordId));
        data.putAll(extra);
        return data;
    }

    private Map<String, Object> unplannedExecution(ProductionRepository.GroupRow group,
                                                   long workshopId, long materialId,
                                                   LocalDate anchorDate, LocalDate factDate,
                                                   Map<String, Object> fact, Map<String, Object> body,
                                                   boolean createGroup) {
        long groupId = createGroup ? ensureGroup(null, workshopId, materialId, anchorDate, body)
                : group.groupId();
        LocalDateTime startedAt = ProductionTypes.toDbTime(
                ProductionTypes.parseTime("fact.startedAt", str(fact, "startedAt"), false));
        LocalDateTime stoppedAt = ProductionTypes.toDbTime(
                ProductionTypes.parseTime("fact.stoppedAt", str(fact, "stoppedAt"), false));
        if (startedAt == null && stoppedAt == null) {
            throw BusinessException.invalid("fact.startedAt", "执行事实至少要有起或止时间。");
        }
        jdbc.update("""
                INSERT INTO p2_output_record
                    (record_type, workshop_id, production_group_id, actual_date, started_at,
                     stopped_at, reason, operator_name)
                VALUES ('EXECUTION', ?, ?, ?, ?, ?, ?, ?)
                """, workshopId, groupId, Date.valueOf(factDate), startedAt, stoppedAt,
                str(body, "reason"), str(body, "operatorName"));
        long recordId = jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
        long groupVersion = bumpGroup(groupId);
        repo.bumpDayRevision(workshopId, factDate);
        return unplannedGroupEnvelope(groupId, groupVersion, ProductionTypes.RT_EXECUTION, recordId,
                Map.of("actualDate", factDate.toString(), "version",
                        repo.findOutput(recordId).version()));
    }

    private Map<String, Object> unplannedPlateInput(ProductionRepository.GroupRow group,
                                                    long workshopId, long materialId,
                                                    LocalDate anchorDate, LocalDate factDate,
                                                    Map<String, Object> fact, Map<String, Object> body,
                                                    boolean createGroup) {
        if (workshopId != queries.workshopIdByCode("WS-ROD")) {
            throw new BusinessException(ErrorCode.STATE_CONFLICT, "铜板投料只属于车间一。");
        }
        long groupId = ensureGroup(group, workshopId, materialId, anchorDate, body);
        BigDecimal inputTon = ProductionTypes.parseTon("fact.inputQuantityTon",
                str(fact, "inputQuantityTon"), true);
        if (inputTon.compareTo(BigDecimal.ZERO) <= 0) {
            throw BusinessException.invalid("fact.inputQuantityTon", "本次投入量必须大于零。");
        }
        BigDecimal amountYuan = ProductionTypes.parseYuan("fact.materialAmountYuan",
                str(fact, "materialAmountYuan"), false);
        long plateMaterial = queries.plateMaterialId();
        StockService.MovementResult movement = stock.postMovement(plateMaterial, "PRODUCTION_ISSUE",
                null, null, "PLATE_INPUT", String.valueOf(str(body, "requestId")), 1,
                str(body, "requestId"), factDate, inputTon.negate(), str(body, "operatorName"),
                null, null);
        jdbc.update("""
                INSERT INTO p2_plate_input
                    (material_id, movement_id, production_group_id, actual_date, input_quantity_ton,
                     material_amount_yuan, operator_name)
                VALUES (?, ?, ?, ?, ?, ?, ?)
                """, plateMaterial, movement.movementId(), groupId, Date.valueOf(factDate), inputTon,
                amountYuan, str(body, "operatorName"));
        long inputId = jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
        long groupVersion = bumpGroup(groupId);
        repo.bumpDayRevision(workshopId, factDate);
        Map<String, Object> extra = new LinkedHashMap<>();
        extra.put("inputId", ProductionTypes.id(inputId));
        extra.put("version", repo.findPlateInput(inputId).version());
        extra.put("actualDate", factDate.toString());
        extra.put("movementId", ProductionTypes.id(movement.movementId()));
        extra.put("accountingState", amountYuan == null ? "PENDING" : "READY");
        extra.put("missingItems", amountYuan == null ? List.of("materialAmountYuan") : List.of());
        return unplannedGroupEnvelope(groupId, groupVersion, ProductionTypes.RT_PLATE_INPUT, inputId,
                extra);
    }

    private Map<String, Object> unplannedHandover(ProductionRepository.GroupRow group,
                                                  long workshopId, long materialId,
                                                  LocalDate anchorDate, LocalDate factDate,
                                                  Map<String, Object> fact, Map<String, Object> body,
                                                  boolean createGroup) {
        if (workshopId != queries.workshopIdByCode("WS-WIRE")) {
            throw new BusinessException(ErrorCode.STATE_CONFLICT, "领杆交出只属于车间二。");
        }
        long groupId = ensureGroup(group, workshopId, materialId, anchorDate, body);
        BigDecimal sentTon = ProductionTypes.parseTon("fact.sentQuantityTon",
                str(fact, "sentQuantityTon"), true);
        if (sentTon.compareTo(BigDecimal.ZERO) <= 0) {
            throw BusinessException.invalid("fact.sentQuantityTon", "实际交出量必须大于零。");
        }
        LocalDateTime sentAt = ProductionTypes.toDbTime(
                ProductionTypes.parseTime("fact.sentAt", str(fact, "sentAt"), true));
        BigDecimal receivedTon = ProductionTypes.parseTon("fact.receivedQuantityTon",
                str(fact, "receivedQuantityTon"), false);
        LocalDateTime receivedAt = ProductionTypes.toDbTime(
                ProductionTypes.parseTime("fact.receivedAt", str(fact, "receivedAt"), false));
        validateReceivedPair(receivedTon, receivedAt, sentAt);
        ReleaseDecision release = checkRelease(sentTon, receivedTon, fact, str(body, "operatorName"));
        Long orderId = optionalId(fact, "orderId");

        StockService.MovementResult movement = stock.postMovement(queries.rodMaterialId(),
                "PRODUCTION_ISSUE", orderId, null, "ROD_HANDOVER",
                String.valueOf(str(body, "requestId")), 1, str(body, "requestId"), factDate,
                sentTon.negate(), str(body, "operatorName"), null, null);
        jdbc.update("""
                INSERT INTO p2_rod_handover
                    (production_group_id, order_id, movement_id, sent_quantity_ton,
                     received_quantity_ton, sent_at, received_at, release_reason, released_by,
                     released_at, operator_name)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, groupId, orderId, movement.movementId(), sentTon, receivedTon, sentAt,
                receivedAt, release.reason(), release.releasedBy(), release.releasedAt(),
                str(body, "operatorName"));
        long handoverId = jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
        long groupVersion = bumpGroup(groupId);
        repo.bumpDayRevision(workshopId, factDate);
        Map<String, Object> extra = new LinkedHashMap<>();
        extra.put("handoverId", ProductionTypes.id(handoverId));
        extra.put("version", repo.findHandover(handoverId).version());
        extra.put("actualDate", factDate.toString());
        extra.put("movementId", ProductionTypes.id(movement.movementId()));
        extra.put("handoverState", receivedTon == null ? ProductionTypes.HANDOVER_PENDING_RECEIPT
                : ProductionTypes.HANDOVER_CONFIRMED);
        return unplannedGroupEnvelope(groupId, groupVersion, ProductionTypes.RT_ROD_HANDOVER,
                handoverId, extra);
    }

    private Map<String, Object> unplannedRodOutput(ProductionRepository.GroupRow group,
                                                   long workshopId, long materialId,
                                                   LocalDate anchorDate, LocalDate factDate,
                                                   Map<String, Object> fact, Map<String, Object> body,
                                                   boolean createGroup) {
        if (workshopId != queries.workshopIdByCode("WS-ROD")) {
            throw new BusinessException(ErrorCode.STATE_CONFLICT, "Φ8 铜杆称重只属于车间一。");
        }
        long groupId = ensureGroup(group, workshopId, materialId, anchorDate, body);
        BigDecimal qualified = ProductionTypes.parseTon("fact.qualifiedQuantityTon",
                str(fact, "qualifiedQuantityTon"), true);
        if (qualified.compareTo(BigDecimal.ZERO) <= 0) {
            throw BusinessException.invalid("fact.qualifiedQuantityTon", "新增称重必须大于零。");
        }
        List<Map<String, Object>> inputRefs = new ArrayList<>();
        if (fact.get("inputIds") instanceof List<?> inputIds) {
            for (Object raw : inputIds) {
                long inputId = Long.parseLong(String.valueOf(raw));
                ProductionRepository.PlateInputRow input = repo.findPlateInput(inputId);
                if (input == null || !Long.valueOf(groupId).equals(input.groupId())) {
                    throw BusinessException.invalid("fact.inputIds", "投料依据必须属于同一归集组。");
                }
                Map<String, Object> ref = new LinkedHashMap<>();
                ref.put("inputId", inputId);
                ref.put("inputQuantityTon", ProductionTypes.ton(input.inputTon()));
                inputRefs.add(ref);
            }
        }
        StockService.MovementResult movement = stock.postMovement(queries.rodMaterialId(),
                "PRODUCTION_RECEIPT", null, null, "ROD_OUTPUT",
                String.valueOf(str(body, "requestId")), 1, str(body, "requestId"), factDate,
                qualified, str(body, "operatorName"), null, null);
        jdbc.update("""
                INSERT INTO p2_output_record
                    (record_type, workshop_id, production_group_id, material_id, movement_id,
                     actual_date, qualified_quantity_ton, operator_name)
                VALUES ('QUALIFIED_OUTPUT', ?, ?, ?, ?, ?, ?, ?)
                """, workshopId, groupId, queries.rodMaterialId(), movement.movementId(),
                Date.valueOf(factDate), qualified, str(body, "operatorName"));
        long outputId = jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
        jdbc.update("UPDATE p2_output_record SET input_references = CAST(? AS JSON) "
                + "WHERE output_id = ?", toJson(inputRefs), outputId);
        long groupVersion = bumpGroup(groupId);
        repo.bumpDayRevision(workshopId, factDate);
        Map<String, Object> extra = new LinkedHashMap<>();
        extra.put("outputId", ProductionTypes.id(outputId));
        extra.put("version", repo.findOutput(outputId).version());
        extra.put("actualDate", factDate.toString());
        extra.put("movementId", ProductionTypes.id(movement.movementId()));
        extra.put("qualifiedQuantityTon", ProductionTypes.ton(qualified));
        return unplannedGroupEnvelope(groupId, groupVersion, ProductionTypes.RT_ROD_OUTPUT, outputId,
                extra);
    }

    private Map<String, Object> unplannedWireOutput(ProductionRepository.GroupRow group,
                                                    long workshopId, long materialId,
                                                    LocalDate anchorDate, LocalDate factDate,
                                                    Map<String, Object> fact, Map<String, Object> body,
                                                    boolean createGroup) {
        if (workshopId != queries.workshopIdByCode("WS-WIRE")) {
            throw new BusinessException(ErrorCode.STATE_CONFLICT, "铜丝称重只属于车间二。");
        }
        long groupId = ensureGroup(group, workshopId, materialId, anchorDate, body);
        BigDecimal qualified = ProductionTypes.parseTon("fact.qualifiedQuantityTon",
                str(fact, "qualifiedQuantityTon"), true);
        if (qualified.compareTo(BigDecimal.ZERO) <= 0) {
            throw BusinessException.invalid("fact.qualifiedQuantityTon", "新增称重必须大于零。");
        }
        List<Map<String, Object>> rodUseRefs = parseRodUseReferences(fact.get("rodUseReferences"));
        List<Map<String, Object>> allocationCommands = parseAllocations(fact.get("allocations"));
        Map<Long, ProductionRepository.OrderRow> lockedOrders = lockAllocationOrders(fact,
                allocationCommands);

        StockService.MovementResult movement = stock.postMovement(materialId, "PRODUCTION_RECEIPT",
                null, null, "WIRE_OUTPUT", String.valueOf(str(body, "requestId")), 1,
                str(body, "requestId"), factDate, qualified, str(body, "operatorName"), null, null);
        jdbc.update("""
                INSERT INTO p2_output_record
                    (record_type, workshop_id, production_group_id, material_id, movement_id,
                     actual_date, qualified_quantity_ton, operator_name)
                VALUES ('QUALIFIED_OUTPUT', ?, ?, ?, ?, ?, ?, ?)
                """, workshopId, groupId, materialId, movement.movementId(), Date.valueOf(factDate),
                qualified, str(body, "operatorName"));
        long outputId = jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);

        List<Map<String, Object>> adjustments = new ArrayList<>();
        List<Map<String, Object>> allocationResults = new ArrayList<>();
        BigDecimal allocatedTotal = BigDecimal.ZERO;
        for (Map<String, Object> command : allocationCommands) {
            long orderId = requireId(command, "orderId");
            ProductionRepository.OrderRow order = lockedOrders.get(orderId);
            if (order == null) {
                throw BusinessException.notFound("归属订单 " + orderId + " 不存在。");
            }
            if (order.wireMaterialId() != materialId) {
                throw BusinessException.invalid("fact.allocations", "订单归属的规格必须与本次称重一致。");
            }
            BigDecimal requested = ProductionTypes.parseTon("quantityTon",
                    str(command, "quantityTon"), true);
            BigDecimal remaining = repo.orderRemainingTon(order);
            BigDecimal applied = requested.min(remaining);
            if (applied.compareTo(BigDecimal.ZERO) > 0) {
                jdbc.update("""
                        INSERT INTO p2_stock_reservation
                            (material_id, order_id, source_type, source_id, source_line_no,
                             reserved_quantity_ton)
                        VALUES (?, ?, 'DIRECT_OUTPUT', ?, 1, ?)
                        """, materialId, orderId, "WIRE_OUTPUT:" + outputId, applied);
                long reservationId = jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
                jdbc.update("""
                        INSERT INTO p2_finished_allocation
                            (order_id, material_id, output_id, reservation_id, source_type,
                             original_quantity_ton, allocated_quantity_ton, operator_name)
                        VALUES (?, ?, ?, ?, 'DIRECT_OUTPUT', ?, ?, ?)
                        """, orderId, materialId, outputId, reservationId, applied, applied,
                        str(body, "operatorName"));
                allocatedTotal = allocatedTotal.add(applied);
                Map<String, Object> result = new LinkedHashMap<>();
                result.put("orderId", ProductionTypes.id(orderId));
                result.put("allocationId", String.valueOf(jdbc.queryForObject(
                        "SELECT LAST_INSERT_ID()", Long.class)));
                result.put("reservationId", ProductionTypes.id(reservationId));
                result.put("quantityTon", ProductionTypes.ton(applied));
                allocationResults.add(result);
            }
            if (applied.compareTo(requested) < 0) {
                Map<String, Object> adjustment = new LinkedHashMap<>();
                adjustment.put("orderId", ProductionTypes.id(orderId));
                adjustment.put("code", "ORDER_REMAINING_LIMIT");
                adjustment.put("requestedQuantityTon", ProductionTypes.ton(requested));
                adjustment.put("allocatedQuantityTon", ProductionTypes.ton(applied));
                adjustments.add(adjustment);
            }
        }
        if (allocatedTotal.compareTo(qualified) > 0) {
            throw BusinessException.invalid("fact.allocations", "归属总量超过实测总量。");
        }
        jdbc.update("UPDATE p2_output_record SET movement_id = ?, input_references = CAST(? AS JSON) "
                + "WHERE output_id = ?", movement.movementId(), toJson(rodUseRefs), outputId);
        long groupVersion = bumpGroup(groupId);
        repo.bumpDayRevision(workshopId, factDate);
        Map<String, Object> extra = new LinkedHashMap<>();
        extra.put("outputId", ProductionTypes.id(outputId));
        extra.put("version", repo.findOutput(outputId).version());
        extra.put("actualDate", factDate.toString());
        extra.put("movementId", ProductionTypes.id(movement.movementId()));
        extra.put("qualifiedQuantityTon", ProductionTypes.ton(qualified));
        extra.put("publicQuantityTon", ProductionTypes.ton(qualified.subtract(allocatedTotal)));
        extra.put("rodUseReferences", rodUseRefs);
        extra.put("allocations", allocationResults);
        extra.put("adjustments", adjustments);
        return unplannedGroupEnvelope(groupId, groupVersion, ProductionTypes.RT_WIRE_OUTPUT, outputId,
                extra);
    }

    private long bumpGroup(Long groupId) {
        if (groupId == null) {
            return 0;
        }
        repo.bumpGroupVersion(groupId);
        ProductionRepository.GroupRow after = repo.findGroup(groupId);
        return after == null ? 0 : after.version();
    }

    private void bumpGroupIfPresent(Long groupId) {
        if (groupId != null) {
            bumpGroup(groupId);
        }
    }

    // ---------------------------------------------------------------- 工具

    private static String str(Map<String, Object> body, String key) {
        Object value = body.get(key);
        return value == null ? null : String.valueOf(value);
    }

    private long requireId(Map<String, Object> body, String key) {
        Object value = body.get(key);
        if (value == null) {
            throw BusinessException.invalid(key, "该项为必填的数字编号。");
        }
        try {
            return Long.parseLong(String.valueOf(value));
        } catch (NumberFormatException error) {
            throw BusinessException.invalid(key, "编号必须是十进制数字。");
        }
    }

    private Long optionalId(Map<String, Object> body, String key) {
        Object value = body.get(key);
        if (value == null || String.valueOf(value).isBlank()) {
            return null;
        }
        try {
            return Long.parseLong(String.valueOf(value));
        } catch (NumberFormatException error) {
            throw BusinessException.invalid(key, "编号必须是十进制数字。");
        }
    }

    private List<Long> requireIdList(Map<String, Object> body, String key) {
        List<Long> ids = new ArrayList<>();
        if (body.get(key) instanceof List<?> list) {
            for (Object item : list) {
                try {
                    ids.add(Long.parseLong(String.valueOf(item)));
                } catch (NumberFormatException error) {
                    throw BusinessException.invalid(key, "编号列表必须是十进制数字。");
                }
            }
        }
        return ids;
    }

    private long requireVersion(Map<String, Object> body, String key) {
        Object value = body.get(key);
        if (value == null) {
            throw BusinessException.invalid(key, "写入必须携带当前版本号。");
        }
        try {
            return Long.parseLong(String.valueOf(value));
        } catch (NumberFormatException error) {
            throw BusinessException.invalid(key, "版本号必须是非负整数。");
        }
    }

    private Map<String, Object> snapshotOf(Object row) {
        return row == null ? null : Json.MAPPER.convertValue(row, Map.class);
    }
}
