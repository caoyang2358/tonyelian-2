package cn.copper.phase2.initialization;

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

}
