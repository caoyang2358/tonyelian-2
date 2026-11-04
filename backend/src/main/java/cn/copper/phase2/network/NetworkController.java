package cn.copper.phase2.network;

import cn.copper.phase2.common.ApiResponse;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

import static cn.copper.phase2.network.NetworkService.*;

@RestController
@RequestMapping("/api/v1/network-layouts")
public class NetworkController {
    private final NetworkService service;

    private final ObjectMapper json;

    public NetworkController(NetworkService service, ObjectMapper json) {
        this.service = service;
        this.json = json;
    }

    @GetMapping("/{workshopId}")
    public ApiResponse<Layout> read(@PathVariable String workshopId,
                                    @RequestParam(defaultValue = "false") boolean includeLatest) {
        return ApiResponse.success(service.read(workshopId, includeLatest));
    }

    @PutMapping("/{workshopId}")
    public ApiResponse<Layout> save(@PathVariable String workshopId, @RequestBody JsonNode body) {
        if (!body.isObject() || !textFields(body, "requestId", "operatorName")
                || (body.hasNonNull("reason") && !body.path("reason").isTextual())
                || !body.path("expectedVersion").isIntegralNumber()
                || !body.path("expectedVersion").canConvertToLong()
                || !body.path("nodes").isArray() || !body.path("edges").isArray()) {
            throw invalid("请求字段格式无效。", null);
        }
        for (JsonNode node : body.path("nodes")) {
            if (!textFields(node, "nodeId", "kind", "objectId", "xPercent", "yPercent")) {
                throw invalid("节点 ID 和坐标必须使用字符串。", node);
            }
        }
        for (JsonNode edge : body.path("edges")) {
            if (!textFields(edge, "edgeId", "fromNodeId", "toNodeId")
                    || (edge.hasNonNull("label") && !edge.path("label").isTextual())) {
                throw invalid("连线 ID、端点和标签格式无效。", edge);
            }
        }
        try {
            return ApiResponse.success(service.save(workshopId, json.treeToValue(body, Command.class)));
        } catch (JsonProcessingException error) {
            throw invalid("请求格式无效。", null);
        }
    }

    private boolean textFields(JsonNode object, String... fields) {
        for (String field : fields) {
            if (!object.path(field).isTextual()) return false;
        }
        return true;
    }

    @ExceptionHandler(LayoutError.class)
    ResponseEntity<ApiResponse<Object>> layoutError(LayoutError error) {
        return ResponseEntity.status(error.status).body(ApiResponse.error(error.code, error.getMessage(), error.details));
    }

    @ExceptionHandler({HttpMessageNotReadableException.class, MethodArgumentTypeMismatchException.class})
    ResponseEntity<ApiResponse<Object>> invalidInput() {
        return layoutError(invalid("请求格式无效。", null));
    }
}
