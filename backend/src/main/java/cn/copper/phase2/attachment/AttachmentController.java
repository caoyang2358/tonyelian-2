package cn.copper.phase2.attachment;

import cn.copper.phase2.attachment.AttachmentModels.AttachmentDetail;
import cn.copper.phase2.attachment.AttachmentModels.AttachmentSummary;
import cn.copper.phase2.common.ApiResponse;
import cn.copper.phase2.common.PageResult;
import org.springframework.core.io.InputStreamResource;
import org.springframework.core.io.Resource;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;

/**
 * 共用附件接口 S1-F01—F05。
 *
 * <p>这一组路由同时服务设备档案、维保合同、知识条目和维修工单，所以归属对象用
 * {@code ownerType + ownerId} 表达，不为每个模块单独开一套上传接口——
 * 同一份手册被知识页引用时也不该再上传一份。</p>
 *
 * <p>没有删除接口。契约未定义附件删除，且附件一旦被引用，删除会让合同、知识页里的
 * 引用指向不存在的对象；误传的照片应通过 F05 替换内容。</p>
 */
@RestController
@RequestMapping("/api/v1/attachments")
public class AttachmentController {
    private final AttachmentService service;
    private final AttachmentWriteService writeService;

    public AttachmentController(AttachmentService service, AttachmentWriteService writeService) {
        this.service = service;
        this.writeService = writeService;
    }

    /** F01：按归属对象分页列出附件；{@code fileKind} 可选，用于"只挑手册"这类场景。 */
    @GetMapping
    public ApiResponse<PageResult<AttachmentSummary>> attachments(
            @RequestParam(required = false) String ownerType,
            @RequestParam(required = false) String ownerId,
            @RequestParam(required = false) String fileKind,
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer pageSize) {
        return ApiResponse.success(service.search(ownerType, ownerId, fileKind, page, pageSize));
    }

    /** F02：附件元信息与受控查看/下载地址，不含服务器存储路径。 */
    @GetMapping("/{attachmentId}")
    public ApiResponse<AttachmentDetail> attachment(@PathVariable String attachmentId) {
        return ApiResponse.success(service.detail(attachmentId));
    }

    /** F03：上传并关联。业务对象必须已存在；大小与类型按附件模块统一校验。 */
    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ApiResponse<AttachmentDetail> upload(
            @RequestParam(required = false) String requestId,
            @RequestParam(required = false) String operatorName,
            @RequestParam(required = false) String ownerType,
            @RequestParam(required = false) String ownerId,
            @RequestParam(required = false) String fileKind,
            @RequestParam(value = "file", required = false) MultipartFile file) {
        return ApiResponse.success(
                writeService.upload(requestId, operatorName, ownerType, ownerId, fileKind, file));
    }

    /** F04：文件流。{@code download=true} 下载，其余按允许类型内联查看。 */
    @GetMapping("/{attachmentId}/content")
    public ResponseEntity<Resource> content(@PathVariable String attachmentId,
                                            @RequestParam(required = false) Boolean download)
            throws IOException {
        AttachmentService.AttachmentContent content = service.content(attachmentId);
        InputStream stream = service.openStream(content);
        String disposition = Boolean.TRUE.equals(download) ? "attachment" : "inline";

        return ResponseEntity.ok()
                .contentType(mediaTypeOrBinary(content.row().mediaType()))
                .contentLength(content.size())
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        disposition + "; filename*=UTF-8''" + encodeFileName(content.row().originalName()))
                // 版本一变地址内容就变，所以 ETag 带上版本；命中缓存时不需要重新下载整份文件。
                .eTag("\"" + content.row().attachmentId() + "-" + content.row().version() + "\"")
                .cacheControl(CacheControl.maxAge(0, TimeUnit.SECONDS).cachePrivate().mustRevalidate())
                .body(new InputStreamResource(stream));
    }

    /** F05：替换内容，保留附件 ID 与全部引用，版本加一。 */
    @PutMapping(value = "/{attachmentId}/content", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ApiResponse<AttachmentDetail> replaceContent(
            @PathVariable String attachmentId,
            @RequestParam(required = false) String requestId,
            @RequestParam(required = false) String operatorName,
            @RequestParam(required = false) String expectedVersion,
            @RequestParam(required = false) String reason,
            @RequestParam(value = "file", required = false) MultipartFile file) {
        return ApiResponse.success(writeService.replace(attachmentId, requestId, operatorName,
                expectedVersion, reason, file));
    }

    private static MediaType mediaTypeOrBinary(String raw) {
        try {
            return MediaType.parseMediaType(raw);
        } catch (RuntimeException error) {
            return MediaType.APPLICATION_OCTET_STREAM;
        }
    }

    /** RFC 5987 的 filename*：中文文件名必须按 UTF-8 百分号编码，直接写会乱码或被截断。 */
    static String encodeFileName(String name) {
        return URLEncoder.encode(name == null ? "attachment" : name, StandardCharsets.UTF_8)
                .replace("+", "%20");
    }
}
