package cn.copper.phase2.equipment;

import cn.copper.phase2.common.ApiResponse;
import cn.copper.phase2.common.ChangeLogModels.ChangeLogItem;
import cn.copper.phase2.common.PageResult;
import cn.copper.phase2.equipment.EquipmentDeleteModels.EquipmentDeleteResult;
import cn.copper.phase2.equipment.EquipmentModels.EquipmentDetail;
import cn.copper.phase2.equipment.EquipmentModels.EquipmentListResponse;
import cn.copper.phase2.equipment.EquipmentModels.EquipmentQuery;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/equipment")
public class EquipmentController {
    private final EquipmentService service;
    private final EquipmentWriteService writeService;
    private final EquipmentDeleteService deleteService;

    public EquipmentController(EquipmentService service, EquipmentWriteService writeService,
                               EquipmentDeleteService deleteService) {
        this.service = service;
        this.writeService = writeService;
        this.deleteService = deleteService;
    }

    /**
     * S1-E01：可选的筛选与分页。全部参数可选；
     * 不传 page/pageSize 时返回全部匹配项，与首批契约保持一致。
     */
    @GetMapping
    public ApiResponse<EquipmentListResponse> equipment(
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) Long workshopId,
            @RequestParam(required = false) String typeCode,
            @RequestParam(required = false) String managementStatus,
            @RequestParam(required = false) Boolean enabled,
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer pageSize) {
        return ApiResponse.success(service.equipment(new EquipmentQuery(
                keyword, workshopId, typeCode, managementStatus, enabled, page, pageSize)));
    }

    /** S1-E02：设备详情。设备不存在返回 404；扩展档案缺失属正常情况，仍返回 200。 */
    @GetMapping("/{equipmentId}")
    public ApiResponse<EquipmentDetail> equipmentDetail(@PathVariable String equipmentId) {
        return ApiResponse.success(service.equipmentDetail(equipmentId));
    }

    /** S1-E03：新增设备与扩展档案，返回与详情同结构，version=1。 */
    @PostMapping
    public ApiResponse<EquipmentDetail> create(@RequestBody JsonNode body) {
        return ApiResponse.success(writeService.create(body));
    }

    /** S1-E04：全量表单更正，需 expectedVersion 与非空 reason。 */
    @PutMapping("/{equipmentId}")
    public ApiResponse<EquipmentDetail> update(@PathVariable String equipmentId,
                                               @RequestBody JsonNode body) {
        return ApiResponse.success(writeService.update(equipmentId, body));
    }

    /** S1-E05：误建删除。需二次确认；有业务关联返回 409 及 blockers，档案保留。 */
    @DeleteMapping("/{equipmentId}")
    public ApiResponse<EquipmentDeleteResult> delete(@PathVariable String equipmentId,
                                                     @RequestBody JsonNode body) {
        return ApiResponse.success(deleteService.delete(equipmentId, body));
    }

    /** S1-E06：变更记录，按录入时间倒序分页。 */
    @GetMapping("/{equipmentId}/changes")
    public ApiResponse<PageResult<ChangeLogItem>> changes(
            @PathVariable String equipmentId,
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer pageSize) {
        return ApiResponse.success(writeService.changes(equipmentId, page, pageSize));
    }
}
