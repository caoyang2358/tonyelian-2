package cn.copper.phase2.planning;

import java.time.LocalDate;
import java.util.List;

public final class PlanningModels {
    private PlanningModels() {}

    public record MaterialItem(String materialId, String code, String name, String type,
                               String specification, String unit, boolean enabled, long version) {}
    public record InventoryItem(String materialId, String code, String name, String type,
                                String specification, String physicalQuantityTon,
                                String reservedQuantityTon, String availableQuantityTon, long version) {}
    public record OrderItem(String orderId, String orderNo, String customerName, String wireMaterialId,
                            String wireSpecification, String requiredQuantityTon, LocalDate dueDate,
                            String manualPlannedRodTon, String contractAmountYuan, boolean amountConfirmed,
                            String plannedQuantityTon, boolean manualPriority, String productionResult, String deliveryResult,
                            String accountingState, long version) {}
    public record CapacityItem(String workshopId, String workshopCode, String workshopName,
                               String maxDailyOutputTon, long version) {}
    public record CapacityDay(LocalDate date, String workshopId, String workshopName,
                              String normalCapacityTon, String overrideCapacityTon,
                              String effectiveCapacityTon, String plannedQuantityTon,
                              String remainingCapacityTon, Long overrideVersion) {}
    public record PlanTaskItem(String taskId, String planId, LocalDate productionDate, int sequenceNo,
                               String workshopId, String workshopName, String materialId,
                               String materialName, String specification, String orderNumbers,
                               String customerNames, String plannedQualifiedTon, String status,
                               long version) {}
    public record CreateResult(String id, long version) {}
    public record MaterialWrite(String requestId, String code, String name, String specification,
                                String operatorName) {}
    public record OrderWrite(String requestId, String orderNo, String customerName, String wireMaterialId,
                             String requiredQuantityTon, String dueDate, String manualPlannedRodTon,
                             String contractAmountYuan, Boolean amountConfirmed, String operatorName) {}
    public record PlanConfirmationWrite(String requestId, String orderId, long expectedOrderVersion,
                                        String manualPlannedRodTon, String rodStartDate,
                                        String wireStartDate, String reason, String operatorName) {}
    public record PlanConfirmationResult(String planId, long version, List<String> taskIds,
                                         List<String> affectedOrderIds, String netNewRodTon,
                                         String plannedPlateTon, LocalDate completionDate,
                                         List<String> warnings) {}
    public record Page<T>(List<T> items, int page, int pageSize, long total) {}
    public record CapacityCalendar(List<CapacityDay> items, LocalDate from, LocalDate to, boolean complete) {}
}
