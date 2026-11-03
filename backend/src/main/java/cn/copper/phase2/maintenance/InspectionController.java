package cn.copper.phase2.maintenance;

import cn.copper.phase2.common.ApiResponse;
import cn.copper.phase2.maintenance.InspectionModels.InspectionDetail;
import cn.copper.phase2.maintenance.InspectionModels.InspectionPage;
import cn.copper.phase2.maintenance.InspectionModels.InspectionQuery;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 运维管理 · 巡检页签接口。当前实现 S1-I01 列表、S1-I02 详情；新增（I03）与更正（I04）待开发。
 */
@RestController
@RequestMapping("/api/v1/inspections")
public class InspectionController {
    private final InspectionService service;

    public InspectionController(InspectionService service) {
        this.service = service;
    }

    @GetMapping
    public ApiResponse<InspectionPage> inspections(
            @RequestParam(required = false) String equipmentId,
            @RequestParam(required = false) String result,
            @RequestParam(required = false) String from,
            @RequestParam(required = false) String to,
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer pageSize) {
        InspectionQuery query = new InspectionQuery(equipmentId, result, from, to);
        return ApiResponse.success(service.inspections(query, page, pageSize));
    }

    @GetMapping("/{inspectionId}")
    public ApiResponse<InspectionDetail> inspection(@PathVariable String inspectionId) {
        return ApiResponse.success(service.inspection(inspectionId));
    }
}
