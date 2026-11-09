package cn.copper.phase2.knowledge;

import java.time.OffsetDateTime;
import java.util.List;

public final class KnowledgeModels {
  private KnowledgeModels() {}

  public record Command(
      String requestId,
      String operatorName,
      Integer expectedVersion,
      String reason,
      String title,
      String category,
      String equipmentId,
      String equipmentType,
      String faultPhenomenon,
      String content,
      List<String> steps,
      List<String> attachmentIds,
      String sourceRepairOrderId) {}

  public record Article(
      String articleId,
      String title,
      String category,
      String equipmentId,
      String equipmentType,
      String faultPhenomenon,
      String content,
      List<String> steps,
      List<String> attachmentIds,
      int attachmentCount,
      String sourceRepairOrderId,
      OffsetDateTime createdAt,
      OffsetDateTime updatedAt,
      int version) {}

  public record Page(List<Article> items, int page, int pageSize, long total) {}
}
