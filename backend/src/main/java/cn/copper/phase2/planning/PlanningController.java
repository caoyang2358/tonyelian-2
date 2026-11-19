package cn.copper.phase2.planning;

import cn.copper.phase2.common.ApiResponse;
import cn.copper.phase2.planning.PlanningModels.*;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/v1")
public class PlanningController {
    private final PlanningService service;
    public PlanningController(PlanningService service){this.service=service;}
    @GetMapping("/orders") public ApiResponse<Page<OrderItem>> orders(@RequestParam(required=false)String keyword,@RequestParam(required=false)String wireMaterialId,@RequestParam(required=false)String dueFrom,@RequestParam(required=false)String dueTo,@RequestParam(required=false)Integer page,@RequestParam(required=false)Integer pageSize){return ApiResponse.success(service.orders(keyword,wireMaterialId,dueFrom,dueTo,page,pageSize));}
    @PostMapping("/orders") public ApiResponse<CreateResult> createOrder(@RequestBody OrderWrite body){return ApiResponse.success(service.createOrder(body));}
    @GetMapping("/orders/{id}") public ApiResponse<OrderItem> order(@PathVariable String id){return ApiResponse.success(service.order(id));}
    @GetMapping("/materials") public ApiResponse<Page<MaterialItem>> materials(@RequestParam(required=false)String type,@RequestParam(required=false)Boolean includeDisabled,@RequestParam(required=false)Integer page,@RequestParam(required=false)Integer pageSize){return ApiResponse.success(service.materials(type,includeDisabled,page,pageSize));}
    @PostMapping("/materials") public ApiResponse<CreateResult> createMaterial(@RequestBody MaterialWrite body){return ApiResponse.success(service.createMaterial(body));}
    @GetMapping("/inventory/balances") public ApiResponse<Page<InventoryItem>> inventory(@RequestParam(required=false)Integer page,@RequestParam(required=false)Integer pageSize){return ApiResponse.success(service.inventory(page,pageSize));}
    @GetMapping("/planning/capacities") public ApiResponse<List<CapacityItem>> capacities(){return ApiResponse.success(service.capacities());}
    @GetMapping("/planning/capacity-calendar") public ApiResponse<CapacityCalendar> calendar(@RequestParam String from,@RequestParam String to){return ApiResponse.success(service.capacityCalendar(from,to));}
    @GetMapping("/planning/calendar") public ApiResponse<Page<PlanTaskItem>> plans(@RequestParam String from,@RequestParam String to,@RequestParam(required=false)Integer page,@RequestParam(required=false)Integer pageSize){return ApiResponse.success(service.planTasks(from,to,page,pageSize));}
    @PostMapping("/planning/plan-confirmations") public ApiResponse<PlanConfirmationResult> confirm(@RequestBody PlanConfirmationWrite body){return ApiResponse.success(service.confirmPlan(body));}
}
