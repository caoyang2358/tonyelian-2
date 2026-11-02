package cn.copper.phase2.initialization;

import cn.copper.phase2.common.ApiResponse;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import static cn.copper.phase2.initialization.InitializationModels.*;

@RestController
@RequestMapping("/api/v1/health")
public class HealthController {
    private final InitializationService service;

    public HealthController(InitializationService service) {
        this.service = service;
    }

    @GetMapping("/live")
    public ApiResponse<LiveStatus> live() {
        return ApiResponse.success(new LiveStatus("UP", "copper-phase2-backend"));
    }

    @GetMapping("/ready")
    public ResponseEntity<ApiResponse<ReadyStatus>> ready() {
        try {
            if (service.connectionReady()) {
                return ResponseEntity.ok(ApiResponse.success(
                        new ReadyStatus("UP", new DatabaseCheck("UP", "SELECT 1"))));
            }
        } catch (DataAccessException ignored) {
            // Readiness failure is expected while the independent development database is stopped.
        }
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(ApiResponse.error(
                "DATABASE_UNAVAILABLE", "数据库尚未就绪，请检查开发库服务和本地连接配置。",
                new ReadyStatus("DOWN", new DatabaseCheck("DOWN", "SELECT 1"))));
    }
}
