package cn.copper.phase2.attachment;

import cn.copper.phase2.attachment.AttachmentModels.AttachmentRow;
import cn.copper.phase2.common.BusinessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

import java.sql.PreparedStatement;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * p2_attachment 的数据访问。
 *
 * <p>归属对象用四个可空外键表达，所以"按归属查"要落到不同列上。列名与表名一律由
 * {@link #ownerColumn} / {@link #ownerTable} 从固定枚举映射出来，不接受外部字符串拼进 SQL。</p>
 *
 * <p>本类不返回 storage_key 给接口层——它在 {@link AttachmentRow} 里只是为了服务层能定位文件。</p>
 */
@Repository
public class AttachmentRepository {

    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");

    /** 归属类型 + 归属 ID + 可选文件类别，构成 F01 的查询条件。 */
    public record AttachmentQuery(String ownerType, long ownerId, String fileKind,
                                  int page, int pageSize) {}

    /**
     * 统一把四列折成对外的 ownerType / ownerId。
     *
     * <p>不写成 UNION：四个分支的列既有 BIGINT 又有隐式字符串转换，MySQL 8 会因
     * collation 不一致直接报 1271。CASE + COALESCE 全程只有一个结果类型，没有这个坑。</p>
     */
    static final String OWNER_TYPE_EXPR = """
            CASE WHEN a.equipment_id IS NOT NULL THEN 'EQUIPMENT'
                 WHEN a.contract_id IS NOT NULL THEN 'MAINTENANCE_CONTRACT'
                 WHEN a.article_id IS NOT NULL THEN 'KNOWLEDGE_ARTICLE'
                 WHEN a.repair_order_id IS NOT NULL THEN 'REPAIR_ORDER'
                 ELSE 'INSPECTION' END
            """;

    static final String OWNER_ID_EXPR =
            "COALESCE(a.equipment_id, a.contract_id, a.article_id, a.repair_order_id, a.inspection_id)";

    static String summaryTemplate(String ownerTypeExpr, String ownerIdExpr) {
        return "SELECT a.attachment_id, " + ownerTypeExpr + " AS owner_type, " + ownerIdExpr
                + " AS owner_id, a.file_kind, a.original_name, a.media_type, a.size_bytes,"
                + " a.version, a.updated_at FROM p2_attachment a ";
    }

    static final String SELECT_ROW = """
            SELECT a.attachment_id,
                   CASE WHEN a.equipment_id IS NOT NULL THEN 'EQUIPMENT'
                        WHEN a.contract_id IS NOT NULL THEN 'MAINTENANCE_CONTRACT'
                        WHEN a.article_id IS NOT NULL THEN 'KNOWLEDGE_ARTICLE'
                        WHEN a.repair_order_id IS NOT NULL THEN 'REPAIR_ORDER'
                        ELSE 'INSPECTION' END AS owner_type,
                   COALESCE(a.equipment_id, a.contract_id, a.article_id, a.repair_order_id,
                            a.inspection_id) AS owner_id,
                   a.file_kind, a.original_name, a.media_type, a.size_bytes, a.storage_key,
                   a.version, a.updated_at
            FROM p2_attachment a WHERE a.attachment_id = ?
            """;

    static final String INSERT_TEMPLATE = """
            INSERT INTO p2_attachment
                (%s, file_kind, original_name, media_type, size_bytes, storage_key,
                 version, created_at, updated_at)
            VALUES (?, ?, ?, ?, ?, ?, 1, NOW(3), NOW(3))
            """;

    static final String UPDATE_CONTENT = """
            UPDATE p2_attachment
               SET original_name = ?, media_type = ?, size_bytes = ?, storage_key = ?,
                   version = version + 1, updated_at = NOW(3)
             WHERE attachment_id = ? AND version = ?
            """;

    /**
     * 列表行的映射：刻意不读 {@code storage_key}。
     *
     * <p>列表 SQL 本来就不选这一列，用同一套全字段映射会直接报 "Column 'storage_key' not found"；
     * 分开两个 mapper 之后，列表连"顺手带出存储路径"的机会都没有。</p>
     */
    static final RowMapper<AttachmentRow> SUMMARY_MAPPER = (rs, row) -> new AttachmentRow(
            rs.getLong("attachment_id"),
            rs.getString("owner_type"),
            String.valueOf(rs.getLong("owner_id")),
            rs.getString("file_kind"),
            rs.getString("original_name"),
            rs.getString("media_type"),
            rs.getLong("size_bytes"),
            null,
            rs.getLong("version"),
            toOffsetDateTime(rs.getTimestamp("updated_at")));

    static final RowMapper<AttachmentRow> ROW_MAPPER = (rs, row) -> new AttachmentRow(
            rs.getLong("attachment_id"),
            rs.getString("owner_type"),
            String.valueOf(rs.getLong("owner_id")),
            rs.getString("file_kind"),
            rs.getString("original_name"),
            rs.getString("media_type"),
            rs.getLong("size_bytes"),
            rs.getString("storage_key"),
            rs.getLong("version"),
            toOffsetDateTime(rs.getTimestamp("updated_at")));

    private final JdbcTemplate jdbc;

    public AttachmentRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 归属类型到外键列的映射。
     * 只认契约列出的四种；其余取值在服务层已被拦下，到这里仍抛 400 作为第二道闸。
     */
    static String ownerColumn(String ownerType) {
        if (AttachmentModels.OWNER_EQUIPMENT.equals(ownerType)) {
            return "equipment_id";
        }
        if (AttachmentModels.OWNER_CONTRACT.equals(ownerType)) {
            return "contract_id";
        }
        if (AttachmentModels.OWNER_ARTICLE.equals(ownerType)) {
            return "article_id";
        }
        if (AttachmentModels.OWNER_REPAIR.equals(ownerType)) {
            return "repair_order_id";
        }
        if (AttachmentModels.OWNER_INSPECTION.equals(ownerType)) {
            return "inspection_id";
        }
        throw BusinessException.invalid("ownerType",
                "附件归属类型只能是 " + String.join("、", AttachmentModels.OWNER_TYPES) + " 之一。");
    }

    /** 归属类型到"归属对象所在表"的映射，同样只认白名单。 */
    static String ownerTable(String ownerType) {
        if (AttachmentModels.OWNER_EQUIPMENT.equals(ownerType)) {
            return "dim_equipment";
        }
        if (AttachmentModels.OWNER_CONTRACT.equals(ownerType)) {
            return "p2_maintenance_contract";
        }
        if (AttachmentModels.OWNER_ARTICLE.equals(ownerType)) {
            return "p2_knowledge_article";
        }
        if (AttachmentModels.OWNER_REPAIR.equals(ownerType)) {
            return "p2_repair_order";
        }
        if (AttachmentModels.OWNER_INSPECTION.equals(ownerType)) {
            return "p2_inspection";
        }
        throw BusinessException.invalid("ownerType",
                "附件归属类型只能是 " + String.join("、", AttachmentModels.OWNER_TYPES) + " 之一。");
    }

    /** 归属对象是否存在。不存在时上传要报 404，而不是靠外键抛 500。 */
    public boolean ownerExists(String ownerType, long ownerId) {
        String sql = "SELECT COUNT(*) FROM " + ownerTable(ownerType) + " WHERE "
                + ownerColumn(ownerType) + " = ?";
        Long total = jdbc.queryForObject(sql, Long.class, ownerId);
        return total != null && total > 0;
    }

    public List<AttachmentRow> search(AttachmentQuery query) {
        List<Object> args = new ArrayList<>();
        String sql = summaryTemplate(OWNER_TYPE_EXPR, OWNER_ID_EXPR) + where(query, args)
                + " ORDER BY a.created_at DESC, a.attachment_id DESC LIMIT ? OFFSET ?";
        args.add(query.pageSize());
        args.add((long) (query.page() - 1) * query.pageSize());
        return jdbc.query(sql, SUMMARY_MAPPER, args.toArray());
    }

    public long count(AttachmentQuery query) {
        List<Object> args = new ArrayList<>();
        String sql = "SELECT COUNT(*) FROM p2_attachment a " + where(query, args);
        Long total = jdbc.queryForObject(sql, Long.class, args.toArray());
        return total == null ? 0L : total;
    }

    static String where(AttachmentQuery query, List<Object> args) {
        // 用普通字符串拼：text block 会吃掉共同缩进，拼接后可能粘成 "... = ?AND ..."。
        StringBuilder sql = new StringBuilder(" WHERE a.").append(ownerColumn(query.ownerType()))
                .append(" = ?");
        args.add(query.ownerId());
        if (query.fileKind() != null) {
            sql.append(" AND a.file_kind = ?");
            args.add(query.fileKind());
        }
        return sql.toString();
    }

    public Optional<AttachmentRow> findRow(long attachmentId) {
        return jdbc.query(SELECT_ROW, ROW_MAPPER, attachmentId).stream().findFirst();
    }

    /** 替换内容前锁定行，版本比对与写入之间不允许被其他事务插队。 */
    public Optional<AttachmentRow> lockRow(long attachmentId) {
        return jdbc.query(SELECT_ROW + " FOR UPDATE", ROW_MAPPER, attachmentId).stream().findFirst();
    }

    /** 插入一条归属唯一的附件；只写一个外键列，其余保持 NULL，符合表上的 CHECK 约束。 */
    public List<String> articleReferences(long id) {
        return jdbc.query("SELECT attachment_id FROM p2_knowledge_attachment WHERE article_id=? ORDER BY display_order,attachment_id", (rs,n) -> rs.getString(1), id);
    }
    public int lockArticle(long id) {
        return jdbc.queryForObject("SELECT version FROM p2_knowledge_article WHERE article_id=? FOR UPDATE", Integer.class, id);
    }
    public void linkArticle(long articleId, long attachmentId) {
        Integer next = jdbc.queryForObject("SELECT COALESCE(MAX(display_order),-1)+1 FROM p2_knowledge_attachment WHERE article_id=?", Integer.class, articleId);
        jdbc.update("INSERT INTO p2_knowledge_attachment(article_id,attachment_id,display_order) VALUES(?,?,?)",articleId,attachmentId,next);
        jdbc.update("UPDATE p2_knowledge_article SET version=version+1,updated_at=NOW(3) WHERE article_id=?",articleId);
    }
    public long insert(String ownerType, long ownerId, String fileKind, String originalName,
                       String mediaType, long sizeBytes, String storageKey) {
        String sql = INSERT_TEMPLATE.formatted(ownerColumn(ownerType));
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            PreparedStatement ps = connection.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS);
            ps.setLong(1, ownerId);
            ps.setString(2, fileKind);
            ps.setString(3, originalName);
            ps.setString(4, mediaType);
            ps.setLong(5, sizeBytes);
            ps.setString(6, storageKey);
            return ps;
        }, keyHolder);
        Number key = keyHolder.getKey();
        if (key == null) {
            throw new IllegalStateException("附件主键未返回。");
        }
        return key.longValue();
    }

    /** 按版本条件更新内容；返回 0 表示版本已被推进，由调用方转成 409。 */
    public int updateContent(long attachmentId, String originalName, String mediaType,
                             long sizeBytes, String storageKey, long expectedVersion) {
        return jdbc.update(UPDATE_CONTENT, originalName, mediaType, sizeBytes, storageKey,
                attachmentId, expectedVersion);
    }

    /** 库里的 DATETIME 统一按东八区解释，与其它模块的时间口径保持一致。 */
    static OffsetDateTime toOffsetDateTime(Timestamp value) {
        return value == null ? null : value.toLocalDateTime().atZone(ZONE).toOffsetDateTime();
    }
}
