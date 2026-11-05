package cn.copper.phase2.attachment;

import cn.copper.phase2.common.BusinessException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * 附件的本机文件存储。
 *
 * <p>只做三件事：校验上传内容、把字节落到磁盘、按 storage_key 找回。文件名一律由服务端生成
 * （{@code yyyy/MM/uuid}），用户提供的文件名只作为 original_name 存库和展示，
 * 不进路径——这是防路径穿越最省事也最可靠的做法，不依赖对 {@code ../} 的字符串过滤。</p>
 *
 * <p><b>与事务的关系</b>：文件系统不参与数据库事务。写入时注册"回滚即删除"，
 * 替换成功后注册"提交后再删旧文件"。顺序保证任何时刻至少有一份可用文件：
 * 新文件先落盘 → 数据库提交 → 才删旧文件。中途失败时数据库仍指向旧文件，
 * 新文件被清掉，不会出现"元信息在、内容没了"。</p>
 */
@Component
public class AttachmentStorage {

    /** 允许用户上传的媒体类型，按 fileKind 分组。表里没有的类型一律拒绝，而不是存下来再猜。 */
    private static final Map<String, List<String>> ALLOWED_TYPES = Map.of(
            AttachmentModels.KIND_IMAGE, List.of(
                    "image/jpeg", "image/png", "image/webp", "image/gif", "image/bmp"),
            AttachmentModels.KIND_PHOTO, List.of(
                    "image/jpeg", "image/png", "image/webp", "image/gif", "image/bmp"),
            AttachmentModels.KIND_MANUAL, List.of(
                    "application/pdf", "image/jpeg", "image/png", "image/webp"),
            AttachmentModels.KIND_DOCUMENT, List.of(
                    "application/pdf", "text/plain", "text/csv",
                    "application/msword", "application/vnd.ms-excel",
                    "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
                    "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"));

    /** 扩展名兜底：浏览器对 .md/.log 之类常给 application/octet-stream，按其真实类型放行。 */
    private static final Map<String, String> BY_EXTENSION = Map.ofEntries(
            Map.entry("jpg", "image/jpeg"), Map.entry("jpeg", "image/jpeg"),
            Map.entry("png", "image/png"), Map.entry("webp", "image/webp"),
            Map.entry("gif", "image/gif"), Map.entry("bmp", "image/bmp"),
            Map.entry("pdf", "application/pdf"), Map.entry("txt", "text/plain"),
            Map.entry("csv", "text/csv"), Map.entry("doc", "application/msword"),
            Map.entry("docx", "application/vnd.openxmlformats-officedocument.wordprocessingml.document"),
            Map.entry("xls", "application/vnd.ms-excel"),
            Map.entry("xlsx", "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"));

    /** 服务端生成的相对路径形态；读取时先按这个形状校验，再做目录越界兜底。 */
    private static final Pattern STORAGE_KEY = Pattern.compile("^\\d{4}/\\d{2}/[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$");

    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");

    /** 一次上传的静态事实：这些值要参与幂等比对，所以必须在写盘之前算好。 */
    public record FileFacts(String originalName, String mediaType, long size, String sha256) {}

    private final Path root;
    private final long maxFileSize;
    private final String maxFileSizeText;

    public AttachmentStorage(@Value("${phase2.attachment.storage-root:./.local/attachments}") String storageRoot,
                             @Value("${phase2.attachment.max-file-size:20971520}") long maxFileSize) {
        this.root = Paths.get(storageRoot).toAbsolutePath().normalize();
        this.maxFileSize = maxFileSize;
        this.maxFileSizeText = describeSize(maxFileSize);
    }

    /** 供日志与错误提示使用的上限描述，例如 "20 MB"。 */
    public String maxFileSizeText() {
        return maxFileSizeText;
    }

