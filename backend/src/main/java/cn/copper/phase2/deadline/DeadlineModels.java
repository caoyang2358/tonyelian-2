package cn.copper.phase2.deadline;

import java.time.LocalDate;
import java.util.List;

/**
 * 期限列表（S1-T01）。
 *
 * <p>一行代表"一台设备的一个期限来源"，不是一台设备一条：同一台设备可能同时有质保到期、
 * 预计寿命，还可能挂多份维保合同。因此同一个 {@code equipmentId} 会出现多次，
 * 靠 {@code reminderType} 与 {@code contractId} 区分。</p>
 *
 * <p>剩余天数与计算状态都不落库。日期或提前量一改，下次查询就是新结果，
 * 不存在"状态字段忘了刷新"的问题。</p>
 */
public final class DeadlineModels {
    private DeadlineModels() {
    }

    /** 质保到期，来源 {@code p2_equipment_profile.warranty_expires_on}。 */
    public static final String TYPE_WARRANTY = "WARRANTY";
    /** 维保到期，来源每份合同的 {@code end_date}；无合同的设备也占一行用于暴露缺资料。 */
    public static final String TYPE_MAINTENANCE = "MAINTENANCE";
    /** 预计寿命到期，由投用日加预计年限推算。 */
    public static final String TYPE_EXPECTED_LIFE = "EXPECTED_LIFE";

    public static final String STATE_MISSING_DATA = "MISSING_DATA";
    public static final String STATE_NORMAL = "NORMAL";
    public static final String STATE_DUE_SOON = "DUE_SOON";
    public static final String STATE_EXPIRED = "EXPIRED";

    public static final List<String> REMINDER_TYPES =
            List.of(TYPE_WARRANTY, TYPE_MAINTENANCE, TYPE_EXPECTED_LIFE);

    public static final List<String> EXPIRY_STATES =
            List.of(STATE_MISSING_DATA, STATE_NORMAL, STATE_DUE_SOON, STATE_EXPIRED);

    /**
     * 寿命到期前不足一年提示评估。
     *
     * <p>该阈值是固定的，<b>不</b>复用质保或维保的提前量——文档明确要求
     * "寿命不足一年不被这两个提前量替换"，两者语义不同：提前量是提醒策略，
     * 一年是寿命评估的口径。</p>
     */
    public static final int LIFE_EVALUATION_DAYS = 365;

    /**
     * 期限行。日期字段凡是拿不到的都给 {@code null}，不造值；具体缺什么由
     * {@code missingFields} 说明，页面据此提示补齐而不是显示假日期。
     */
    public record DeadlineItem(
            String equipmentId, String equipmentCode, String equipmentName,
            String workshopId, String workshopName, String responsiblePerson,
            String reminderType, String contractId, String contractNumber,
            LocalDate purchasedOn, LocalDate commissionedOn, Integer expectedLifeYears,
            LocalDate dueDate, Integer remainingDays, String expiryState,
            List<String> missingFields) {
    }
}
