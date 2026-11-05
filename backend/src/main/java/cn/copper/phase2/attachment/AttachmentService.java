package cn.copper.phase2.attachment;

import cn.copper.phase2.attachment.AttachmentModels.AttachmentDetail;
import cn.copper.phase2.attachment.AttachmentModels.AttachmentRow;
import cn.copper.phase2.attachment.AttachmentModels.AttachmentSummary;
import cn.copper.phase2.attachment.AttachmentRepository.AttachmentQuery;
import cn.copper.phase2.common.BusinessException;
import cn.copper.phase2.common.PageResult;
import org.springframework.stereotype.Service;

import java.nio.file.Path;
import java.util.List;

/**
 * S1-F01 / F02 / F04 只读查询。
 *
 * <p>三处口径都在这里统一：归属类型只认契约枚举、分页参数越界直接 400、
 * "元信息在但文件丢了"单独报 404 而不伪装成"已上传"。上层（合同详情、知识页）
 * 都复用同一份读取逻辑，不各自拼一套。</p>
 */
@Service
public class AttachmentService {
    private static final int DEFAULT_PAGE_SIZE = 20;
    private static final int MAX_PAGE_SIZE = 100;
    private static final String CONTENT_PREFIX = "/api/v1/attachments/";

    private final AttachmentRepository repository;
    private final AttachmentStorage storage;

    public AttachmentService(AttachmentRepository repository, AttachmentStorage storage) {
        this.repository = repository;
        this.storage = storage;
    }

    /** F01 分页；fileKind 为空表示不限类别。 */
    public PageResult<AttachmentSummary> search(String ownerType, String ownerId, String fileKind,
                                                Integer page, Integer pageSize) {
        String resolvedOwnerType = resolveOwnerType(ownerType);
        long resolvedOwnerId = parsePositiveId(ownerId, "归属对象");
        String resolvedKind = resolveFileKind(fileKind);
        int resolvedPage = resolvePage(page);
        int resolvedSize = resolvePageSize(pageSize);

        AttachmentQuery query = new AttachmentQuery(resolvedOwnerType, resolvedOwnerId,
                resolvedKind, resolvedPage, resolvedSize);
        List<AttachmentSummary> items = repository.search(query).stream()
                .map(AttachmentService::toSummary)
                .toList();
        return PageResult.of(items, resolvedPage, resolvedSize, repository.count(query));
    }

    /** F02 元信息；不返回 storage_key，只给受控查看/下载地址。 */
    public AttachmentDetail detail(String attachmentId) {
        return detail(parseAttachmentId(attachmentId));
    }

    AttachmentDetail detail(long attachmentId) {
        return toDetail(find(attachmentId));
    }

    /** F04 需要的三样东西：元信息（媒体类型、文件名）、真实路径、长度。 */
    public AttachmentContent content(String attachmentId) {
        AttachmentRow row = find(parseAttachmentId(attachmentId));
        Path path = storage.locate(row.storageKey());
        try {
            return new AttachmentContent(row, path, storage.sizeOf(row.storageKey()));
        } catch (java.io.IOException error) {
            // 元信息与文件是两件事，契约要求分别报告：这里不能让页面以为文件还在。
            throw BusinessException.notFound("附件记录存在，但存储中的文件已丢失，请联系管理员检查附件目录。");
        }
    }

    public record AttachmentContent(AttachmentRow row, Path path, long size) {}

    /** 打开文件流；由控制器负责包装成响应体并关闭。 */
    public java.io.InputStream openStream(AttachmentContent content) throws java.io.IOException {
        return storage.open(content.row().storageKey());
    }

    private AttachmentRow find(long attachmentId) {
        return repository.findRow(attachmentId)
                .orElseThrow(() -> BusinessException.notFound("附件不存在或已被删除。"));
    }

    static AttachmentSummary toSummary(AttachmentRow row) {
        String content = contentPath(row.attachmentId());
        return new AttachmentSummary(String.valueOf(row.attachmentId()), row.ownerType(),
                row.ownerId(), row.fileKind(), row.originalName(), row.mediaType(),
                row.sizeBytes(), row.version(), row.updatedAt(), content, content + "?download=true");
    }

    static AttachmentDetail toDetail(AttachmentRow row) {
        String content = contentPath(row.attachmentId());
        return new AttachmentDetail(String.valueOf(row.attachmentId()), row.ownerType(),
                row.ownerId(), row.fileKind(), row.originalName(), row.mediaType(),
                row.sizeBytes(), row.version(), row.updatedAt(), content, content + "?download=true");
    }

    static String contentPath(long attachmentId) {
        return CONTENT_PREFIX + attachmentId + "/content";
    }

    /** 归属类型必填，且只能是契约枚举；大小写宽容，存库与出参统一用大写。 */
    static String resolveOwnerType(String raw) {
        if (raw == null || raw.isBlank()) {
            throw BusinessException.invalid("ownerType",
                    "必须指定附件归属类型：" + String.join("、", AttachmentModels.OWNER_TYPES) + "。");
        }
        String value = raw.trim().toUpperCase();
        if (!AttachmentModels.OWNER_TYPES.contains(value)) {
            throw BusinessException.invalid("ownerType",
                    "附件归属类型只能是 " + String.join("、", AttachmentModels.OWNER_TYPES) + " 之一。");
        }
        return value;
    }

    /** 文件类别可选；给了就必须合法，不把未知值当成"全部"。 */
    static String resolveFileKind(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String value = raw.trim().toUpperCase();
        if (!AttachmentModels.FILE_KINDS.contains(value)) {
            throw BusinessException.invalid("fileKind",
                    "附件类别只能是 " + String.join("、", AttachmentModels.FILE_KINDS) + " 之一。");
        }
        return value;
    }

    static long parseAttachmentId(String raw) {
        return parsePositiveId(raw, "附件");
    }

    static long parsePositiveId(String raw, String label) {
        try {
            long id = Long.parseLong(raw == null ? "" : raw.trim());
            if (id <= 0) {
                throw new NumberFormatException("非正整数");
            }
            return id;
        } catch (NumberFormatException error) {
            throw BusinessException.invalid("id", label + " ID 必须是正整数字符串。");
        }
    }

    static int resolvePage(Integer page) {
        if (page == null) {
            return 1;
        }
        if (page < 1) {
            throw BusinessException.invalid("page", "页码必须从 1 开始。");
        }
        return page;
    }

    static int resolvePageSize(Integer pageSize) {
        if (pageSize == null) {
            return DEFAULT_PAGE_SIZE;
        }
        if (pageSize < 1 || pageSize > MAX_PAGE_SIZE) {
            throw BusinessException.invalid("pageSize", "每页条数必须在 1 到 " + MAX_PAGE_SIZE + " 之间。");
        }
        return pageSize;
    }
}
