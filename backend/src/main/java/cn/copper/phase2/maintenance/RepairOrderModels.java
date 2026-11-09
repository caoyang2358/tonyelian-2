package cn.copper.phase2.maintenance;

import java.time.OffsetDateTime;
import java.util.List;

/**
 * 维修工单模块对外数据契约。
 * 字段名遵循《接口通用约定》：新增业务 ID 用十进制字符串，时刻用带偏移的 ISO 8601。
 */
public final class RepairOrderModels {
    private RepairOrderModels() {}

    /** 工单摘要：对应文档 S1-R02 拟定字段，不含详情与动作历史。 */
    public record RepairOrderItem(
            String repairOrderId,
            String orderNo,
            String equipmentId,
            String equipmentName,
            String sourceType,
            String sourceId,
            /** 故障类别：MECHANICAL / ELECTRICAL / INSTRUMENT / PROCESS / OTHER；历史数据可能为空。 */
            String faultCategory,
            String status,
            OffsetDateTime createdAt,
            OffsetDateTime firstSubmittedAt,
            OffsetDateTime completedAt,
            int version) {}

    /** 分页信封：page 从 1 开始，pageSize 默认 20、最大 100。 */
    public record RepairOrderPage(List<RepairOrderItem> items, int page, int pageSize, long total) {}

    /** 列表查询条件；未传的项为 null，表示不参与过滤。 */
    public record RepairOrderQuery(
            String equipmentId,
            String status,
            String sourceType,
            String from,
            String to) {}

    /**
     * 单条流转动作：对应文档 S1-R09 拟定字段，并补充 {@code description}。
     * 文档字段表未列 description，但 R07 的维修内容写入该列，不返回会使 R09 无法还原每轮维修记录。
     * {@code previousStatus} 为 null 表示建单动作，{@code nextStatus} 即动作后的工单状态。
     */
    public record RepairActionItem(
            String actionId,
            String actionType,
            String result,
            String previousStatus,
            String nextStatus,
            OffsetDateTime occurredAt,
            OffsetDateTime recordedAt,
            String operatorName,
            String reason,
            String description,
            List<String> attachmentIds) {}

    /** 动作历史分页信封。 */
    public record RepairActionPage(List<RepairActionItem> items, int page, int pageSize, long total) {}

    /**
     * 工单详情：对应文档 S1-R03。
     * {@code sourceSnapshot} 是建单时冻结的来源证据，后续来源记录变更不回写；
     * {@code availableActions} 是当前状态下允许的动作码，前端据此决定按钮可用性。
     */
    public record RepairOrderDetail(
            String repairOrderId,
            String orderNo,
            String equipmentId,
            String equipmentName,
            String sourceType,
            String sourceId,
            Object sourceSnapshot,
            String faultCategory,
            String status,
            String description,
            String supplement,
            String reporterName,
            List<String> photoAttachmentIds,
            List<String> availableActions,
            List<RepairActionItem> actions,
            OffsetDateTime createdAt,
            OffsetDateTime updatedAt,
            OffsetDateTime firstSubmittedAt,
            OffsetDateTime completedAt,
            int version) {}

    /** S1-R01 建单命令：有来源时传 sourceId，手工来源传 equipmentId；故障类别与故障描述可同时带入。 */
    public record RepairOrderCreateCommand(
            String requestId,
            String operatorName,
            String sourceType,
            String sourceId,
            String equipmentId,
            String faultCategory,
            String description,
            String reporterName) {}

    /** S1-R01 响应：doc 拟定字段 repairOrderId / status / sourceSnapshot / editableFields / version。 */
    public record RepairOrderCreated(
            String repairOrderId,
            String orderNo,
            String status,
            Object sourceSnapshot,
            List<String> editableFields,
            int version) {}

    /** S1-R04 保存资料命令：仅在 DRAFT 与 RETURNED_FOR_INFO 状态可用。 */
    public record RepairOrderSaveCommand(
            String requestId,
            String operatorName,
            Integer expectedVersion,
            String reason,
            String description,
            String supplement,
            String reporterName,
            String faultCategory,
            List<String> photoAttachmentIds) {}

    /** S1-R04 响应：同一工单详情、新版本与必要审计依据。 */
    public record RepairOrderSaved(RepairOrderDetail repairOrder, String changeLogId) {}

    /** S1-R05 提交命令。 */
    public record RepairOrderSubmitCommand(
            String requestId,
            String operatorName,
            Integer expectedVersion,
            String reason) {}

    /** S1-R06 审核命令：result 取 APPROVE / RETURN_FOR_INFO / NO_REPAIR / DUPLICATE。 */
    public record RepairOrderReviewCommand(
            String requestId,
            String operatorName,
            Integer expectedVersion,
            String result,
            String reason) {}

    /** S1-R07 维修结果命令。 */
    public record RepairResultCommand(
            String requestId,
            String operatorName,
            Integer expectedVersion,
            String reason,
            String performedAt,
            String description,
            List<String> attachmentIds) {}

    /** S1-R08 验收命令：result 取 PASS / FAIL。 */
    public record RepairAcceptanceCommand(
            String requestId,
            String operatorName,
            Integer expectedVersion,
            String result,
            String acceptedAt,
            String reason) {}

    /** S1-R05 至 R08 的统一响应：新状态、动作 ID、版本及时间。 */
    public record RepairActionResult(
            String repairOrderId,
            String status,
            String actionId,
            int version,
            OffsetDateTime updatedAt) {}
}
