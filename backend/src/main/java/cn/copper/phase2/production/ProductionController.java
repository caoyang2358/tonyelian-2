package cn.copper.phase2.production;

import cn.copper.phase2.common.ApiResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 生产记录接口（S2-R01–R19）。成功为 HTTP 200 及 code=OK；
 * 业务约束见《系统二-生产记录-接口开发规范》。
 */
@RestController
@RequestMapping("/api/v1/production")
public class ProductionController {

    private final ProductionQueryService queries;
    private final ProductionCommandService commands;
    private final DayStatusService dayStatus;

    public ProductionController(ProductionQueryService queries, ProductionCommandService commands,
                                DayStatusService dayStatus) {
        this.queries = queries;
        this.commands = commands;
        this.dayStatus = dayStatus;
    }

    // S2-R01 计划任务及实际摘要
    @GetMapping("/tasks")
    public ApiResponse<Map<String, Object>> listTasks(
            @RequestParam(required = false) Long workshopId,
            @RequestParam(required = false) Long orderId,
            @RequestParam(required = false) String plannedFrom,
            @RequestParam(required = false) String plannedTo,
            @RequestParam(required = false) String actualFrom,
            @RequestParam(required = false) String actualTo,
            @RequestParam(required = false) String executionState,
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer pageSize) {
        return ApiResponse.success(queries.listTasks(workshopId, orderId, plannedFrom, plannedTo,
                actualFrom, actualTo, executionState, page, pageSize));
    }

    // S2-R02 任务详情
    @GetMapping("/tasks/{taskId}")
    public ApiResponse<Map<String, Object>> taskDetail(@PathVariable long taskId) {
        return ApiResponse.success(queries.taskDetail(taskId));
    }

    // S2-R03 实际时间线与历史
    @GetMapping("/records")
    public ApiResponse<Map<String, Object>> listRecords(
            @RequestParam(required = false) String recordType,
            @RequestParam(required = false) Long taskId,
            @RequestParam(required = false) Long productionGroupId,
            @RequestParam(required = false) Long workshopId,
            @RequestParam(required = false) Long orderId,
            @RequestParam(required = false) String actualFrom,
            @RequestParam(required = false) String actualTo,
            @RequestParam(required = false) String source,
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer pageSize) {
        return ApiResponse.success(queries.listRecords(recordType, taskId, productionGroupId, workshopId,
                orderId, actualFrom, actualTo, source, page, pageSize));
    }

    // S2-R04 记录详情（原始事实、当前值与更正依据）
    @GetMapping("/records/{recordType}/{recordId}")
    public ApiResponse<Map<String, Object>> recordDetail(@PathVariable String recordType,
                                                         @PathVariable long recordId) {
        return ApiResponse.success(queries.recordDetail(recordType, recordId));
    }

    // S2-R05 登记实际起止
    @PostMapping("/tasks/{taskId}/execution-records")
    public ApiResponse<Map<String, Object>> recordExecution(@PathVariable long taskId,
                                                            @RequestBody Map<String, Object> body) {
        body.put("taskId", String.valueOf(taskId));
        return ApiResponse.success(commands.recordExecution(body));
    }

    // S2-R06 更正执行片段
    @PostMapping("/execution-records/{recordId}/corrections")
    public ApiResponse<Map<String, Object>> correctExecution(@PathVariable long recordId,
                                                             @RequestBody Map<String, Object> body) {
        return ApiResponse.success(commands.correctExecution(recordId, body));
    }

    // S2-R07 实际铜板投料
    @PostMapping("/tasks/{taskId}/plate-inputs")
    public ApiResponse<Map<String, Object>> recordPlateInput(@PathVariable long taskId,
                                                             @RequestBody Map<String, Object> body) {
        return ApiResponse.success(commands.recordPlateInput(taskId, body));
    }

