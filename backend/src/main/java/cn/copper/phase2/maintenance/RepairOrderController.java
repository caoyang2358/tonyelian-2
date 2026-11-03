package cn.copper.phase2.maintenance;

import cn.copper.phase2.common.ApiResponse;
import cn.copper.phase2.maintenance.MaintenanceModels.RepairOrderPage;
import cn.copper.phase2.maintenance.MaintenanceModels.RepairOrderQuery;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 运维管理 · 维修页签接口。当前实现 S1-R02 工单列表；其余工单接口（R01、R03—R09）待开发。
 * 路径前缀与《接口通用约定》一致：/api/v1。
 */
@RestController
@RequestMapping("/api/v1/repair-orders")
public class RepairOrderController {
    private final MaintenanceService service;

    public RepairOrderController(MaintenanceService service) {
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
}
