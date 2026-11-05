package cn.copper.phase2.maintenance;

import java.time.OffsetDateTime;
import java.util.List;

/**
 * 提醒页签对外数据契约（S1-A01 列表、S1-A02 详情、S1-A04 处理历史）。
 *
 * <p>字段取自页面接口文档 §2 的"拟定事件摘要字段"；详情在此基础上补触发证据、规则快照与关联维修状态。
 * 小数一律以十进制字符串返回，避免前端拿到浮点误差。
 */
public final class AlertEventModels {
    private AlertEventModels() {}

    /** 列表行：设备测点身份、时间线、推导出的两个状态及关联工单。 */
    public record AlertEventItem(
            String eventId,
            String equipmentId,
            String equipmentName,
            String sensorId,
            String sensorCode,
            String parameterId,
            String parameterCode,
            OffsetDateTime startedAt,
            OffsetDateTime triggeredAt,
            OffsetDateTime recoveredAt,
            OffsetDateTime endedAt,
            String endReason,
            String eventState,
            String handlingState,
            List<String> repairOrderIds,
            int version) {}

    /** 触发时生效的规则快照：事件发生后规则再改，也不影响这里的历史依据。 */
    public record RuleSnapshot(
            String ruleVersionId,
            String ruleVersion,
            String normalLower,
            String normalUpper,
            String alarmLower,
            String alarmUpper,
            String unitCode,
            String triggerMode,
            Integer durationSeconds,
            OffsetDateTime effectiveAt) {}

    /** 由该事件发起的工单及其当前状态；维修状态独立于事件状态，不能用恢复代替验收。 */
    public record RepairOrderRef(String repairOrderId, String orderNo, String status) {}

    /** 详情：摘要字段 + 触发值、读数来源、规则快照与关联工单状态。 */
    public record AlertEventDetail(
            String eventId,
            String equipmentId,
            String equipmentName,
            String sensorId,
            String sensorCode,
            String parameterId,
            String parameterCode,
            OffsetDateTime startedAt,
            OffsetDateTime triggeredAt,
            OffsetDateTime recoveredAt,
            OffsetDateTime endedAt,
            String endReason,
            String eventState,
            String handlingState,
            String triggerValue,
            String unitCode,
            Object sourceReference,
            String ruleVersionId,
            RuleSnapshot ruleSnapshot,
            List<RepairOrderRef> repairOrders,
            int version) {}

    public record AlertEventPage(List<AlertEventItem> items, int page, int pageSize, long total) {}

    /** 列表查询条件；未传的项为 null，表示不参与过滤。 */
    public record AlertEventQuery(
            String equipmentId,
            String sensorId,
            String parameterId,
            String eventState,
            String handlingState,
            String from,
            String to) {}

    /** 单条人工处理记录；只记人工动作，不改异常事实。 */
    public record HandlingRecordItem(
            String handlingRecordId,
            String action,
            String reason,
            String operatorName,
            OffsetDateTime recordedAt) {}

    public record HandlingRecordPage(List<HandlingRecordItem> items, int page, int pageSize, long total) {}

    /** S1-A03 请求体：只记录人工动作，不改异常事实。 */
    public record HandlingCommand(
            String requestId,
            String operatorName,
            Integer expectedVersion,
            String action,
            String reason) {}

    /**
     * S1-A03 响应：写入后的人工状态就是本次动作，事件版本号随之递增。
     * 不返回事件事实（触发值、恢复时间等），因为这类动作不允许改动它们。
     */
    public record HandlingResult(
            String handlingRecordId,
            String handlingState,
            int version,
            OffsetDateTime updatedAt) {}
}
