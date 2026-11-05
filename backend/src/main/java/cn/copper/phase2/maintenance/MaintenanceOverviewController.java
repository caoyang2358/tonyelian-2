package cn.copper.phase2.maintenance;

import cn.copper.phase2.common.ApiResponse;
import cn.copper.phase2.maintenance.OverviewModels.MaintenanceOverview;
import cn.copper.phase2.maintenance.OverviewModels.OverviewQuery;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 运维管理 · 概览页签接口：S1-M01 统计。
 */
@RestController
@RequestMapping("/api/v1/maintenance-overview")
public class MaintenanceOverviewController {
    private final OverviewService service;

    public MaintenanceOverviewController(OverviewService service) {
        this.service = service;
    }

    @GetMapping
    public ApiResponse<MaintenanceOverview> overview(
            @RequestParam(required = false) String workshopId,
            @RequestParam(required = false) String equipmentId,
            @RequestParam(required = false) String from,
            @RequestParam(required = false) String to) {
        return ApiResponse.success(service.overview(new OverviewQuery(workshopId, equipmentId, from, to)));
    }
}
