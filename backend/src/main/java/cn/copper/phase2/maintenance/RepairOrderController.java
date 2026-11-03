package cn.copper.phase2.maintenance;

import cn.copper.phase2.common.ApiResponse;
import cn.copper.phase2.maintenance.RepairOrderModels.RepairAcceptanceCommand;
import cn.copper.phase2.maintenance.RepairOrderModels.RepairActionPage;
import cn.copper.phase2.maintenance.RepairOrderModels.RepairActionResult;
import cn.copper.phase2.maintenance.RepairOrderModels.RepairOrderCreateCommand;
import cn.copper.phase2.maintenance.RepairOrderModels.RepairOrderCreated;
import cn.copper.phase2.maintenance.RepairOrderModels.RepairOrderDetail;
import cn.copper.phase2.maintenance.RepairOrderModels.RepairOrderPage;
import cn.copper.phase2.maintenance.RepairOrderModels.RepairOrderQuery;
import cn.copper.phase2.maintenance.RepairOrderModels.RepairOrderSaveCommand;
import cn.copper.phase2.maintenance.RepairOrderModels.RepairOrderSaved;
import cn.copper.phase2.maintenance.RepairOrderModels.RepairOrderSubmitCommand;
import cn.copper.phase2.maintenance.RepairOrderModels.RepairOrderReviewCommand;
import cn.copper.phase2.maintenance.RepairOrderModels.RepairResultCommand;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 运维管理 · 维修页签接口：S1-R01 至 S1-R09。
 * 路径前缀与《接口通用约定》一致：/api/v1。
 */
@RestController
@RequestMapping("/api/v1/repair-orders")
public class RepairOrderController {
    private final RepairOrderService service;

    public RepairOrderController(RepairOrderService service) {
        this.service = service;
    }

    @GetMapping
    public ApiResponse<RepairOrderPage> repairOrders(
            @RequestParam(required = false) String equipmentId,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String sourceType,
            @RequestParam(required = false) String from,
            @RequestParam(required = false) String to,
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer pageSize) {
        RepairOrderQuery query = new RepairOrderQuery(equipmentId, status, sourceType, from, to);
        return ApiResponse.success(service.repairOrders(query, page, pageSize));
    }

    @GetMapping("/{repairOrderId}")
    public ApiResponse<RepairOrderDetail> detail(@PathVariable String repairOrderId) {
        return ApiResponse.success(service.detail(repairOrderId));
    }

    @GetMapping("/{repairOrderId}/actions")
    public ApiResponse<RepairActionPage> actions(
            @PathVariable String repairOrderId,
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer pageSize) {
        return ApiResponse.success(service.actions(repairOrderId, page, pageSize));
    }

    @PostMapping
    public ApiResponse<RepairOrderCreated> create(@RequestBody RepairOrderCreateCommand command) {
        return ApiResponse.success(service.create(command));
    }

    @PutMapping("/{repairOrderId}")
    public ApiResponse<RepairOrderSaved> save(@PathVariable String repairOrderId,
                                              @RequestBody RepairOrderSaveCommand command) {
        return ApiResponse.success(service.save(repairOrderId, command));
    }

    @PostMapping("/{repairOrderId}/submissions")
    public ApiResponse<RepairActionResult> submit(@PathVariable String repairOrderId,
                                                  @RequestBody RepairOrderSubmitCommand command) {
        return ApiResponse.success(service.submit(repairOrderId, command));
    }

    @PostMapping("/{repairOrderId}/reviews")
    public ApiResponse<RepairActionResult> review(@PathVariable String repairOrderId,
                                                  @RequestBody RepairOrderReviewCommand command) {
        return ApiResponse.success(service.review(repairOrderId, command));
    }

    @PostMapping("/{repairOrderId}/repair-results")
    public ApiResponse<RepairActionResult> recordRepairResult(
            @PathVariable String repairOrderId, @RequestBody RepairResultCommand command) {
        return ApiResponse.success(service.recordRepairResult(repairOrderId, command));
    }

    @PostMapping("/{repairOrderId}/acceptances")
    public ApiResponse<RepairActionResult> accept(@PathVariable String repairOrderId,
                                                  @RequestBody RepairAcceptanceCommand command) {
        return ApiResponse.success(service.accept(repairOrderId, command));
    }
}
