package cn.copper.phase2.maintenance;

import java.time.OffsetDateTime;
import java.util.List;

/**
 * 运维管理模块对外数据契约。
 * 字段名遵循《接口通用约定》：新增业务 ID 用十进制字符串，时刻用带偏移的 ISO 8601。
 */
public final class MaintenanceModels {
    private MaintenanceModels() {}

    /** 工单摘要：对应文档 S1-R02 拟定字段，不含详情与动作历史。 */
    public record RepairOrderItem(
            String repairOrderId,
            String orderNo,
            String equipmentId,
            String equipmentName,
            String sourceType,
            String sourceId,
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
}
