package cn.copper.phase2.maintenance;

import java.time.OffsetDateTime;
import java.util.List;

/**
 * 巡检页签对外数据契约（S1-I01 列表、S1-I02 详情）。
 */
public final class InspectionModels {
    private InspectionModels() {}

    /**
     * 列表行：含设备、实际巡检时刻、巡检人、结果及关联报修单。
     *
     * <p>{@code repairOrderStatuses} 与 {@code repairOrderIds} 同序，供列表展示"报修到哪一步"；
     * 页面只渲染状态标签，不渲染主键，主键留给详情定位用。</p>
     */
    public record InspectionItem(
            String inspectionId,
            String equipmentId,
            String equipmentName,
            OffsetDateTime inspectedAt,
            String inspectorName,
            String result,
            List<String> repairOrderIds,
            List<String> repairOrderStatuses,
            int photoCount,
            int version) {}

    /**
     * 详情：在列表行基础上补巡检内容、情况说明与录入时间。
     *
     * <p>{@code inspectorName} 是实际巡检人，{@code operatorName} 是登记这条记录的人；
     * 两者可以不同（见《系统一-运维管理页面接口文档》§3 说明），更正后原登记人保持不变，
     * 更正人只记在 p2_change_log 里。</p>
     *
     * <p>{@code photoCount} 只是"有没有现场照片"的提示，照片本身走共用附件接口
     * （ownerType=INSPECTION、ownerId=巡检编号）按需拉取，避免详情体里塞一堆二进制地址。</p>
     */
    public record InspectionDetail(
            String inspectionId,
            String equipmentId,
            String equipmentName,
            OffsetDateTime inspectedAt,
            String inspectorName,
            String operatorName,
            String content,
            String result,
            String description,
            List<String> repairOrderIds,
            int photoCount,
            OffsetDateTime recordedAt,
            int version) {}

    public record InspectionPage(List<InspectionItem> items, int page, int pageSize, long total) {}

    /** 列表查询条件；未传的项为 null，表示不参与过滤。 */
    public record InspectionQuery(String equipmentId, String result, String from, String to) {}

    /** S1-I03 请求体：requestId 用于幂等，operatorName 为手填经办人（非登录身份）。 */
    public record InspectionCreateCommand(
            String requestId,
            String operatorName,
            String equipmentId,
            String inspectedAt,
            String inspectorName,
            String content,
            String result,
            String description) {}

    /** S1-I04 请求体：带 expectedVersion 做并发校验，reason 记录更正依据。 */
    public record InspectionUpdateCommand(
            String requestId,
            String operatorName,
            Integer expectedVersion,
            String reason,
            String inspectedAt,
            String inspectorName,
            String content,
            String result,
            String description) {}

    /** S1-I04 响应：更新后的详情、版本与本次留痕编号。 */
    public record InspectionSaved(InspectionDetail inspection, String changeLogId) {}
}
