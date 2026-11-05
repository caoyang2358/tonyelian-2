package cn.copper.phase2.contract;

import cn.copper.phase2.common.ApiResponse;
import cn.copper.phase2.common.PageResult;
import cn.copper.phase2.contract.ContractModels.ContractDetail;
import cn.copper.phase2.contract.ContractModels.ContractSummary;
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
 * 维保合同接口 S1-C01—C04。
 *
 * <p>不提供删除接口：契约未定义合同删除，且合同一旦关联设备与附件，删除会牵动历史维修记录。
 * 不再使用的合同应通过解绑覆盖设备（{@code equipmentIds: []}）退出使用范围，
 * 附件与历史工单保持可追溯。</p>
 */
@RestController
@RequestMapping("/api/v1/maintenance-contracts")
public class ContractController {
    private final ContractService service;
    private final ContractWriteService writeService;

    public ContractController(ContractService service, ContractWriteService writeService) {
        this.service = service;
        this.writeService = writeService;
    }

    /** S1-C01：分页查询，可按设备、服务单位与期限状态过滤。 */
    @GetMapping
    public ApiResponse<PageResult<ContractSummary>> contracts(
            @RequestParam(required = false) String equipmentId,
            @RequestParam(required = false) String serviceOrganization,
            @RequestParam(required = false) String expiryState,
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer pageSize) {
        return ApiResponse.success(service.search(equipmentId, serviceOrganization,
                expiryState, page, pageSize));
    }

    /** S1-C02：合同详情、覆盖设备与关联附件。 */
    @GetMapping("/{contractId}")
    public ApiResponse<ContractDetail> contract(@PathVariable String contractId) {
        return ApiResponse.success(service.detail(contractId));
    }

    /** S1-C03：新增合同并关联设备，返回与详情同结构，{@code version=1}。 */
    @PostMapping
    public ApiResponse<ContractDetail> create(@RequestBody JsonNode body) {
        return ApiResponse.success(writeService.create(body));
    }

    /** S1-C04：更新期限、资料与覆盖设备；{@code equipmentIds: []} 表示解除全部覆盖关系。 */
    @PutMapping("/{contractId}")
    public ApiResponse<ContractDetail> update(@PathVariable String contractId,
                                              @RequestBody JsonNode body) {
        return ApiResponse.success(writeService.update(contractId, body));
    }
}
