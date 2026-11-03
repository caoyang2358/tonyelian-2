package cn.copper.phase2.initialization;

import cn.copper.phase2.common.ApiResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import static cn.copper.phase2.initialization.InitializationModels.*;

@RestController
@RequestMapping("/api/v1")
public class BootstrapController {
    private final InitializationService service;

    public BootstrapController(InitializationService service) {
        this.service = service;
    }

    @GetMapping("/bootstrap/overview")
    public ApiResponse<BootstrapOverview> overview() {
        return ApiResponse.success(service.overview());
    }

}
