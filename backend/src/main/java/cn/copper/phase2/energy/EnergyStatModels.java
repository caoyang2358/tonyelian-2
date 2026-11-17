package cn.copper.phase2.energy;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;

/**
 * S2-E01—E03 能源总量与日来源的响应模型。
 *
 * <p>这里集中三件事：完整性三态、缺项结构、数值的输出精度。它们必须与
 * {@code docs/系统二-生产与能源统计-接口开发规范.md} 第 3.1 节一致，否则前端
 * 只能靠猜来区分「真零」「缺测」「部分已知」——而这三者在生产现场是完全不同的结论。</p>
 */
public final class EnergyStatModels {
    private EnergyStatModels() {}

    /**
     * 完整。可包含明确的零：计量正常、当日确实没用电，就是 {@code COMPLETE} + {@code "0.000000"}。
     */
    public static final String COMPLETE = "COMPLETE";
    /**
     * 部分已知。有可追溯的来源，但覆盖、质量或称重范围仍有缺口，{@code knownValue} 给出已知部分，
     * {@code value} 必须为 null——不能把已知部分当成全天用量，那会低估费用。
     */
    public static final String PARTIAL = "PARTIAL";
    /** 没有可采纳来源。{@code value} 与 {@code knownValue} 都为 null，并返回具体缺项。 */
    public static final String MISSING = "MISSING";

    /** 能源消耗沿用一期 DWS 的 6 位小数，不做二次舍入。 */
    public static final int CONSUMPTION_SCALE = 6;
    /** 吨数按契约保留 3 位。 */
    public static final int TON_SCALE = 3;

    /** 单次查询的日期跨度上限。日槽是「日期×车间×能源」展开的，不设上限会一次撑爆响应。 */
    public static final int MAX_RANGE_DAYS = 366;

    public static final String UNIT_TON = "t";

    /**
     * 一条缺项。每个字段回答「去哪里补」：哪张来源、哪台表计、哪个车间哪一天、哪个字段。
     *
     * @param sourceType 缺项的来源类别，如 {@code ENERGY_METER}、{@code PRODUCTION_RECORD}
     * @param sourceId   来源主键，可空（例如整段日期都没有表计时无单条来源）
     */
    public record MissingItem(
            String code,
            String sourceType,
            String sourceId,
            String workshopId,
            LocalDate actualDate,
            String field,
            String message) {}

    /**
     * 一个能源项或产量的汇总。
     *
     * @param value      只在 {@code COMPLETE} 时有值
     * @param knownValue 可追溯的已知部分；没有任何已知来源时为 null，明确零才为 "0.000000"
     */
    public record EnergyQuantity(
            String unit,
            String value,
            String knownValue,
            String completeness,
            List<MissingItem> missingItems) {}

    /** 一个车间的四项。车间一、车间二的产量分别返回，不相加成「全厂产量」。 */
    public record WorkshopTotals(
            String workshopId,
            String workshopName,
            EnergyQuantity electricity,
            EnergyQuantity water,
            EnergyQuantity gas,
            EnergyQuantity qualifiedOutput) {}

    public record EnergyTotals(LocalDate from, LocalDate to, List<WorkshopTotals> workshops) {}

    /**
     * 表计身份。一期 {@code dim_sensor} 里同时有工艺测点和能源表计，
     * 这里只承载已确认归属的能源总表，未确认归属的不进结果。
     */
    public record MeterIdentity(String sensorId, String sensorCode, String sensorName) {}

    /**
     * E02 的一个日来源槽。缺表计或缺记录时仍返回该槽，{@code meter} 为 null，
     * 让页面能定位缺口，而不是让整行消失。
     */
    public record DailyConsumptionItem(
            LocalDate actualDate,
            String workshopId,
            String workshopName,
            String energyType,
            String energyTypeName,
            String unit,
            MeterIdentity meter,
            String value,
            String knownValue,
            String completeness,
            String qualityStatus,
            String calculationMethod,
            OffsetDateTime refreshTime,
            List<MissingItem> missingItems) {}

    /**
     * 查询范围内日槽的完整性分布。<b>统计筛选前的全量</b>：筛选只缩小列表，
     * 不影响这里的分母——否则筛掉缺测后满屏 COMPLETE，会被读成"整段区间都完整"。
     * {@code total} 才是筛选后的条数，用于分页。
     */
    public record DailyConsumptionSummary(
            long slotTotal, long completeCount, long partialCount, long missingCount) {}

    public record DailyConsumptionPage(
            List<DailyConsumptionItem> items,
            int page,
            int pageSize,
            long total,
            DailyConsumptionSummary summary) {}
}
