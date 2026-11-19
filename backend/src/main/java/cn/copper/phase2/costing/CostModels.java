package cn.copper.phase2.costing;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * 成本模块共享的常量与格式化口径。
 *
 * <p>依据 {@code docs/api/生产能源成本接口清单.md} 第 4 节与
 * {@code docs/系统二-生产与能源统计-数据与计算设计.md} 第 6—7 节：</p>
 * <ul>
 *   <li>成本流水只表达<b>价值流转</b>，数量事实仍由 {@code p2_stock_movement} 维护；
 *       写成本台账不会产生第二份实际投料或产出。</li>
 *   <li>金额算不出就是 {@code null}，已知部分单列；不拿「电＋水」的和冒充完整成本。</li>
 * </ul>
 */
public final class CostModels {

    /** 吨数 3 位（与 V6 一致）。 */
    public static final int TON_SCALE = 3;
    /** 单价按 V6 定义 10 位。 */
    public static final int UNIT_COST_SCALE = 10;
    /** 金额按分。 */
    public static final int AMOUNT_SCALE = 2;

    /** 成本流水类型，白名单与 V6 的 CHECK 一致。 */
    public static final String ENTRY_OPENING = "OPENING";
    public static final String ENTRY_RECEIPT = "RECEIPT";
    public static final String ENTRY_ISSUE = "ISSUE";
    public static final String ENTRY_ASSIGN = "ASSIGN";
    public static final String ENTRY_RELEASE = "RELEASE";
    public static final String ENTRY_DELIVERY = "DELIVERY";
    public static final String ENTRY_CORRECTION = "CORRECTION";

    /** 订单计费项类型，白名单与 V6 的 CHECK 一致。 */
    public static final String ITEM_ROD_ISSUE = "ROD_ISSUE";
    public static final String ITEM_WORKSHOP_ENERGY = "WORKSHOP_ENERGY";
    public static final String ITEM_PUBLIC_FINISHED = "PUBLIC_FINISHED";

    /** 核算运行状态，与 V6 的 CHECK 一致。 */
    public static final String RUN_PENDING = "PENDING";
    public static final String RUN_RUNNING = "RUNNING";
    public static final String RUN_INCOMPLETE = "INCOMPLETE";
    public static final String RUN_COMPLETED = "COMPLETED";
    public static final String RUN_FAILED = "FAILED";

    /** 触发方式。本版只有人工受控补算，自动触发要等来源修订号落地。 */
    public static final String TRIGGER_MANUAL = "MANUAL_RECALCULATION";

    /** 算法版本：规则、舍入与口径变化时必须改这个值，避免跨版本比对。 */
    public static final String RULE_VERSION = "S2-COST-1.0";

    /** 物料池份额键：整个物料一个池，归属另由 order_id 表达。 */
    public static final String SHARE_POOL = "POOL";

    /** 缺项码。 */
    public static final String CODE_OPENING_COST_MISSING = "OPENING_COST_MISSING";
    public static final String CODE_RECEIPT_AMOUNT_MISSING = "RECEIPT_AMOUNT_MISSING";
    public static final String CODE_MATERIAL_AMOUNT_MISSING = "MATERIAL_AMOUNT_MISSING";
    public static final String CODE_WORKSHOP1_ENERGY_MISSING = "WORKSHOP1_ENERGY_MISSING";
    public static final String CODE_WORKSHOP2_ENERGY_MISSING = "WORKSHOP2_ENERGY_MISSING";
    public static final String CODE_ROD_COST_MISSING = "ROD_COST_MISSING";
    public static final String CODE_CORRECTION_COST_MISSING = "CORRECTION_COST_MISSING";
    public static final String CODE_ORDER_ATTRIBUTION_MISSING = "ORDER_ATTRIBUTION_MISSING";
    public static final String CODE_PUBLIC_POOL_NOT_TRACKED = "PUBLIC_POOL_NOT_TRACKED";
    public static final String CODE_SOURCE_REVISION_NOT_TRACKED = "SOURCE_REVISION_NOT_TRACKED";
    public static final String CODE_COST_CARRY_MISSING = "COST_CARRY_MISSING";
    /** 成品已直接归属订单，但找不到该次产出的入库估值（成本仍按归属重量带不进来）。 */
    public static final String CODE_DIRECT_OUTPUT_COST_NOT_COVERED = "DIRECT_OUTPUT_COST_NOT_COVERED";

