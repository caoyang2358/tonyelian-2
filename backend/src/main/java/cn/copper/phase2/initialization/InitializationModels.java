package cn.copper.phase2.initialization;

import java.util.List;

public final class InitializationModels {
    private InitializationModels() {}

    public record DatabaseCheck(String status, String check) {}
    public record LiveStatus(String status, String application) {}
    public record ReadyStatus(String status, DatabaseCheck database) {}
    public record DatabaseOverview(String status, String schemaName, String serverVersion,
                                   long phase1TableCount, long phase2TableCount) {}
    public record Counts(long workshops, long productionEquipment, long sensors) {}
    public record BootstrapOverview(String projectName, String environment, DatabaseOverview database,
                                    Counts counts, String scope) {}
    public record EquipmentItem(long id, String code, String name, long workshopId,
                                String workshopName, String responsiblePerson, boolean enabled) {}
    public record EquipmentList(List<EquipmentItem> items) {}
}
