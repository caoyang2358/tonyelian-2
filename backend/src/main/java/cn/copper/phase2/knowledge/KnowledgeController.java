package cn.copper.phase2.knowledge;

import cn.copper.phase2.common.ApiResponse;
import cn.copper.phase2.knowledge.KnowledgeModels.*;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/knowledge-articles")
public class KnowledgeController {
  private final KnowledgeService service;

  public KnowledgeController(KnowledgeService service) {
    this.service = service;
  }

  @GetMapping
  public ApiResponse<Page> list(
      @RequestParam(required = false) String equipmentId,
      @RequestParam(required = false) String equipmentType,
      @RequestParam(required = false) String keyword,
      @RequestParam(required = false) String category,
      @RequestParam(defaultValue = "1") int page,
      @RequestParam(defaultValue = "20") int pageSize) {
    return ApiResponse.success(
        service.list(equipmentId, equipmentType, keyword, category, page, pageSize));
  }

  @GetMapping("/{articleId}")
  public ApiResponse<Article> detail(@PathVariable String articleId) {
    return ApiResponse.success(service.detail(articleId));
  }

  @PostMapping
  public ApiResponse<Article> create(@RequestBody Command command) {
    return ApiResponse.success(service.save(null, command));
  }

  @PutMapping("/{articleId}")
  public ApiResponse<Article> update(@PathVariable String articleId, @RequestBody Command command) {
    return ApiResponse.success(service.save(articleId, command));
  }
}
