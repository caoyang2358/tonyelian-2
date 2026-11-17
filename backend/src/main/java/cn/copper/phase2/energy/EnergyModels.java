package cn.copper.phase2.energy;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

/**
 * S2-E04—E06 能源价格的请求与响应模型。
 *
 * <p>三个约束在这里集中声明，避免路由层、校验层各写一套：</p>
 * <ul>
 *   <li>能源类型只有三种，与 {@code p2_energy_price} 的 CHECK 约束一致；</li>
 *   <li>单位由能源类型决定，不允许出现"电按立方米计价"这种组合；</li>
 *   <li>单价用字符串传输。数据库是 {@code decimal(24,10)}，用 JSON 数字会丢精度——
 *       这是金额，丢精度比多一层转换更糟。</li>
 * </ul>
 */
public final class EnergyModels {
    private EnergyModels() {}

    /** 顺序与页面下拉一致：电、水、煤气。 */
    public static final List<String> ENERGY_TYPES = List.of("ELECTRICITY", "WATER", "GAS");

    public static final Map<String, String> ENERGY_TYPE_NAMES = Map.of(
            "ELECTRICITY", "电",
            "WATER", "水",
            "GAS", "煤气");

    /** 单位随能源固定，前端只展示不选择；后端按此校验，防止"电按 Nm³ 计价"。 */
    public static final Map<String, String> UNIT_BY_TYPE = Map.of(
            "ELECTRICITY", "kWh",
            "WATER", "m³",
            "GAS", "Nm³");

    public static final String SCOPE_GENERAL = "GENERAL";
    public static final String SCOPE_WORKSHOP = "WORKSHOP";

    /** 单价的数据库精度：decimal(24,10)。超过 10 位小数会被 MySQL 静默截断，必须提前拒绝。 */
    public static final int PRICE_SCALE = 10;
    public static final int PRICE_PRECISION = 24;

    public static final int OPERATOR_MAX = 100;
    public static final int REASON_MAX = 1000;

    /**
     * E04 的一行价格。
     *
     * @param scope     GENERAL 表示通用范围（{@code workshop_id} 为空），WORKSHOP 表示车间专用
     * @param current   true 为当前有效价，false 为被更正替换下来的历史行
     */
    public record EnergyPriceSummary(
            String priceId,
            String energyType,
            String energyTypeName,
            String workshopId,
            String workshopName,
            String scope,
            LocalDate effectiveDate,
            String unitCode,
            String unitPriceYuanPerUnit,
            long version,
            boolean current,
            String supersedesPriceId,
            String operatorName,
            String reason,
            OffsetDateTime recordedAt) {}

    /**
     * 指定车间、能源与实际日时的匹配结果。
     *
     * <p>{@code scope} 说明这一价取自哪个范围，调用方据此解释"为什么是这个价"，
     * 而不是只拿到一个数字。</p>
     */
    public record PriceMatch(
            String priceId,
            String scope,
            LocalDate effectiveDate,
            String unitCode,
            String unitPriceYuanPerUnit,
            long version) {}

    /**
     * E04 的分页结果。
     *
     * <p>{@code matchedPrice} 只在同时给出车间、能源与实际日时才有值；其余查询为 null，
     * 表示"这次查询没有问匹配，不是没有匹配到"。</p>
     */
    public record EnergyPricePage(
            List<EnergyPriceSummary> items,
            int page,
            int pageSize,
            long total,
            PriceMatch matchedPrice) {}

    /**
     * 受影响范围。
     *
     * @param from         影响起始日
     * @param to           影响结束日；null 表示一直向后生效（新价没有终点）
     * @param workshopIds  受影响车间；通用范围为空数组，表示对全部车间生效
     */
    public record AffectedScope(LocalDate from, LocalDate to, List<String> workshopIds) {}

    /** E05 新增生效价的响应。{@code costRunId} 恒为 null：核算服务尚未实现，不伪造运行 ID。 */
    public record PriceWriteResult(
            String priceId,
            long version,
            String energyType,
            String workshopId,
            String scope,
            LocalDate effectiveDate,
            String unitCode,
            String unitPriceYuanPerUnit,
            String costRunId,
            AffectedScope affectedScope) {}

    /**
     * E06 更正的响应。
     *
     * @param previousPriceId 被更正的原价格，仍保留在历史里
     * @param priceId         更正后生成的新价格身份
     * @param status          核算状态；核算服务未接入前固定 PENDING，表示"待补算"而非"已完成"
     */
    public record PriceCorrectionResult(
            String previousPriceId,
            String priceId,
            long version,
            String supersedesPriceId,
            String changeLogId,
            AffectedScope affectedScope,
            String costRunId,
            String status) {}
}
