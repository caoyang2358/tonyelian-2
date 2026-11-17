package cn.copper.phase2.attachment;

import cn.copper.phase2.common.BusinessException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.web.MockMultipartFile;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
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

        String message = fields(error).get("file");
        assertTrue(message.contains("设备配图"), message);
        // 报错面向使用者：不暴露 IMAGE 这类内部枚举，也不甩 MIME 串。
        assertFalse(message.contains("application/"), message);
    }

    /**
     * 可执行文件按扩展名直接拒，理由说成"安全考虑"而不是"类型不在白名单"。
     *
     * <p>这一条不能只依赖白名单：白名单能挡住，但报错会变成一串 MIME，
     * 用户既看不懂也不知道该怎么办。</p>
     */
    @Test
    void executableFileIsRejectedWithReadableReason() {
        MockMultipartFile file = new MockMultipartFile(
                "file", "setup.exe", "application/x-msdownload", new byte[]{1});

        BusinessException error = assertThrows(BusinessException.class,
                () -> storage(1024).inspect(file, "DOCUMENT"));

        String message = fields(error).get("file");
        assertTrue(message.contains("可执行文件"), message);
        assertTrue(message.contains(".exe"), message);
    }

    /** 把 .exe 改名成 .pdf 也挡得住：扩展名过了，Content-Type 仍指向可执行文件。 */
    @Test
    void executableRenamedAsPdfIsStillRejected() {
        MockMultipartFile file = new MockMultipartFile(
                "file", "合同附件.pdf", "application/x-msdownload", new byte[]{1});

        BusinessException error = assertThrows(BusinessException.class,
                () -> storage(1024).inspect(file, "DOCUMENT"));

        String message = fields(error).get("file");
        assertTrue(message.contains("可执行"), message);
        // 要说清"改扩展名不算"，否则用户会以为系统连 PDF 都不认。
        assertTrue(message.contains("扩展名"), message);
    }

    /** 设备手册只收 PDF 与图片；传 Word 时要指出去处，否则用户只看到"不收"却不知道该改选哪类。 */
    @Test
    void manualRejectsWordAndPointsToDocumentKind() {
        MockMultipartFile file = new MockMultipartFile("file", "说明书.docx",
                "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
                new byte[]{1});

        BusinessException error = assertThrows(BusinessException.class,
                () -> storage(1024).inspect(file, "MANUAL"));

        String message = fields(error).get("file");
        assertTrue(message.contains("设备手册"), message);
        assertTrue(message.contains("文档资料"), message);
    }

    /** 文档资料类要覆盖 Office 的四种扩展名，它们是设备台账里最常见的附件。 */
    @Test
    void documentKindAcceptsWordAndExcelVariants() {
        AttachmentStorage storage = storage(1024);

        assertEquals("application/msword", storage.inspect(new MockMultipartFile(
                "file", "a.doc", "application/msword", new byte[]{1}), "DOCUMENT").mediaType());
        assertEquals("application/vnd.ms-excel", storage.inspect(new MockMultipartFile(
                "file", "b.xls", "application/vnd.ms-excel", new byte[]{1}), "DOCUMENT").mediaType());
        assertTrue(storage.inspect(new MockMultipartFile("file", "c.docx",
                        "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
                        new byte[]{1}), "DOCUMENT").mediaType().endsWith("wordprocessingml.document"));
        assertTrue(storage.inspect(new MockMultipartFile("file", "d.xlsx",
                        "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
                        new byte[]{1}), "DOCUMENT").mediaType().endsWith("spreadsheetml.sheet"));
    }

    /** 报错文案必须能翻译所有放行类型；漏了翻译会退回显示 MIME，等于又变成看不懂的提示。 */
    @Test
    void everyAllowedTypeHasReadableLabel() {
        for (String kind : List.of("IMAGE", "MANUAL", "DOCUMENT", "PHOTO")) {
            String text = AttachmentStorage.describeAllowed(kind);
            assertFalse(text.contains("application/"), kind + "：" + text);
            assertFalse(text.contains("image/"), kind + "：" + text);
        }
    }

    /** doc 与 docx 同叫"Word 文档"、xls 与 xlsx 同叫"Excel 表格"，同一个名字不能在清单里念两遍。 */
    @Test
    void allowedFormatsAreListedOnce() {
        String text = AttachmentStorage.describeAllowed("DOCUMENT");

        assertEquals(text.indexOf("Word 文档"), text.lastIndexOf("Word 文档"), text);
        assertEquals(text.indexOf("Excel 表格"), text.lastIndexOf("Excel 表格"), text);
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
