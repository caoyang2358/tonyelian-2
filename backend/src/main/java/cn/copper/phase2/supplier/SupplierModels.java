package cn.copper.phase2.supplier;

import java.time.OffsetDateTime;
import java.util.List;

/**
 * 供应商（S1-S01—S04）的对外模型。
 *
 * <p>供应商是设备档案的关联对象：一台设备可以有多个供应商，一个供应商也可以覆盖多台设备，
 * 关系只存在 {@code p2_equipment_supplier} 关联表里，不在设备档案中复制供应商字段。</p>
 */
public final class SupplierModels {
    private SupplierModels() {
    }

    /** S01 列表行：只含选择与摘要需要的字段，{@code equipmentCount} 为当前真实关联数。 */
    public record SupplierSummary(String supplierId, String name, String contactName,
                                  String phone, long equipmentCount) {}

    /**
     * S02 详情：可写字段 + 关联设备集合 + 并发版本。
     * {@code equipmentIds} 按设备 ID 升序返回，空数组表示当前没有任何设备关联。
     */
    public record SupplierDetail(String supplierId, String name, String contactName, String phone,
                                 String remark, List<String> equipmentIds, long version,
                                 OffsetDateTime updatedAt) {}

    /**
     * S03/S04 解析后的写入请求。
     *
     * <p>{@code equipmentIds} 已去重并保留传入顺序；{@code expectedVersion} 仅 S04 使用，
     * 新建时为 null。{@code reason} 为可选的补充说明，不强制填写——维护联系方式不属于
     * 契约要求必须说明理由的重要更正。</p>
     */
    public record SupplierWriteRequest(String requestId, String operatorName, Long expectedVersion,
                                       String reason, String name, String contactName, String phone,
                                       String remark, List<Long> equipmentIds) {}
}
