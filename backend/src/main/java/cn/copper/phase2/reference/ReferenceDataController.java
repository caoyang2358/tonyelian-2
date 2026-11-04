package cn.copper.phase2.reference;

import cn.copper.phase2.common.ApiResponse;
import cn.copper.phase2.reference.ReferenceDataModels.ReferenceData;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/reference-data")
public class ReferenceDataController {
    private final ReferenceDataService service;

    public ReferenceDataController(ReferenceDataService service) {
        this.service = service;
    }

    /** 表单下拉选项；无请求参数、无业务写入。 */
    @GetMapping
    public ApiResponse<ReferenceData> referenceData() {
        return ApiResponse.success(service.referenceData());
    }
}
