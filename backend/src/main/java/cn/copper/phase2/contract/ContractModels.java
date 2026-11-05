package cn.copper.phase2.contract;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;

/**
 * 维保合同（S1-C01—C04）的对外模型。
 *
 * <p>合同与设备是多对多关系，只存在 {@code p2_contract_equipment} 关联表里，
 * 不在每台设备下复制合同正文——同一份合同关联十台设备，库里仍然只有一行合同。
 * 附件通过 {@code p2_attachment.contract_id} 反向引用，不在合同表里存文件名。</p>
 */
public final class ContractModels {
    private ContractModels() {
    }

    /**
     * C01 列表行。
     *
     * <p>{@code expiryState} 由 {@code end_date} 与服务端业务日期计算，取值见
     * {@link #STATE_ACTIVE} 等常量；{@code remainingDays} 为距到期的天数，
     * 结束日期为空时两者都给"未知"，不用 0 或负数冒充。</p>
     */
    public record ContractSummary(String contractId, String serviceOrganization,
                                  String contractNumber, LocalDate startDate, LocalDate endDate,
                                  long equipmentCount, String expiryState, Integer remainingDays) {}

    /**
     * C02 详情：合同字段 + 覆盖设备 + 关联附件 + 并发版本。
     *
     * <p>{@code equipmentIds} 与 {@code attachmentIds} 都是当前真实关联，没有就返回空数组。</p>
     */
    public record ContractDetail(String contractId, String serviceOrganization,
                                 String contractNumber, LocalDate startDate, LocalDate endDate,
                                 String remark, List<String> equipmentIds, List<String> attachmentIds,
                                 String expiryState, Integer remainingDays, long version,
                                 OffsetDateTime updatedAt) {}

    /**
     * C03/C04 解析后的写入请求。
     *
     * <p>{@code startDate} / {@code endDate} 允许为 null——契约要求"待补日期保留 null"，
     * 合同可以先建档、后补期限。{@code equipmentIds} 已去重并保留传入顺序。</p>
     */
    public record ContractWriteRequest(String requestId, String operatorName, Long expectedVersion,
                                       String reason, String serviceOrganization,
                                       String contractNumber, LocalDate startDate, LocalDate endDate,
                                       String remark, List<Long> equipmentIds) {}

    /** 未到期，且剩余天数大于维保提前提醒天数。 */
    public static final String STATE_ACTIVE = "ACTIVE";
    /** 未到期，但剩余天数已进入提前提醒范围。 */
    public static final String STATE_EXPIRING = "EXPIRING_SOON";
    /** 结束日期早于今天。到期只是一种提示，不会自动关闭设备或工单。 */
    public static final String STATE_EXPIRED = "EXPIRED";
    /** 结束日期缺失，无法判断。缺资料就报未知，不按"未到期"处理。 */
    public static final String STATE_UNKNOWN = "UNKNOWN";

    public static final List<String> EXPIRY_STATES =
            List.of(STATE_ACTIVE, STATE_EXPIRING, STATE_EXPIRED, STATE_UNKNOWN);
}
