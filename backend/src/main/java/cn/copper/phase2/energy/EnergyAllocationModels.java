package cn.copper.phase2.energy;

import cn.copper.phase2.energy.EnergyStatModels.MeterIdentity;
import cn.copper.phase2.energy.EnergyStatModels.MissingItem;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;

/**
 * S2-E03 当日来源与分摊的响应模型。
 *
 * <p>这个接口回答的是「这一天用掉的能源，按什么比例算到谁头上」。它比 E01/E02 多两层
 * 容易出错的东西，所以模型里把两件事显式化：</p>
 * <ul>
 *   <li><b>分母</b>。分摊比例的分子是订单归属量，分母是当日全部合格量。分母不完整时
 *       比例本身就没有意义，宁可整块返回 null 并把缺项说清楚。</li>
 *   <li><b>核算版本</b>。费用一旦落账就有版本，本版还没有核算运行，所以 {@code costRun}
 *       恒为空，{@code status} 停在 {@code PENDING_COSTING}，不假装已核算。</li>
 * </ul>
 */
public final class EnergyAllocationModels {
    private EnergyAllocationModels() {}

    /** 车间二：按当日实际合格重量分摊全日消耗（含待机），属于经营估算。 */
    public static final String BASIS_OUTPUT_WEIGHT = "OUTPUT_WEIGHT";
    /** 车间一：材料与能源按库存成本归集，需库存成本台账，本版未接入。 */
    public static final String BASIS_STOCK_COST = "STOCK_COST";

    /** 依据齐全但还没有核算运行，费用未落账。 */
    public static final String STATUS_PENDING_COSTING = "PENDING_COSTING";
    /** 缺计量、缺称重、缺价格或缺归属，还谈不上分摊。 */
    public static final String STATUS_PENDING_INPUT = "PENDING_INPUT";
    /** 当日已确认无合格产出：能源列为车间费用，不强算比例。 */
    public static final String STATUS_NO_OUTPUT = "NO_OUTPUT_CONFIRMED";

    /** 金额与消耗的合计口径：金额 2 位，消耗沿用 6 位。 */
    public static final int AMOUNT_SCALE = 2;
    /** 分摊比例输出精度。用量占比要在两位小数的份额之间对得上，6 位够用不会误导。 */
    public static final int RATIO_SCALE = 6;

    public static final String CODE_ORDER_ATTRIBUTION_MISSING = "ORDER_ATTRIBUTION_MISSING";
    public static final String CODE_PRICE_MISSING = "PRICE_MISSING";
    public static final String CODE_ALLOCATION_BASIS_UNSUPPORTED = "ALLOCATION_BASIS_UNSUPPORTED";
    public static final String CODE_ATTRIBUTION_EXCEEDS_OUTPUT = "ATTRIBUTION_EXCEEDS_OUTPUT";
    public static final String CODE_COST_RUN_MISSING = "COST_RUN_MISSING";
    public static final String CODE_DAY_RECORDS_UNCONFIRMED = "DAY_RECORDS_UNCONFIRMED";

    /** 当时有效的价格快照。带上范围与版本，是为了让人能回答「为什么是这个价」。 */
    public record PriceSnapshot(
            String priceId,
            String scope,
            LocalDate effectiveDate,
            String unitCode,
            String unitPriceYuanPerUnit,
            long version) {}

    /**
     * 一类能源在当日的来源、价格与费用。
     *
     * @param consumption      仅完整时有值
     * @param knownConsumption 可追溯的已知部分
     * @param amountYuan       消耗与单价都齐才计算；缺一为 null，不按已知部分估费用
     */
    public record EnergyDaySource(
            String energyType,
            String energyTypeName,
            String unit,
            MeterIdentity meter,
            String consumption,
            String knownConsumption,
            String completeness,
            String qualityStatus,
            String calculationMethod,
            OffsetDateTime refreshTime,
            PriceSnapshot price,
            String amountYuan,
            List<MissingItem> missingItems) {}

    /**
     * 分摊分母。
     *
     * @param quantityTon 当日全部合格量；缺称重时为 null，确认零产出时为 "0.000"
     * @param complete    当日生产记录是否已确认录全
     */
    public record Denominator(String quantityTon, boolean complete, String note) {}

    public record ShareEnergy(String energyType, String unit, String allocatedConsumption, String amountYuan) {}

    /**
     * 一个分摊对象。订单与公共份额用同一个结构，{@code targetType} 区分，
     * 这样页面不必为公共行写第二套渲染。
     */
    public record AllocationShare(
            String targetType,
            String orderId,
            String orderNo,
            String qualifiedQuantityTon,
            String ratio,
            List<ShareEnergy> energies,
            String amountYuan,
            List<MissingItem> missingItems) {}

    /**
     * 合计校验：源总量 = 各订单份额 + 公共份额。三项都算得出来时才给差值，
     * 否则留 null——用缺测数据算出的「差 0」比不给更糟。
     */
    public record EnergyReconciliation(
            String energyType,
            String unit,
            String sourceConsumption,
            String allocatedConsumption,
            String publicConsumption,
            String difference) {}

    /** 当日录全确认状态。R18/R19 未接入时恒为未确认。 */
    public record DayConfirmation(
            boolean confirmed,
            String confirmedBy,
            OffsetDateTime confirmedAt,
            boolean invalidated,
            String note) {}

    public record CostRun(String costRunId, Long version, String status) {}

    public record WorkshopDayAllocation(
            String workshopId,
            String workshopName,
            String workshopType,
            LocalDate actualDate,
            String allocationBasis,
            String status,
            String statusNote,
            DayConfirmation dayConfirmation,
            Denominator denominator,
            List<EnergyDaySource> sources,
            String totalAmountYuan,
            List<AllocationShare> allocations,
            AllocationShare publicShare,
            List<EnergyReconciliation> reconciliation,
            CostRun costRun,
            List<MissingItem> missingItems) {}
}
