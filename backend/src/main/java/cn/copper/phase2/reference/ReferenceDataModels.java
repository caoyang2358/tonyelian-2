package cn.copper.phase2.reference;

import java.util.List;

/** BASE-04 参考数据 DTO。ID 一律十进制字符串，未知值不虚构。 */
public final class ReferenceDataModels {
    private ReferenceDataModels() {}

    public record ReferenceData(
            List<WorkshopOption> workshops,
            List<ProcessOption> processes,
            List<UnitOption> units,
            List<ReferenceDictionaries.Option> equipmentTypes,
            List<ReferenceDictionaries.Option> equipmentManagementStatuses) {}

    public record WorkshopOption(String id, String code, String name, boolean enabled) {}

    public record ProcessOption(String id, String code, String name, String workshopId,
                                int sequenceNo, boolean enabled) {}

    public record UnitOption(String id, String code, String name, String category) {}
}
