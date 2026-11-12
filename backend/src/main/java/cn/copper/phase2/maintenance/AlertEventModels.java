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

    /** 列表行：设备测点身份、时间线、推导出的两个状态、处理优先级、超限数值及关联工单。 */
    public record AlertEventItem(
            String eventId,
            String equipmentId,
            String equipmentName,
            String sensorId,
            String sensorCode,
            String parameterId,
            String parameterCode,
            /** 参数中文名（如「熔化温度」）：界面只展示这个，编码仅供内部比对。 */
            String parameterName,
            OffsetDateTime startedAt,
            OffsetDateTime triggeredAt,
            OffsetDateTime recoveredAt,
            OffsetDateTime endedAt,
            String endReason,
            String eventState,
            String handlingState,
            /** 处理优先级：URGENT / HIGH / NORMAL / LOW，由事件状态与人工处理状态推导。 */
            String priority,
            /** 触发时的读数与单位：列表直接可见超限程度，不必逐条点开详情。 */
            String triggerValue,
            String unitCode,
            /** 事件来源：REALTIME / HISTORY 为采集判定，MANUAL 为人工补录。列表用标签区分两者。 */
            String sourceType,
            List<String> repairOrderIds,
            /**
             * 该事件派生的工单状态（与 repairOrderIds 同序）。
             *
             * <p>列表只给状态不给单号：值班人扫列表要回答的是"这条异常有人接了没、修到哪了"，
             * 单号属于详情里的定位信息。早期列表直接拼工单主键，页面上会出现孤零零的「3」，
             * 既看不懂也点不进去。</p>
             */
            List<String> repairOrderStatuses,
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
            /** 参数中文名（如「熔化温度」）：界面只展示这个，编码仅供内部比对。 */
            String parameterName,
            OffsetDateTime startedAt,
            OffsetDateTime triggeredAt,
            OffsetDateTime recoveredAt,
            OffsetDateTime endedAt,
            String endReason,
            String eventState,
            String handlingState,
            String priority,
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
            String to,
            /** time（默认，触发时间倒序）或 priority（优先级优先）。 */
            String sort) {}

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

    /**
     * 人工补录异常事件（S1-A05）。
     *
     * <p>补录不等于伪造采集：{@code sourceReference} 会写成 MANUAL 并带上补录人与补录原因，
     * 列表用「人工补录」标签与采集判定区分开。触发值、起始时间由补录人填，
     * 设备取测点归属，单位与阈值取参数定义与规则版本，避免前后端各算一遍。</p>
     */
    public record ManualEventCommand(
            String requestId,
            String sensorId,
            String parameterId,
            /** 现场看到的超限数值；与单位一起决定"超限多少"，不参与阈值判定。 */
            String triggerValue,
            /** 异常起始时间（ISO 8601）；触发时间缺省同起始时间。 */
            String startedAt,
            String triggeredAt,
            /** 补录原因：为什么这条异常不是采集出来的，必须写清。 */
            String reason,
            String operatorName) {}
}
