package cn.copper.phase2.equipment;

import java.util.List;

public final class EquipmentModels {
    private EquipmentModels() {}

    public record EquipmentItem(long id, String code, String name, long workshopId,
                                String workshopName, String responsiblePerson, boolean enabled) {}
    public record EquipmentList(List<EquipmentItem> items) {}
}