    private CostModels() {
    }

    /** 金额统一 HALF_UP 到分；{@code null} 原样返回，缺就是缺。 */
    public static BigDecimal amount(BigDecimal value) {
        return value == null ? null : value.setScale(AMOUNT_SCALE, RoundingMode.HALF_UP);
    }

    /** 接口输出用十进制字符串；缺项一律 {@code null}，不写 0 冒充。 */
    public static String money(BigDecimal value) {
        return value == null ? null : amount(value).toPlainString();
    }

    public static String ton(BigDecimal value) {
        return value == null ? null : value.setScale(TON_SCALE, RoundingMode.HALF_UP).toPlainString();
    }

    public static String unit(BigDecimal value) {
        return value == null ? null : value.setScale(UNIT_COST_SCALE, RoundingMode.HALF_UP).toPlainString();
    }

    /** 缺项输出为 {@code {code, message}}：只给码，页面只能显示英文；只给话，又没法按类处理。 */
    public static java.util.Map<String, Object> missing(String code) {
        java.util.Map<String, Object> item = new java.util.LinkedHashMap<>();
        item.put("code", code);
        item.put("message", message(code));
        return item;
    }

    public static String message(String code) {
        return switch (code) {
            case CODE_OPENING_COST_MISSING -> "期初只有数量没有成本金额：请在库存流水登记期初成本，未登记时均价不可得。";
            case CODE_RECEIPT_AMOUNT_MISSING -> "到货只有数量没有成本金额：请在库存流水登记到货金额，未登记时均价不可得。";
            case CODE_MATERIAL_AMOUNT_MISSING -> "本次投料材料金额未录入，入库成本只能按已知部分入账。";
            case CODE_WORKSHOP1_ENERGY_MISSING -> "车间一能源尚未按日归集，入库成本缺这一部分。";
            case CODE_WORKSHOP2_ENERGY_MISSING -> "车间二能源尚未分摊到本次产出，入库成本缺这一部分。";
            case CODE_ROD_COST_MISSING -> "本次称重未关联到已有明确成本的领杆份额。";
            case CODE_CORRECTION_COST_MISSING -> "更正后的成本依据未提供，差额无法计算。";
            case CODE_ORDER_ATTRIBUTION_MISSING -> "领用未明确归属订单，成本只能留在公共池。";
            case CODE_PUBLIC_POOL_NOT_TRACKED -> "公共成品自由池尚未按规格单独记账，自由公共数量与均价不可得。";
            case CODE_SOURCE_REVISION_NOT_TRACKED -> "来源修订号尚未落地，本次运行无法核对「计算期间来源是否变化」。";
            case CODE_COST_CARRY_MISSING -> "找不到该入库对应的上游产出或交接依据。";
            case CODE_DIRECT_OUTPUT_COST_NOT_COVERED ->
                    "成品已直接归属该订单，但找不到该次产出的入库成本估值，无法按归属重量带入；"
                            + "当前金额只是已归集部分，不作为完整成本。";
            case "COST_RUN_MISSING" -> "尚未产生核算运行，成本视图与订单盈亏都还没有依据。";
            case "QUANTITY_MISMATCH" -> "成本视图的期末数量与库存余额不一致，说明期间还有未处理的数量事实；此时不给均价。";
            case "CANCELLED" -> "订单已取消，保留历史实际与成本，不计算也不展示利润。";
            case "AMOUNT_UNCONFIRMED" -> "约定金额尚未人工确认，不能估算最终盈亏。";
            case "COST_INCOMPLETE" -> "必要成本仍缺项（材料、能源或价格），不给出完整总成本。";
            case "PRODUCTION_NOT_COMPLETED" -> "生产尚未结束，盈亏属于进行中的估算，不作为最终结果。";
            case "ORDER_COST_NOT_COVERED" -> "本次运行没有为该订单产生成本项：尚未发生可核算的领料或能源归属，不等于成本为零。";
            default -> code;
        };
    }
}
