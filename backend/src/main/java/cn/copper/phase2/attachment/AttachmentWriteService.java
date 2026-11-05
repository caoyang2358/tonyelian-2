package cn.copper.phase2.attachment;

import cn.copper.phase2.attachment.AttachmentModels.AttachmentDetail;
import cn.copper.phase2.attachment.AttachmentModels.AttachmentReplaceRequest;
import cn.copper.phase2.attachment.AttachmentModels.AttachmentRow;
import cn.copper.phase2.attachment.AttachmentModels.AttachmentUploadRequest;
import cn.copper.phase2.attachment.AttachmentStorage.FileFacts;
import cn.copper.phase2.common.BusinessException;
import cn.copper.phase2.common.ChangeLogModels.ChangeLogWrite;
import cn.copper.phase2.common.ChangeLogRepository;
import cn.copper.phase2.common.IdempotencyService;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * S1-F03 上传 / S1-F05 替换。
 *
 * <p>三条硬约束决定了这里的写法：</p>
 * <ul>
 *   <li><b>幂等先于写盘</b>：先算计文件摘要，重放的请求不会再落第二份文件；</li>
 *   <li><b>失败不留垃圾</b>：写盘后若事务回滚，注册的清理回调会删掉新文件；</li>
 *   <li><b>替换不丢原件</b>：新文件先落盘、数据库提交后才删旧文件，
 *       任何一步失败都还能读到原内容。</li>
 * </ul>
 *
 * <p>附件本身不提供删除接口：契约 F01—F05 没有 DELETE，且附件一旦被合同或知识页引用，
 * 删除会让那些引用悬空。</p>
 */
@Service
public class AttachmentWriteService {
    private static final String OBJECT_TYPE = "ATTACHMENT";
    private static final int OPERATOR_MAX = 100;
    private static final int REASON_MAX = 1000;

    private final AttachmentRepository repository;
    private final AttachmentStorage storage;
    private final ChangeLogRepository changeLog;
    private final IdempotencyService idempotency;
    private final AttachmentService reader;

    public AttachmentWriteService(AttachmentRepository repository, AttachmentStorage storage,
                                  ChangeLogRepository changeLog, IdempotencyService idempotency,
                                  AttachmentService reader) {
        this.repository = repository;
        this.storage = storage;
        this.changeLog = changeLog;
        this.idempotency = idempotency;
        this.reader = reader;
    }

    /** F03 上传并关联到已有业务对象。业务对象必须先存在，附件不代建对象。 */
    public AttachmentDetail upload(String requestId, String operatorName, String ownerType,
                                   String ownerId, String fileKind, MultipartFile file) {
        String resolvedOwnerType = AttachmentService.resolveOwnerType(ownerType);
        long resolvedOwnerId = AttachmentService.parsePositiveId(ownerId, "归属对象");
        String resolvedKind = AttachmentService.resolveFileKind(fileKind);
        if (resolvedKind == null) {
            throw BusinessException.invalid("fileKind",
                    "必须指定附件类别：" + String.join("、", AttachmentModels.FILE_KINDS) + " 之一。");
        }
        String resolvedOperator = requireOperator(operatorName);

        FileFacts facts = storage.inspect(file, resolvedKind);
        AttachmentUploadRequest request = new AttachmentUploadRequest(requestId, resolvedOperator,
                resolvedOwnerType, String.valueOf(resolvedOwnerId), resolvedKind,
                facts.originalName(), facts.mediaType(), facts.size(), facts.sha256());

        return idempotency.execute(requestId, "ATTACHMENT_UPLOAD", request, AttachmentDetail.class,
                () -> doUpload(request, file));
    }

    private AttachmentDetail doUpload(AttachmentUploadRequest request, MultipartFile file) {
        long ownerId = Long.parseLong(request.ownerId());
        if (!repository.ownerExists(request.ownerType(), ownerId)) {
            throw BusinessException.notFound(ownerLabel(request.ownerType()) + "不存在，无法关联附件。");
        }
        Integer articleVersion = AttachmentModels.OWNER_ARTICLE.equals(request.ownerType()) ? repository.lockArticle(ownerId) : null;
        Object beforeReferences = articleVersion == null ? null : Map.of("attachmentIds", repository.articleReferences(ownerId));
        String storageKey = storage.persist(file);
        long attachmentId = repository.insert(request.ownerType(), ownerId, request.fileKind(),
                request.originalName(), request.mediaType(), request.size(), storageKey);

        if (articleVersion != null) {
            repository.linkArticle(ownerId, attachmentId);
            changeLog.insert(new ChangeLogWrite("KNOWLEDGE_ARTICLE",request.ownerId(),"UPDATE",beforeReferences,
                Map.of("attachmentIds", repository.articleReferences(ownerId)),"上传文章配图",request.operatorName(),articleVersion,articleVersion+1,request.requestId()));
        }
        changeLog.insert(new ChangeLogWrite(OBJECT_TYPE, String.valueOf(attachmentId), "CREATE",
                null, snapshot(request.ownerType(), request.ownerId(), request.fileKind(),
                        request.originalName(), request.mediaType(), request.size(), 1),
                "上传附件", request.operatorName(), 0, 1, request.requestId()));
        return reader.detail(attachmentId);
    }

