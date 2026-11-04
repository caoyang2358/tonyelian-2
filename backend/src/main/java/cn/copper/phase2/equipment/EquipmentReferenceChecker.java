package cn.copper.phase2.equipment;

import cn.copper.phase2.equipment.EquipmentModels.DeletionBlocker;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * S1-E05 的关联检查。
 *
 * <p>详情里的 {@code deletion} 与真正的删除接口共用这一份实现，避免"页面说能删、接口说不能删"
 * 这类前后不一致。检查覆盖 {@link EquipmentRepository#REFERENCE_SCOPES} 中全部十类业务关联，
 * 每类都直接读库当前事实，不接受前端传入的"无关联"声明。</p>
 *
 * <p>未覆盖的维度不伪称已查到无关联：生产按车间与任务组织、数据模型上不挂设备，
 * 因此不产生 blocker，也不计入"检查未完成"。</p>
 */
@Component
public class EquipmentReferenceChecker {
    private static final Map<String, String> LABELS = Map.of(
            "SENSOR", "测点",
            "ALERT_EVENT", "预警事件",
            "MONITOR", "监测记录",
            "INSPECTION", "巡检记录",
            "REPAIR", "维修工单",
            "CONTRACT", "维保合同",
            "KNOWLEDGE", "知识条目",
            "LAYOUT", "车间布局节点",
            "ATTACHMENT", "附件",
            "SUPPLIER", "供应商关联");

    private final EquipmentRepository repository;

    public EquipmentReferenceChecker(EquipmentRepository repository) {
        this.repository = repository;
    }

    /** 返回阻塞删除的关联明细；空列表表示当前没有阻断性关联。 */
    public List<DeletionBlocker> blockers(long equipmentId) {
        List<DeletionBlocker> blockers = new ArrayList<>();
        for (EquipmentRepository.ReferenceScope scope : EquipmentRepository.REFERENCE_SCOPES) {
            long count = repository.countReferences(scope.table(), equipmentId);
            if (count > 0) {
                blockers.add(new DeletionBlocker(scope.type(), count,
                        "该设备已关联 " + count + " 条" + label(scope.type()) + "。"));
            }
        }
        return List.copyOf(blockers);
    }

    private static String label(String type) {
        return LABELS.getOrDefault(type, "业务记录");
    }
}