    // S2-R08 补材料金额或更正投料
    @PostMapping("/plate-inputs/{inputId}/corrections")
    public ApiResponse<Map<String, Object>> correctPlateInput(@PathVariable long inputId,
                                                              @RequestBody Map<String, Object> body) {
        return ApiResponse.success(commands.correctPlateInput(inputId, body));
    }

    // S2-R09 Φ8 铜杆实际称重
    @PostMapping("/tasks/{taskId}/rod-outputs")
    public ApiResponse<Map<String, Object>> recordRodOutput(@PathVariable long taskId,
                                                            @RequestBody Map<String, Object> body) {
        return ApiResponse.success(commands.recordRodOutput(taskId, body));
    }

    // S2-R10 铜杆交出、接收与超差放行
    @PostMapping("/tasks/{taskId}/rod-handovers")
    public ApiResponse<Map<String, Object>> recordHandover(@PathVariable long taskId,
                                                           @RequestBody Map<String, Object> body) {
        return ApiResponse.success(commands.recordHandover(taskId, body));
    }

    // S2-R11 补接收或更正交接
    @PostMapping("/rod-handovers/{handoverId}/corrections")
    public ApiResponse<Map<String, Object>> correctHandover(@PathVariable long handoverId,
                                                            @RequestBody Map<String, Object> body) {
        return ApiResponse.success(commands.correctHandover(handoverId, body));
    }

    // S2-R12 铜丝称重、归属与入库
    @PostMapping("/wire-outputs")
    public ApiResponse<Map<String, Object>> recordWireOutput(@RequestBody Map<String, Object> body) {
        return ApiResponse.success(commands.recordWireOutput(body));
    }

    // S2-R13 更正产出（含零确认作废）
    @PostMapping("/outputs/{outputId}/corrections")
    public ApiResponse<Map<String, Object>> correctOutput(@PathVariable long outputId,
                                                          @RequestBody Map<String, Object> body) {
        return ApiResponse.success(commands.correctOutput(outputId, body));
    }

    // S2-R14 明确无合格产出
    @PostMapping("/no-output-confirmations")
    public ApiResponse<Map<String, Object>> confirmNoOutput(@RequestBody Map<String, Object> body) {
        return ApiResponse.success(commands.confirmNoOutput(body));
    }

    // S2-R15 登记当日任务结束
    @PostMapping("/tasks/{taskId}/completion")
    public ApiResponse<Map<String, Object>> completeTask(@PathVariable long taskId,
                                                         @RequestBody Map<String, Object> body) {
        return ApiResponse.success(commands.completeTask(taskId, body));
    }

    // S2-R16 正常或不足量结束整单生产
    @PostMapping("/orders/{orderId}/completion")
    public ApiResponse<Map<String, Object>> completeOrder(@PathVariable long orderId,
                                                          @RequestBody Map<String, Object> body) {
        return ApiResponse.success(commands.completeOrder(orderId, body));
    }

    // S2-R17 计划外生产补录（首笔建组）
    @PostMapping("/unplanned-records")
    public ApiResponse<Map<String, Object>> recordUnplanned(@RequestBody Map<String, Object> body) {
        return ApiResponse.success(commands.recordUnplanned(body));
    }

    // S2-R18 查询车间日录全状态
    @GetMapping("/workshops/{workshopId}/days/{actualDate}/record-status")
    public ApiResponse<Map<String, Object>> dayStatus(@PathVariable long workshopId,
                                                      @PathVariable String actualDate) {
        return ApiResponse.success(dayStatus.dayStatus(workshopId, actualDate));
    }

    // S2-R19 确认当日生产记录已录全
    @PostMapping("/workshops/{workshopId}/days/{actualDate}/record-completions")
    public ApiResponse<Map<String, Object>> confirmDay(@PathVariable long workshopId,
                                                       @PathVariable String actualDate,
                                                       @RequestBody Map<String, Object> body) {
        return ApiResponse.success(dayStatus.confirmDay(workshopId, actualDate, body));
    }
}
