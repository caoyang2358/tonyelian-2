package cn.copper.phase2.attachment;

import cn.copper.phase2.common.BusinessException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.web.MockMultipartFile;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 附件存储的边界：媒体类型判定、类别白名单、文件名清洗、路径不越界、大小上限。
 *
 * <p>这些点出错的后果都不是"显示不好看"，而是存进了不该存的类型、或者按用户提供的路径
 * 读写到了存储目录之外，所以逐条钉住。</p>
 */
class AttachmentStorageTest {

    @TempDir
    Path tempDir;

    private AttachmentStorage storage(long maxSize) {
        return new AttachmentStorage(tempDir.toString(), maxSize);
    }

    @Test
    void imageUploadKeepsDeclaredMediaType() {
        MockMultipartFile file = new MockMultipartFile(
                "file", "现场照片.PNG", "image/png", new byte[]{1, 2, 3});

        AttachmentStorage.FileFacts facts = storage(1024 * 1024).inspect(file, "IMAGE");

        assertEquals("image/png", facts.mediaType());
        assertEquals("现场照片.PNG", facts.originalName());
        assertEquals(3, facts.size());
        assertEquals(64, facts.sha256().length());
    }

    /** 浏览器给 .pdf 常常只报 application/octet-stream，必须按扩展名补回来，否则手册全被拒。 */
    @Test
    void octetStreamFallsBackToExtension() {
        MockMultipartFile file = new MockMultipartFile(
                "file", "manual.pdf", "application/octet-stream", new byte[]{1});

        AttachmentStorage.FileFacts facts = storage(1024).inspect(file, "MANUAL");

        assertEquals("application/pdf", facts.mediaType());
    }

    /** Content-Type 带 charset 时只取主类型，否则 "text/plain;charset=UTF-8" 永远匹配不上白名单。 */
    @Test
    void charsetParameterIsStripped() {
        MockMultipartFile file = new MockMultipartFile(
                "file", "note.txt", "text/plain; charset=UTF-8", new byte[]{1});

        assertEquals("text/plain", storage(1024).inspect(file, "DOCUMENT").mediaType());
    }

    @Test
    void imageKindRejectsDocument() {
        MockMultipartFile file = new MockMultipartFile(
                "file", "manual.pdf", "application/pdf", new byte[]{1});

        BusinessException error = assertThrows(BusinessException.class,
                () -> storage(1024).inspect(file, "IMAGE"));

        assertTrue(fields(error).get("file").contains("IMAGE"));
    }

    @Test
    void oversizedFileIsRejectedBeforeWriting() {
        MockMultipartFile file = new MockMultipartFile(
                "file", "big.png", "image/png", new byte[2048]);

        BusinessException error = assertThrows(BusinessException.class,
                () -> storage(1024).inspect(file, "IMAGE"));

        assertTrue(fields(error).get("file").contains("上限"));
    }

    @Test
    void emptyFileIsRejected() {
        MockMultipartFile file = new MockMultipartFile("file", "empty.png", "image/png", new byte[0]);

        BusinessException error = assertThrows(BusinessException.class,
                () -> storage(1024).inspect(file, "IMAGE"));

        assertEquals("请选择一个非空文件再上传。", fields(error).get("file"));
    }

    /** 客户端可能回传完整路径（旧版 IE 行为），落库前只保留最后一段。 */
    @Test
    void fileNameLosesClientPath() {
        assertEquals("a.png", AttachmentStorage.normalizeFileName("C:\\Users\\dev\\a.png"));
        assertEquals("b.pdf", AttachmentStorage.normalizeFileName("../../etc/b.pdf"));
        assertEquals("attachment", AttachmentStorage.normalizeFileName("   "));
    }

    @Test
    void longFileNameIsTruncatedKeepingExtension() {
        String name = "设备".repeat(200) + ".docx";
        String normalized = AttachmentStorage.normalizeFileName(name);

        assertTrue(normalized.length() <= AttachmentModels.ORIGINAL_NAME_MAX);
        assertTrue(normalized.endsWith(".docx"));
    }

    @Test
    void persistedFileIsLocatableAndInsideRoot() throws Exception {
        AttachmentStorage storage = storage(1024);
        MockMultipartFile file = new MockMultipartFile(
                "file", "a.png", "image/png", new byte[]{9, 8, 7});

        String key = storage.persist(file);

        assertTrue(key.matches("^\\d{4}/\\d{2}/[0-9a-f-]{36}$"), key);
        assertTrue(storage.exists(key));
        Path path = storage.locate(key);
        assertTrue(path.startsWith(tempDir.toAbsolutePath().normalize()));
        assertEquals(3, Files.size(path));
        assertArrayEquals(new byte[]{9, 8, 7}, Files.readAllBytes(path));
    }

    /** 路径穿越与畸形 key 一律当作不存在，绝不把服务器目录结构回显给调用方。 */
    @Test
    void traversalKeysAreRejected() {
        AttachmentStorage storage = storage(1024);

        for (String key : Set.of("../../etc/passwd", "2026/11/not-a-uuid", "", "/etc/passwd")) {
            BusinessException error = assertThrows(BusinessException.class, () -> storage.locate(key));
            assertEquals("NOT_FOUND", error.errorCode().name());
        }
        assertFalse(storage.exists("../../etc/passwd"));
    }

    @Test
    void unknownExtensionIsReported() {
        MockMultipartFile file = new MockMultipartFile(
                "file", "data.bin", "application/octet-stream", new byte[]{1});

        BusinessException error = assertThrows(BusinessException.class,
                () -> storage(1024).inspectAnyKind(file));

        assertTrue(fields(error).get("file").contains("无法识别"));
    }

    @SuppressWarnings("unchecked")
    private static java.util.Map<String, String> fields(BusinessException error) {
        Object nested = error.details().get("fields");
        return nested instanceof java.util.Map<?, ?> map
                ? (java.util.Map<String, String>) map
                : java.util.Map.of();
    }
}