    /**
     * 校验并读取上传内容的基本事实。
     *
     * <p>会完整读一遍文件算摘要，但不再重写一份副本；真正的落盘在 {@link #persist}。
     * 两步分开是为了让幂等判断发生在写盘之前——重放请求不应该在磁盘上留下第二份文件。</p>
     */
    public FileFacts inspect(MultipartFile file, String fileKind) {
        FileFacts facts = inspectAnyKind(file);
        assertAllowedType(fileKind, facts.mediaType());
        return facts;
    }

    /**
     * 只做与类别无关的检查（非空、大小、文件名、媒体类型）。
     *
     * <p>替换附件时类别沿用原附件，而原附件要加锁之后才读得到，所以先做这一步；
     * 按类别校验留在锁内进行，避免"照片位被换成了合同正文"。</p>
     */
    public FileFacts inspectAnyKind(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw BusinessException.invalid("file", "请选择一个非空文件再上传。");
        }
        if (file.getSize() > maxFileSize) {
            throw BusinessException.invalid("file",
                    "文件大小为 " + describeSize(file.getSize()) + "，超过上限 " + maxFileSizeText + "。");
        }
        String originalName = normalizeFileName(file.getOriginalFilename());
        String mediaType = resolveMediaType(file.getContentType(), originalName);
        if (mediaType.isEmpty()) {
            throw BusinessException.invalid("file",
                    "无法识别文件类型，请使用图片、PDF、Word、Excel 或纯文本文件。");
        }
        return new FileFacts(originalName, mediaType, file.getSize(), sha256(file));
    }

    /**
     * 把文件写入存储目录，返回 storage_key。
     * 写入失败会删掉半成品；事务回滚时注册的回调同样会删掉它。
     */
    public String persist(MultipartFile file) {
        LocalDate today = LocalDate.now(ZONE);
        String key = "%04d/%02d/%s".formatted(today.getYear(), today.getMonthValue(),
                UUID.randomUUID());
        Path target = locate(key);
        try {
            Files.createDirectories(target.getParent());
            try (InputStream input = file.getInputStream();
                 OutputStream output = Files.newOutputStream(target,
                         StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING,
                         StandardOpenOption.WRITE)) {
                input.transferTo(output);
            }
        } catch (IOException error) {
            deleteQuietly(target);
            throw new BusinessException(cn.copper.phase2.common.ErrorCode.INTERNAL_ERROR,
                    "文件保存失败，请稍后重试。");
        }
        deleteOnRollback(key);
        return key;
    }

    /** 按 storage_key 定位真实文件；越界或格式非法一律视为不存在，不回显路径。 */
    public Path locate(String storageKey) {
        if (storageKey == null || !STORAGE_KEY.matcher(storageKey).matches()) {
            throw BusinessException.notFound("附件文件不存在，请重新上传。");
        }
        Path path = root.resolve(storageKey).normalize();
        if (!path.startsWith(root)) {
            throw BusinessException.notFound("附件文件不存在，请重新上传。");
        }
        return path;
    }

    /** 元信息存在但文件被误删的情况要能分开报告，所以这里只回答"在不在"，不抛异常。 */
    public boolean exists(String storageKey) {
        try {
            return Files.isRegularFile(locate(storageKey));
        } catch (BusinessException error) {
            return false;
        }
    }

    public long sizeOf(String storageKey) throws IOException {
        return Files.size(locate(storageKey));
    }

    public InputStream open(String storageKey) throws IOException {
        return Files.newInputStream(locate(storageKey));
    }

    /** 事务提交后再删旧文件；没有事务上下文时直接删。 */
    public void deleteAfterCommit(String storageKey) {
        if (storageKey == null) {
            return;
        }
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            deleteIfLocatable(storageKey);
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                deleteIfLocatable(storageKey);
            }
        });
    }

    private void deleteOnRollback(String storageKey) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCompletion(int status) {
                if (status != STATUS_COMMITTED) {
                    deleteIfLocatable(storageKey);
                }
            }
        });
    }

    /** 清理回调里不能再抛业务异常，否则会把已完成的提交报成失败。 */
    private void deleteIfLocatable(String storageKey) {
        try {
            deleteQuietly(locate(storageKey));
        } catch (BusinessException ignored) {
            // 非服务端生成的 key 不会落到真实文件上，无需处理。
        }
    }

    private static void deleteQuietly(Path path) {
        try {
            Files.deleteIfExists(path);
        } catch (IOException ignored) {
            // 清理失败不应盖住原始错误：残留文件由运维按目录巡检处理，业务响应仍以主流程为准。
        }
    }

    /** 原始文件名只留最后一段，去掉客户端可能带上的完整路径，并限制长度。 */
    static String normalizeFileName(String raw) {
        String value = raw == null ? "" : raw.trim();
        int slash = Math.max(value.lastIndexOf('/'), value.lastIndexOf('\\'));
        if (slash >= 0) {
            value = value.substring(slash + 1);
        }
        value = value.replaceAll("[\\p{Cntrl}]", "").trim();
        if (value.isEmpty()) {
            return "attachment";
        }
        if (value.length() > AttachmentModels.ORIGINAL_NAME_MAX) {
            String extension = "";
            int dot = value.lastIndexOf('.');
            if (dot > 0 && value.length() - dot <= 16) {
                extension = value.substring(dot);
            }
            value = value.substring(0, AttachmentModels.ORIGINAL_NAME_MAX - extension.length()) + extension;
        }
        return value;
    }

    /** Content-Type 去掉 charset 等参数；空值或 octet-stream 时退回按扩展名判断。 */
    static String resolveMediaType(String declared, String originalName) {
        String type = declared == null ? "" : declared.split(";", 2)[0].trim().toLowerCase(Locale.ROOT);
        if (type.isEmpty() || "application/octet-stream".equals(type) || "binary/octet-stream".equals(type)) {
            String extension = extensionOf(originalName);
            type = extension == null ? "" : BY_EXTENSION.getOrDefault(extension, "");
        }
        return type.length() > AttachmentModels.MEDIA_TYPE_MAX
                ? type.substring(0, AttachmentModels.MEDIA_TYPE_MAX)
                : type;
    }

    /** 按 fileKind 校验媒体类型；不通过时报 400 并说明允许哪些类型，而不是静默存下来。 */
    static void assertAllowedType(String fileKind, String mediaType) {
        List<String> allowed = ALLOWED_TYPES.getOrDefault(fileKind, List.of());
        if (!allowed.contains(mediaType)) {
            throw BusinessException.invalid("file",
                    "「" + fileKind + "」不接受该文件类型，允许：" + String.join("、", allowed) + "。");
        }
    }

    private static String extensionOf(String name) {
        if (name == null) {
            return null;
        }
        int dot = name.lastIndexOf('.');
        return dot < 0 || dot == name.length() - 1
                ? null
                : name.substring(dot + 1).toLowerCase(Locale.ROOT);
    }

    private static String sha256(MultipartFile file) {
        try (DigestInputStream input =
                     new DigestInputStream(file.getInputStream(), MessageDigest.getInstance("SHA-256"))) {
            input.transferTo(OutputStream.nullOutputStream());
            return HexFormat.of().formatHex(input.getMessageDigest().digest());
        } catch (IOException error) {
            throw BusinessException.invalid("file", "文件读取失败，请重新选择后再上传。");
        } catch (NoSuchAlgorithmException error) {
            throw new IllegalStateException("运行环境缺少 SHA-256 支持。");
        }
    }

    static String describeSize(long bytes) {
        if (bytes >= 1024 * 1024) {
            return "%.1f MB".formatted(bytes / 1024.0 / 1024.0);
        }
        if (bytes >= 1024) {
            return "%.0f KB".formatted(bytes / 1024.0);
        }
        return bytes + " B";
    }
}
