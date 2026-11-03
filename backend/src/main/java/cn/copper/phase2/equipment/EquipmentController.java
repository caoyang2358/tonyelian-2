package cn.copper.phase2.equipment;

import cn.copper.phase2.common.ApiResponse;
import cn.copper.phase2.equipment.EquipmentModels.EquipmentList;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/equipment")
public class EquipmentController {
    private final EquipmentService service;

    public EquipmentController(EquipmentService service) {
        this.service = service;
    }

    @GetMapping
    public ApiResponse<EquipmentList> equipment() {
        return ApiResponse.success(service.equipment());
    }
}
