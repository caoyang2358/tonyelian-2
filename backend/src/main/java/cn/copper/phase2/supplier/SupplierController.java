package cn.copper.phase2.supplier;

import cn.copper.phase2.common.ApiResponse;
import cn.copper.phase2.common.PageResult;
import cn.copper.phase2.supplier.SupplierModels.SupplierDetail;
import cn.copper.phase2.supplier.SupplierModels.SupplierSummary;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 供应商接口 S1-S01—S04，挂在设备管理的"供应商"页签下使用。
 *
 * <p>不提供删除接口：契约未定义供应商删除，历史合同与设备关联需要保留可追溯关系。
 * 不再使用的供应商应通过解绑设备关联（{@code equipmentIds: []}）退出使用范围。</p>
 */
@RestController
@RequestMapping("/api/v1/suppliers")
public class SupplierController {
    private final SupplierService service;
    private final SupplierWriteService writeService;

    public SupplierController(SupplierService service, SupplierWriteService writeService) {
        this.service = service;
        this.writeService = writeService;
    }

    /** S1-S01：分页查询，可按关键字与设备过滤。 */
    @GetMapping
    public ApiResponse<PageResult<SupplierSummary>> suppliers(
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) String equipmentId,
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer pageSize) {
        return ApiResponse.success(service.search(keyword, equipmentId, page, pageSize));
    }

    /** S1-S02：供应商详情与关联设备。 */
    @GetMapping("/{supplierId}")
    public ApiResponse<SupplierDetail> supplier(@PathVariable String supplierId) {
        return ApiResponse.success(service.detail(supplierId));
    }

    /** S1-S03：新增供应商并关联设备，返回与详情同结构，{@code version=1}。 */
    @PostMapping
    public ApiResponse<SupplierDetail> create(@RequestBody JsonNode body) {
        return ApiResponse.success(writeService.create(body));
    }

    /** S1-S04：整体更新资料与关联；{@code equipmentIds: []} 表示解除全部设备关联。 */
    @PutMapping("/{supplierId}")
    public ApiResponse<SupplierDetail> update(@PathVariable String supplierId,
                                              @RequestBody JsonNode body) {
        return ApiResponse.success(writeService.update(supplierId, body));
    }
}
