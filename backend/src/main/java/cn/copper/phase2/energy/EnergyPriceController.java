package cn.copper.phase2.energy;

import cn.copper.phase2.common.ApiResponse;
import cn.copper.phase2.energy.EnergyModels.EnergyPricePage;
import cn.copper.phase2.energy.EnergyModels.PriceCorrectionResult;
import cn.copper.phase2.energy.EnergyModels.PriceWriteResult;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 能源价格接口 S2-E04—E06。
 *
 * <p>类级路径限定到 {@code /energy/prices}，不占用 {@code /energy} 前缀——
 * 总量、日明细、分摊仍归 S2-E01—E03，由同一模块的其他控制器实现，两者互不干扰。</p>
 *
 * <p>不提供删除接口。价格是核算依据，删掉一条会让引用它的历史核算无法复算；
 * 录错了走 E06 更正，录重了停用原行，历史都留着。</p>
 */
@RestController
@RequestMapping("/api/v1/energy/prices")
public class EnergyPriceController {
    private final EnergyPriceService service;
    private final EnergyPriceWriteService writeService;

    public EnergyPriceController(EnergyPriceService service, EnergyPriceWriteService writeService) {
        this.service = service;
        this.writeService = writeService;
    }

    /** E04：价格分页；带实际日时附按"专用优先、通用回退"算出的匹配结果。 */
    @GetMapping
    public ApiResponse<EnergyPricePage> prices(
            @RequestParam(required = false) String energyType,
            @RequestParam(required = false) String workshopId,
            @RequestParam(required = false) String scope,
            @RequestParam(required = false) String effectiveFrom,
            @RequestParam(required = false) String effectiveTo,
            @RequestParam(required = false) String actualDate,
            @RequestParam(required = false) Boolean includeHistory,
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer pageSize) {
        return ApiResponse.success(service.search(energyType, workshopId, scope, effectiveFrom,
                effectiveTo, actualDate, includeHistory, page, pageSize));
    }

    /** E05：新增生效价，返回新价格与受影响范围。 */
    @PostMapping
    public ApiResponse<PriceWriteResult> create(@RequestBody JsonNode body) {
        return ApiResponse.success(writeService.create(body));
    }

    /** E06：更正原价格；原行保留并退出当前有效集合，新行承接后续匹配。 */
    @PostMapping("/{priceId}/corrections")
    public ApiResponse<PriceCorrectionResult> correct(@PathVariable String priceId,
                                                      @RequestBody JsonNode body) {
        return ApiResponse.success(writeService.correct(priceId, body));
    }
}
