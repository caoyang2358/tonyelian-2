package cn.copper.phase2.knowledge;

import cn.copper.phase2.attachment.AttachmentService;
import cn.copper.phase2.common.*;
import cn.copper.phase2.common.ChangeLogModels.ChangeLogWrite;
import cn.copper.phase2.equipment.EquipmentRepository;
import cn.copper.phase2.knowledge.KnowledgeModels.*;
import cn.copper.phase2.maintenance.MaintenanceConflictException;
import cn.copper.phase2.maintenance.RepairOrderService;
import cn.copper.phase2.reference.ReferenceDictionaries;
import java.sql.Statement;
import java.time.ZoneId;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class KnowledgeService {
  static final Set<String> CATEGORIES =
      Set.of(
          "INTRODUCTION",
          "OPERATION_MAINTENANCE",
          "COMMON_FAULT",
          "TROUBLESHOOTING",
          "REPAIR_CASE");
  private final JdbcTemplate jdbc;
  private final cn.copper.phase2.maintenance.IdempotencyService idempotency;
  private final ChangeLogRepository audit;
  private final EquipmentRepository equipment;
  private final RepairOrderService repairs;
  private final AttachmentService attachments;

  public KnowledgeService(
      JdbcTemplate jdbc,
      cn.copper.phase2.maintenance.IdempotencyService idempotency,
      ChangeLogRepository audit,
      EquipmentRepository equipment,
      RepairOrderService repairs,
      AttachmentService attachments) {
    this.jdbc = jdbc;
    this.idempotency = idempotency;
    this.audit = audit;
    this.equipment = equipment;
    this.repairs = repairs;
    this.attachments = attachments;
  }

  static String text(String v) {
    return v == null || v.isBlank() ? null : v.trim();
  }

  static String required(String field, String value, int max) {
    if (text(value) == null || value.length() > max)
      throw BusinessException.invalid(field, "必填且长度不能超过 " + max);
    return value.trim();
  }

  static long id(String field, String value) {
    try {
      if (value == null || !value.matches("[1-9][0-9]*")) throw new NumberFormatException();
      return Long.parseLong(value);
    } catch (NumberFormatException e) {
      throw BusinessException.invalid(field, "必须是正整数 ID");
    }
  }

  public Article detail(String articleId) {
    long key = id("articleId", articleId);
    var rows =
        jdbc.query(
            "SELECT * FROM p2_knowledge_article WHERE article_id=?",
            (rs, n) -> {
              List<String> refs =
                  jdbc.query(
                      "SELECT attachment_id FROM p2_knowledge_attachment WHERE article_id=? ORDER"
                          + " BY display_order, attachment_id",
                      (r, i) -> r.getString(1),
                      key);
              List<String> steps;
              try {
                steps =
                    Json.MAPPER.readValue(
                        rs.getString("steps"),
                        Json.MAPPER
                            .getTypeFactory()
                            .constructCollectionType(List.class, String.class));
              } catch (Exception e) {
                throw new IllegalStateException("步骤无法读取", e);
              }
              return new Article(
                  rs.getString("article_id"),
                  rs.getString("title"),
                  rs.getString("category"),
                  rs.getString("equipment_id"),
                  rs.getString("equipment_type"),
                  rs.getString("fault_phenomenon"),
                  rs.getString("content"),
                  steps,
                  refs,
                  refs.size(),
                  rs.getString("source_repair_order_id"),
                  rs.getTimestamp("created_at")
                      .toLocalDateTime()
                      .atZone(ZoneId.of("Asia/Shanghai"))
                      .toOffsetDateTime(),
                  rs.getTimestamp("updated_at")
                      .toLocalDateTime()
                      .atZone(ZoneId.of("Asia/Shanghai"))
                      .toOffsetDateTime(),
                  rs.getInt("version"));
            },
            key);
    if (rows.isEmpty()) throw BusinessException.notFound("知识文章不存在");
    return rows.get(0);
  }

  @Transactional(readOnly = true)
  public Page list(
      String equipmentId,
      String equipmentType,
      String keyword,
      String category,
      int page,
      int pageSize) {
    if (page < 1 || pageSize < 1 || pageSize > 100)
      throw BusinessException.invalid("page", "页码必须为正数，每页1至100条");
    StringBuilder where = new StringBuilder(" WHERE 1=1");
    var args = new ArrayList<Object>();
    if (text(equipmentId) != null) {
      long eid = id("equipmentId", equipmentId);
      equipment.findDetail(eid).orElseThrow(() -> BusinessException.notFound("设备不存在"));
      where.append(
          " AND (a.equipment_id=? OR a.equipment_type=(SELECT type_code FROM p2_equipment_profile"
              + " WHERE equipment_id=?))");
      args.add(eid);
      args.add(eid);
    }
    if (text(equipmentType) != null) {
      if (!ReferenceDictionaries.isEquipmentType(equipmentType))
        throw BusinessException.invalid("equipmentType", "设备类型无效");
      where.append(
          " AND (a.equipment_type=? OR EXISTS (SELECT 1 FROM p2_equipment_profile p WHERE"
              + " p.equipment_id=a.equipment_id AND p.type_code=?))");
      args.add(equipmentType);
      args.add(equipmentType);
    }
    if (text(category) != null) {
      if (!CATEGORIES.contains(category)) throw BusinessException.invalid("category", "分类无效");
      where.append(" AND a.category=?");
      args.add(category);
    }
    if (text(keyword) != null) {
      where.append(
          " AND (LOCATE(?,a.title)>0 OR LOCATE(?,a.fault_phenomenon)>0 OR LOCATE(?,a.content)>0)");
      args.add(text(keyword));
      args.add(text(keyword));
      args.add(text(keyword));
    }
    long total =
        jdbc.queryForObject(
            "SELECT COUNT(*) FROM p2_knowledge_article a" + where, Long.class, args.toArray());
    args.add(pageSize);
    args.add((long) (page - 1) * pageSize);
    List<String> ids =
        jdbc.query(
            "SELECT a.article_id FROM p2_knowledge_article a"
                + where
                + " ORDER BY a.updated_at DESC,a.article_id DESC LIMIT ? OFFSET ?",
            (r, n) -> r.getString(1),
            args.toArray());
    return new Page(ids.stream().map(this::detail).toList(), page, pageSize, total);
  }

  @Transactional
  public Article save(String articleId, Command c) {
    if (c == null) throw BusinessException.invalid("body", "请求体必填");
    required("requestId", c.requestId(), 36);
    required("operatorName", c.operatorName(), 100);
    var replay =
        idempotency.claim(
            c.requestId(),
            articleId == null ? "KNOWLEDGE_CREATE" : "KNOWLEDGE_UPDATE",
            articleId,
            c,
            Article.class);
    if (replay.isPresent()) return replay.get();
    Article before = null;
    if (articleId != null) {
      var versions =
          jdbc.query(
              "SELECT version FROM p2_knowledge_article WHERE article_id=? FOR UPDATE",
              (r, n) -> r.getInt(1),
              id("articleId", articleId));
      if (versions.isEmpty()) throw BusinessException.notFound("知识文章不存在");
      if (c.expectedVersion() == null || c.expectedVersion() < 0)
        throw BusinessException.invalid("expectedVersion", "更新必须携带非负版本");
      if (!versions.get(0).equals(c.expectedVersion()))
        throw BusinessException.versionConflict(c.expectedVersion(), versions.get(0));
      required("reason", c.reason(), 1000);
      before = detail(articleId);
    }
    String title = required("title", c.title(), 200);
    if (!CATEGORIES.contains(c.category() == null ? "" : c.category()))
      throw BusinessException.invalid("category", "分类无效");
    String eid = text(c.equipmentId()), type = text(c.equipmentType());
    if (eid == null && type == null) throw BusinessException.invalid("equipmentId", "请选择设备或类型");
    if (type != null && !ReferenceDictionaries.isEquipmentType(type))
      throw BusinessException.invalid("equipmentType", "类型无效");
    if (eid != null) {
      var row =
          equipment
              .findDetail(id("equipmentId", eid))
              .orElseThrow(() -> BusinessException.notFound("设备不存在"));
      // 类型取共享设备档案，不推断历史设备类型。
      String actual = row.profile().typeCode();
      if (type != null && !type.equals(actual))
        throw BusinessException.invalid("equipmentType", "与设备档案类型不一致");
    }
    if (c.steps() == null) throw BusinessException.invalid("steps", "必须提供步骤数组，清空传 []");
    for (String step : c.steps()) required("steps", step, 10000);
    if (text(c.content()) == null && c.steps().isEmpty())
      throw BusinessException.invalid("content", "正文与步骤至少填写一项");
    if ("TROUBLESHOOTING".equals(c.category()) && c.steps().isEmpty())
      throw BusinessException.invalid("steps", "人工排查必须填写步骤");
    if (Set.of("COMMON_FAULT", "TROUBLESHOOTING").contains(c.category()))
      required("faultPhenomenon", c.faultPhenomenon(), 10000);
    if (c.attachmentIds() == null
        || new HashSet<>(c.attachmentIds()).size() != c.attachmentIds().size())
      throw BusinessException.invalid("attachmentIds", "附件数组必填且不可重复");
    for (String ref : c.attachmentIds())
      attachments.detail(String.valueOf(id("attachmentIds", ref)));
    String source = before == null ? text(c.sourceRepairOrderId()) : before.sourceRepairOrderId();
    if (before != null
        && c.sourceRepairOrderId() != null
        && !Objects.equals(c.sourceRepairOrderId(), source))
      throw BusinessException.invalid("sourceRepairOrderId", "不可更改维修来源");
    if (source != null) {
      if (!"REPAIR_CASE".equals(c.category()))
        throw BusinessException.invalid("category", "来源仅允许用于维修案例");
      var repair = repairs.knowledgeSource(source);
      if (!"COMPLETED".equals(repair.status()))
        throw new MaintenanceConflictException("STATE_CONFLICT", "来源工单尚未验收完成", Map.of());
      String repairType =
          equipment
              .findDetail(id("equipmentId", repair.equipmentId()))
              .orElseThrow(() -> BusinessException.notFound("来源设备不存在"))
              .profile()
              .typeCode();
      if (eid != null ? !eid.equals(repair.equipmentId()) : !Objects.equals(type, repairType))
        throw BusinessException.invalid("equipmentId", "适用范围与来源设备不一致");
    }
    String steps;
    try {
      steps = Json.MAPPER.writeValueAsString(c.steps());
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
    if (before == null) {
      var key = new GeneratedKeyHolder();
      final String sourceId = source;
      jdbc.update(
          con -> {
            var ps =
                con.prepareStatement(
                    "INSERT INTO"
                        + " p2_knowledge_article(title,category,equipment_id,equipment_type,fault_phenomenon,content,steps,source_repair_order_id,created_at,updated_at)"
                        + " VALUES(?,?,?,?,?,?,?,?,NOW(3),NOW(3))",
                    Statement.RETURN_GENERATED_KEYS);
            Object[] values = {
              title,
              c.category(),
              eid,
              type,
              text(c.faultPhenomenon()),
              c.content(),
              steps,
              sourceId
            };
            for (int i = 0; i < values.length; i++) ps.setObject(i + 1, values[i]);
            return ps;
          },
          key);
      articleId = Objects.requireNonNull(key.getKey()).toString();
    } else
      jdbc.update(
          "UPDATE p2_knowledge_article SET"
              + " title=?,category=?,equipment_id=?,equipment_type=?,fault_phenomenon=?,content=?,steps=?,version=version+1,updated_at=NOW(3)"
              + " WHERE article_id=?",
          title,
          c.category(),
          eid,
          type,
          text(c.faultPhenomenon()),
          c.content(),
          steps,
          articleId);
    jdbc.update("DELETE FROM p2_knowledge_attachment WHERE article_id=?", articleId);
    for (int i = 0; i < c.attachmentIds().size(); i++)
      jdbc.update(
          "INSERT INTO p2_knowledge_attachment(article_id,attachment_id,display_order)"
              + " VALUES(?,?,?)",
          articleId,
          c.attachmentIds().get(i),
          i);
    Article after = detail(articleId);
    audit.insert(
        new ChangeLogWrite(
            "KNOWLEDGE_ARTICLE",
            articleId,
            before == null ? "CREATE" : "UPDATE",
            before,
            after,
            before == null ? "新增知识" : c.reason(),
            c.operatorName(),
            before == null ? 0 : before.version(),
            after.version(),
            c.requestId()));
    idempotency.complete(c.requestId(), after);
    return after;
  }
}
