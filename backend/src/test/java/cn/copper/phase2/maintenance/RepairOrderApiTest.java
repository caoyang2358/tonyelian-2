package cn.copper.phase2.maintenance;

import cn.copper.phase2.common.ApiExceptionHandler;
import cn.copper.phase2.maintenance.RepairOrderModels.RepairOrderItem;
import cn.copper.phase2.maintenance.RepairOrderModels.RepairOrderQuery;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.OffsetDateTime;
import java.util.List;

import static org.hamcrest.Matchers.endsWith;
import static org.hamcrest.Matchers.startsWith;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 接口层测试：真实 Controller + 真实 Service 校验逻辑，只把 SQL 访问层替换成假实现，
 * 这样既能验证契约格式，也能验证参数校验，且完全不连数据库。
 */
@WebMvcTest(RepairOrderController.class)
@Import({ApiExceptionHandler.class, MaintenanceExceptionHandler.class, RepairOrderService.class})
class RepairOrderApiTest {

    @Autowired private MockMvc mvc;
    @MockitoBean private RepairOrderRepository repository;
    /** 本类只覆盖读接口，写路径的幂等组件按空实现注入即可。 */
    @MockitoBean private IdempotencyService idempotencyService;

    @Test
    void listReturnsPagedEnvelopeWithStringIdsAndOffsetTimestamps() throws Exception {
        OffsetDateTime created = OffsetDateTime.parse("2026-11-03T08:30:00+08:00");
        when(repository.countRepairOrders(any())).thenReturn(1L);
        when(repository.findRepairOrders(any(), anyInt(), anyInt())).thenReturn(List.of(
                new RepairOrderItem("12", "RO-20261103-001", "3", "1#结晶牵引机", "INSPECTION", "7",
                        "REVIEW_PENDING", created, created.plusHours(1), null, 3)));

        mvc.perform(get("/api/v1/repair-orders"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("OK"))
                .andExpect(jsonPath("$.data.page").value(1))
                .andExpect(jsonPath("$.data.pageSize").value(20))
                .andExpect(jsonPath("$.data.total").value(1))
                .andExpect(jsonPath("$.data.items[0].repairOrderId").value("12"))
                .andExpect(jsonPath("$.data.items[0].equipmentId").value("3"))
                .andExpect(jsonPath("$.data.items[0].sourceId").value("7"))
                .andExpect(jsonPath("$.data.items[0].status").value("REVIEW_PENDING"))
                .andExpect(jsonPath("$.data.items[0].createdAt").value(startsWith("2026-11-03T08:30")))
                .andExpect(jsonPath("$.data.items[0].createdAt").value(endsWith("+08:00")))
                .andExpect(jsonPath("$.data.items[0].completedAt").doesNotExist());
    }

    @Test
    void emptyResultStillReturnsPageEnvelope() throws Exception {
        when(repository.countRepairOrders(any())).thenReturn(0L);

        mvc.perform(get("/api/v1/repair-orders").param("status", "DRAFT"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("OK"))
                .andExpect(jsonPath("$.data.total").value(0))
                .andExpect(jsonPath("$.data.items").isEmpty());
    }

    @Test
    void pageSizeIsCappedAtOneHundred() throws Exception {
        when(repository.countRepairOrders(any())).thenReturn(0L);

        mvc.perform(get("/api/v1/repair-orders").param("page", "2").param("pageSize", "100"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.page").value(2))
                .andExpect(jsonPath("$.data.pageSize").value(100));
    }

    @Test
    void rejectsUnknownStatusWithValidationEnvelope() throws Exception {
        mvc.perform(get("/api/v1/repair-orders").param("status", "NOT_A_STATUS"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.data.fieldErrors[0].field").value("status"));

        verifyNoInteractions(repository);
    }

    @Test
    void rejectsPageSizeAboveLimit() throws Exception {
        mvc.perform(get("/api/v1/repair-orders").param("pageSize", "101"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.data.fieldErrors[0].field").value("pageSize"));

        verifyNoInteractions(repository);
    }

    @Test
    void rejectsNonNumericPageParameter() throws Exception {
        mvc.perform(get("/api/v1/repair-orders").param("page", "abc"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.data.fieldErrors[0].field").value("page"));
    }

    @Test
    void rejectsNonNumericEquipmentId() throws Exception {
        mvc.perform(get("/api/v1/repair-orders").param("equipmentId", "SMELT-FUR-01"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.data.fieldErrors[0].field").value("equipmentId"));
    }

    @Test
    void rejectsMalformedTimestamp() throws Exception {
        mvc.perform(get("/api/v1/repair-orders").param("from", "2026-11-03"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.data.fieldErrors[0].field").value("from"));
    }

    @Test
    void rejectsInvertedTimeRange() throws Exception {
        mvc.perform(get("/api/v1/repair-orders")
                        .param("from", "2026-11-04T00:00:00+08:00")
                        .param("to", "2026-11-03T00:00:00+08:00"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.data.fieldErrors[0].field").value("from"));
    }

    @Test
    void filtersArePassedToRepositoryAsTrimmedValues() throws Exception {
        when(repository.countRepairOrders(eq(new RepairOrderQuery("3", "DRAFT", "MANUAL", null, null))))
                .thenReturn(0L);

        mvc.perform(get("/api/v1/repair-orders")
                        .param("equipmentId", " 3 ")
                        .param("status", "DRAFT")
                        .param("sourceType", "MANUAL"))
                .andExpect(status().isOk());
    }

    @Test
    void detailRouteIsReachableAndReportsMissingOrder() throws Exception {
        when(repository.findOrder(999L)).thenReturn(null);

        mvc.perform(get("/api/v1/repair-orders/999"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));
    }
}
