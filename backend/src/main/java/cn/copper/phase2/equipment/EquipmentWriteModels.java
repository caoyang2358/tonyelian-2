package cn.copper.phase2.equipment;

import cn.copper.phase2.equipment.EquipmentModels.EquipmentProfile;

/**
 * S1-E03 / S1-E04 的写入载荷。
 * <p>这是全量表单：可空字段必须显式传 null 表示清空，数组传 [] 表示清空。
 * 字段是否"出现"由服务层基于原始 JSON 判定，遗漏即 400，不会被当作清空。</p>
 */
public final class EquipmentWriteModels {
    private EquipmentWriteModels() {}

    public record EquipmentWriteRequest(String requestId, String operatorName,
                                        Long expectedVersion, String reason,
                                        String code, String name, String workshopId,
                                        String responsiblePerson, Boolean enabled,
                                        EquipmentProfile profile) {}
}