    /** F05 替换内容：保留附件 ID 与全部引用，只推进版本。 */
    public AttachmentDetail replace(String attachmentId, String requestId, String operatorName,
                                    String expectedVersion, String reason, MultipartFile file) {
        long id = AttachmentService.parseAttachmentId(attachmentId);
        long version = parseExpectedVersion(expectedVersion);
        String resolvedOperator = requireOperator(operatorName);
        String resolvedReason = requireReason(reason);

        // 替换不改变 fileKind，所以按原附件的类别校验类型；原类别在读锁之后才知道，
        // 这里先用宽松的"文档类"预检不现实——改为在 doReplace 里按原类别再校验一次。
        FileFacts facts = storage.inspectAnyKind(file);
        AttachmentReplaceRequest request = new AttachmentReplaceRequest(requestId, resolvedOperator,
                version, resolvedReason, facts.originalName(), facts.mediaType(), facts.size(),
                facts.sha256());

        return idempotency.execute(requestId, "ATTACHMENT_REPLACE:" + id, request,
                AttachmentDetail.class, () -> doReplace(id, request, file));
    }

    private AttachmentDetail doReplace(long attachmentId, AttachmentReplaceRequest request,
                                       MultipartFile file) {
        AttachmentRow current = repository.lockRow(attachmentId)
                .orElseThrow(() -> BusinessException.notFound("附件不存在或已被删除。"));
        if (current.version() != request.expectedVersion()) {
            throw BusinessException.versionConflict(request.expectedVersion(), current.version());
        }
        // 类别沿用原附件：照片位不能在这一步变成合同正文，否则列表按类别筛选会前后矛盾。
        AttachmentStorage.assertAllowedType(current.fileKind(), request.mediaType());

        String newKey = storage.persist(file);
        if (repository.updateContent(attachmentId, request.originalName(), request.mediaType(),
                request.size(), newKey, request.expectedVersion()) == 0) {
            throw BusinessException.versionConflict(request.expectedVersion(), current.version());
        }

        changeLog.insert(new ChangeLogWrite(OBJECT_TYPE, String.valueOf(attachmentId), "UPDATE",
                snapshot(current.ownerType(), current.ownerId(), current.fileKind(),
                        current.originalName(), current.mediaType(), current.sizeBytes(),
                        current.version()),
                snapshot(current.ownerType(), current.ownerId(), current.fileKind(),
                        request.originalName(), request.mediaType(), request.size(),
                        current.version() + 1),
                request.reason(), request.operatorName(),
                toVersion(current.version()), toVersion(current.version() + 1), request.requestId()));

        // 提交成功后才动旧文件：万一本事务回滚，原内容仍然可读。
        storage.deleteAfterCommit(current.storageKey());
        return reader.detail(attachmentId);
    }

    private static String requireOperator(String raw) {
        String value = raw == null ? "" : raw.trim();
        if (value.isEmpty()) {
            throw BusinessException.invalid("operatorName", "操作人为必填项。");
        }
        if (value.length() > OPERATOR_MAX) {
            throw BusinessException.invalid("operatorName", "操作人不得超过 " + OPERATOR_MAX + " 个字符。");
        }
        return value;
    }

    /** 替换是一次有意的覆盖，必须说明依据；契约把"替换附件"明确列为需 reason 的 U 操作。 */
    private static String requireReason(String raw) {
        String value = raw == null ? "" : raw.trim();
        if (value.isEmpty()) {
            throw BusinessException.invalid("reason", "替换附件时必须填写替换原因。");
        }
        if (value.length() > REASON_MAX) {
            throw BusinessException.invalid("reason", "替换原因不得超过 " + REASON_MAX + " 个字符。");
        }
        return value;
    }

    private static long parseExpectedVersion(String raw) {
        try {
            long version = Long.parseLong(raw == null ? "" : raw.trim());
            if (version < 0) {
                throw new NumberFormatException("负数");
            }
            return version;
        } catch (NumberFormatException error) {
            throw BusinessException.invalid("expectedVersion", "更新时必须提供非负的当前版本号。");
        }
    }

    private static String ownerLabel(String ownerType) {
        return switch (ownerType) {
            case AttachmentModels.OWNER_EQUIPMENT -> "设备";
            case AttachmentModels.OWNER_CONTRACT -> "维保合同";
            case AttachmentModels.OWNER_ARTICLE -> "知识条目";
            default -> "维修单";
        };
    }

    private static Map<String, Object> snapshot(String ownerType, String ownerId, String fileKind,
                                                String originalName, String mediaType, long size,
                                                long version) {
        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("ownerType", ownerType);
        snapshot.put("ownerId", ownerId);
        snapshot.put("fileKind", fileKind);
        snapshot.put("originalName", originalName);
        snapshot.put("mediaType", mediaType);
        snapshot.put("size", size);
        snapshot.put("version", version);
        return snapshot;
    }

    private static Integer toVersion(long version) {
        return version > Integer.MAX_VALUE ? null : Math.toIntExact(version);
    }
}
