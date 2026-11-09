package cn.copper.phase2.knowledge;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import cn.copper.phase2.attachment.AttachmentService;
import cn.copper.phase2.common.*;
import cn.copper.phase2.equipment.EquipmentRepository;
import cn.copper.phase2.knowledge.KnowledgeModels.*;
import cn.copper.phase2.maintenance.RepairOrderService;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

class KnowledgeServiceTest {
  final JdbcTemplate jdbc = mock(JdbcTemplate.class);
  final cn.copper.phase2.maintenance.IdempotencyService idem =
      mock(cn.copper.phase2.maintenance.IdempotencyService.class);
  final KnowledgeService service =
      new KnowledgeService(
          jdbc,
          idem,
          mock(ChangeLogRepository.class),
          mock(EquipmentRepository.class),
          mock(RepairOrderService.class),
          mock(AttachmentService.class));

  @Test
  void successfulReplayPrecedesVersionAndValidation() {
    Article result =
        new Article(
            "1",
            "标题",
            "INTRODUCTION",
            null,
            "OTHER",
            null,
            "正文",
            List.of(),
            List.of(),
            0,
            null,
            null,
            null,
            2);
    Command request =
        new Command("retry", "张工", 1, "更正", null, null, null, null, null, null, null, null, null);
    when(idem.claim("retry", "KNOWLEDGE_UPDATE", "1", request, Article.class))
        .thenReturn(Optional.of(result));
    assertSame(result, service.save("1", request));
    verifyNoInteractions(jdbc);
  }

  @Test
  void invalidPaginationNeverQueriesDatabase() {
    assertThrows(BusinessException.class, () -> service.list(null, null, null, null, 0, 20));
    verifyNoInteractions(jdbc);
  }

  @Test
  void positiveIdsRejectOverflowAndZero() {
    assertThrows(BusinessException.class, () -> KnowledgeService.id("articleId", "0"));
    assertThrows(
        BusinessException.class, () -> KnowledgeService.id("articleId", "999999999999999999999"));
  }
}
