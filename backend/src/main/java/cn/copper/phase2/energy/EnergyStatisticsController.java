package cn.copper.phase2.energy;

import cn.copper.phase2.common.ApiResponse;
import cn.copper.phase2.energy.EnergyAllocationModels.WorkshopDayAllocation;
import cn.copper.phase2.energy.EnergyStatModels.DailyConsumptionPage;
import cn.copper.phase2.energy.EnergyStatModels.EnergyTotals;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 能源总量、日来源与当日分摊接口 S2-E01、S2-E02、S2-E03。
 *
 * <p>与 {@link EnergyPriceController} 共用 {@code /api/v1/energy} 前缀但各占子路径，
 * 价格与用量是两套独立的读写面：价格是二期自己维护的，用量只读一期采集。</p>
 */
@RestController
@RequestMapping("/api/v1/energy")
public class EnergyStatisticsController {
    private final EnergyTotalsService totalsService;
    private final EnergyDailyConsumptionService dailyService;
    private final EnergyAllocationService allocationService;

    public EnergyStatisticsController(EnergyTotalsService totalsService,
                                      EnergyDailyConsumptionService dailyService,
                                      EnergyAllocationService allocationService) {
        this.totalsService = totalsService;
        this.dailyService = dailyService;
        this.allocationService = allocationService;
    }

    /** E01：分车间四项总量。省略车间时返回全部生产车间。 */
    @GetMapping("/totals")
    public ApiResponse<EnergyTotals> totals(
            @RequestParam(required = false) String from,
            @RequestParam(required = false) String to,
            @RequestParam(required = false) String workshopId) {
        return ApiResponse.success(totalsService.totals(from, to, workshopId));
    }

    /** E02：日来源明细分页，缺表计或缺读数的日槽也返回。 */
    @GetMapping("/daily-consumptions")
    public ApiResponse<DailyConsumptionPage> dailyConsumptions(
            @RequestParam(required = false) String from,
            @RequestParam(required = false) String to,
            @RequestParam(required = false) String workshopId,
            @RequestParam(required = false) String energyType,
            @RequestParam(required = false) String qualityStatus,
            @RequestParam(required = false) String completeness,
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer pageSize) {
        return ApiResponse.success(dailyService.search(from, to, workshopId, energyType,
                qualityStatus, completeness, page, pageSize));
    }

    /**
     * E03：车间某日的来源与分摊。路径里同时带车间与实际日，因为分摊天然是「一个车间的一天」，
     * 放进查询参数反而会让调用方误以为可以批量取多天再相加。
     */
    @GetMapping("/workshops/{workshopId}/days/{actualDate}/allocations")
    public ApiResponse<WorkshopDayAllocation> dayAllocation(
            @PathVariable String workshopId,
            @PathVariable String actualDate) {
        return ApiResponse.success(allocationService.dayAllocation(workshopId, actualDate));
    }
}
