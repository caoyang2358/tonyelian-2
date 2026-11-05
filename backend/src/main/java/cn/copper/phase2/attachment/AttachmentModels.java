package cn.copper.phase2.attachment;

import java.time.OffsetDateTime;
import java.util.List;

/**
 * 共用附件（S1-F01—F05）的对外模型。
 *
 * <p>库里 {@code p2_attachment} 用四个可空外键表达"归属唯一"：设备、合同、知识条目、维修单各一列，
 * 约束保证恰好一列非空。对外统一说成 {@code ownerType + ownerId}，前端不需要知道列名，
 * 以后新增归属对象也不用改调用方。</p>
 *
 * <p>任何响应都不含 {@code storage_key}：那是服务器上的相对路径，属于实现细节，
 * 契约明确要求不返回服务器文件路径。查看和下载统一走 F04 的受控地址。</p>
 */
public final class AttachmentModels {
    private AttachmentModels() {
    }

    public static final String OWNER_EQUIPMENT = "EQUIPMENT";
    public static final String OWNER_CONTRACT = "MAINTENANCE_CONTRACT";
    public static final String OWNER_ARTICLE = "KNOWLEDGE_ARTICLE";
    public static final String OWNER_REPAIR = "REPAIR_ORDER";
    public static final List<String> OWNER_TYPES =
            List.of(OWNER_EQUIPMENT, OWNER_CONTRACT, OWNER_ARTICLE, OWNER_REPAIR);

    public static final String KIND_IMAGE = "IMAGE";
    public static final String KIND_MANUAL = "MANUAL";
    public static final String KIND_DOCUMENT = "DOCUMENT";
    public static final String KIND_PHOTO = "PHOTO";
    public static final List<String> FILE_KINDS =
            List.of(KIND_IMAGE, KIND_MANUAL, KIND_DOCUMENT, KIND_PHOTO);

    /** 文件名、媒体类型、大小这类描述字段的上限，与库里列宽一致。 */
    public static final int ORIGINAL_NAME_MAX = 255;
    public static final int MEDIA_TYPE_MAX = 127;

    /**
     * F01 列表行。
     *
     * <p>{@code size} 用契约里的字段名（字节数），不写 {@code sizeBytes}。
     * {@code contentPath} 是同源受控地址，前端直接用来预览，不拼服务器路径。</p>
     */
    public record AttachmentSummary(String attachmentId, String ownerType, String ownerId,
                                    String fileKind, String originalName, String mediaType, long size,
                                    long version, OffsetDateTime updatedAt,
                                    String contentPath, String downloadPath) {}

    /** F02 详情：在列表行基础上只补"归属对象"的展示标签，不新增可写字段。 */
    public record AttachmentDetail(String attachmentId, String ownerType, String ownerId,
                                   String fileKind, String originalName, String mediaType, long size,
                                   long version, OffsetDateTime updatedAt,
                                   String contentPath, String downloadPath) {}

    /**
     * F03 上传：契约要求 multipart 带 W（requestId、operatorName）以及 ownerType/ownerId/fileKind。
     *
     * <p>{@code contentSha256} 参与幂等比对——同一 requestId 重放同一份文件返回首次结果，
     * 换一份文件则判为"同号不同内容"。用摘要而不是文件名，避免同名不同内容被误判成重放。</p>
     */
    public record AttachmentUploadRequest(String requestId, String operatorName, String ownerType,
                                          String ownerId, String fileKind, String originalName,
                                          String mediaType, long size, String contentSha256) {}

    /** F05 替换：U 语义，带 expectedVersion；{@code reason} 作为替换依据写入留痕。 */
    public record AttachmentReplaceRequest(String requestId, String operatorName, long expectedVersion,
                                           String reason, String originalName, String mediaType,
                                           long size, String contentSha256) {}

    /** 数据库里的一行（含 storage_key，仅供服务内部与仓储层使用，不出接口）。 */
    public record AttachmentRow(long attachmentId, String ownerType, String ownerId, String fileKind,
                                String originalName, String mediaType, long sizeBytes,
                                String storageKey, long version, OffsetDateTime updatedAt) {}
}
