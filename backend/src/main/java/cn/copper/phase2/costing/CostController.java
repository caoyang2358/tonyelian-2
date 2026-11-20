package cn.copper.phase2.costing;

import cn.copper.phase2.common.ApiResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 库存成本、订单盈亏与补算接口（S2-K01—K06）。
 *
 * <p>本组只做查询与受控补算：没有「手改余额」「手改利润」「新增费用」的入口。
 * 实际数量仍由原始事实与库存服务维护，这里只投影这些事实值多少钱。</p>
 */
@RestController
@RequestMapping("/api/v1/costing")
public class CostController {

    private final CostQueryService queries;
    private final CostRunService runs;

    public CostController(CostQueryService queries, CostRunService runs) {
        this.queries = queries;
        this.runs = runs;
    }

    // S2-K01 物料成本视图
    @GetMapping("/stocks")
    public ApiResponse<Map<String, Object>> stocks(
            @RequestParam(required = false) Long materialId,
            @RequestParam(required = false) String materialType,
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer pageSize) {
        return ApiResponse.success(queries.stocks(materialId, materialType, page, pageSize));
    }

    // S2-K02 成本流水
    @GetMapping("/stock-ledger")
    public ApiResponse<Map<String, Object>> stockLedger(
            @RequestParam(required = false) Long costRunId,
            @RequestParam(required = false) Long materialId,
            @RequestParam(required = false) Long orderId,
            @RequestParam(required = false) String entryType,
            @RequestParam(required = false) String from,
            @RequestParam(required = false) String to,
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer pageSize) {
        return ApiResponse.success(queries.stockLedger(costRunId, materialId, orderId, entryType,
                from, to, page, pageSize));
    }

    // S2-K03 订单核算列表
    @GetMapping("/orders")
    public ApiResponse<Map<String, Object>> orders(
            @RequestParam(required = false) Long orderId,
            @RequestParam(required = false) String accountingState,
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer pageSize) {
        return ApiResponse.success(queries.orders(orderId, accountingState, page, pageSize));
    }

    // S2-K04 单订单核算依据
    @GetMapping("/orders/{orderId}")
    public ApiResponse<Map<String, Object>> orderDetail(@PathVariable long orderId) {
        return ApiResponse.success(queries.orderDetail(orderId));
    }

    // S2-K05 受控补算
    @PostMapping("/recalculations")
    public ApiResponse<Map<String, Object>> recalculate(@RequestBody Map<String, Object> body) {
        return ApiResponse.success(runs.recalculate(body));
    }

    // S2-K06 运行查询
    @GetMapping("/runs/{costRunId}")
    public ApiResponse<Map<String, Object>> run(@PathVariable long costRunId) {
        return ApiResponse.success(runs.run(costRunId));
    }
}
