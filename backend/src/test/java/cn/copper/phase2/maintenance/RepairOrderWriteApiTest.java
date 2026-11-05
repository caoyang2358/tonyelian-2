package cn.copper.phase2.maintenance;

import cn.copper.phase2.common.ApiExceptionHandler;
import cn.copper.phase2.maintenance.RepairOrderRepository.InspectionSource;
import cn.copper.phase2.maintenance.RepairOrderRepository.OrderRow;
import cn.copper.phase2.maintenance.RepairOrderRepository.StatusRow;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.util.List;

import static org.hamcrest.Matchers.matchesPattern;
import static org.hamcrest.Matchers.startsWith;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 工单写接口的编排验证：状态机、版本校验、幂等与留痕。
 * 真实 SQL 与版本递增由端到端联调覆盖，这里只保证「该拒绝的拒绝、该写历史的写历史」。
 */
@WebMvcTest(RepairOrderController.class)
@Import({ApiExceptionHandler.class, MaintenanceExceptionHandler.class, RepairOrderService.class})
class RepairOrderWriteApiTest {

    private static final String REQUEST_ID = "6f1b7c2e-3a4d-4e5f-8a9b-0c1d2e3f4a5b";
    private static final OffsetDateTime NOW = OffsetDateTime.parse("2026-11-03T09:00:00+08:00");

    @Autowired private MockMvc mvc;
    @MockitoBean private RepairOrderRepository repository;
    @MockitoBean private IdempotencyService idempotencyService;

    private static OrderRow order(String status, int version, String description, String reporterName) {
        return new OrderRow("7", "RO-20261103-000007", "3", "1#结晶牵引机", "MANUAL", null, null,
                status, description, null, reporterName, NOW.minusDays(1), NOW,
                "REVIEW_PENDING".equals(status) ? NOW.minusHours(1) : null, null, version);
    }

    private static String body(String requestId, int expectedVersion, String extra) {
        return """
                {"requestId":"%s","operatorName":"张三","expectedVersion":%d%s}
                """.formatted(requestId, expectedVersion, extra == null ? "" : "," + extra);
    }

    // ---- S1-R01 建单 ----

    @Test
    void manualCreateReturnsDraftWithEditableFields() throws Exception {
        when(repository.equipmentExists(3L)).thenReturn(true);
        when(repository.insertOrder(anyString(), eq(3L), eq("MANUAL"), eq(null), eq(null), eq(null), any()))
                .thenReturn(7L);
        when(repository.findOrder(7L)).thenReturn(order("DRAFT", 1, null, null));

        mvc.perform(post("/api/v1/repair-orders")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"%s","operatorName":"张三","sourceType":"MANUAL","equipmentId":"3"}
                                """.formatted(REQUEST_ID)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.repairOrderId").value("7"))
                .andExpect(jsonPath("$.data.orderNo").value("RO-20261103-000007"))
                .andExpect(jsonPath("$.data.status").value("DRAFT"))
                .andExpect(jsonPath("$.data.version").value(1))
                .andExpect(jsonPath("$.data.editableFields[0]").value("description"))
                .andExpect(jsonPath("$.data.sourceSnapshot").doesNotExist());

        verify(repository).insertAction(eq(7L), eq("CREATE"), eq(null), eq(null), eq("DRAFT"),
                any(), any(), eq("张三"), eq(null), eq(null));
    }

    @Test
    void inspectionCreateTakesEquipmentFromSourceAndFreezesSnapshot() throws Exception {
        when(repository.findInspectionSource(5L)).thenReturn(new InspectionSource(
                "5", "3", "1#结晶牵引机", NOW, "李四", "检查结晶轮", "ABNORMAL", "速度波动", 2));
        when(repository.insertOrder(anyString(), eq(3L), eq("INSPECTION"), eq(null), eq(5L), anyString(), any()))
                .thenReturn(7L);
        when(repository.findOrder(7L)).thenReturn(
                order("DRAFT", 1, null, null));

        mvc.perform(post("/api/v1/repair-orders")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"%s","operatorName":"张三","sourceType":"INSPECTION","sourceId":"5"}
                                """.formatted(REQUEST_ID)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("DRAFT"))
                .andExpect(jsonPath("$.data.sourceSnapshot.inspectionId").value("5"))
                .andExpect(jsonPath("$.data.sourceSnapshot.result").value("ABNORMAL"));
    }

    @Test
    void missingSourceRecordReturns404() throws Exception {
        when(repository.findInspectionSource(999L)).thenReturn(null);

        mvc.perform(post("/api/v1/repair-orders")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"%s","operatorName":"张三","sourceType":"INSPECTION","sourceId":"999"}
                                """.formatted(REQUEST_ID)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));

        verify(repository, never()).insertOrder(any(), anyLong(), any(), any(), any(), any(), any());
    }

    @Test
    void manualCreateWithoutEquipmentIsRejected() throws Exception {
        mvc.perform(post("/api/v1/repair-orders")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"%s","operatorName":"张三","sourceType":"MANUAL"}
                                """.formatted(REQUEST_ID)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.data.fieldErrors[0].field").value("equipmentId"));

        verifyNoInteractions(repository);
    }

    @Test
    void createRejectsUnknownSourceTypeBeforeTouchingDatabase() throws Exception {
        mvc.perform(post("/api/v1/repair-orders")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"%s","operatorName":"张三","sourceType":"EMAIL","equipmentId":"3"}
                                """.formatted(REQUEST_ID)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.data.fieldErrors[0].field").value("sourceType"));

        verifyNoInteractions(repository);
    }

    // ---- S1-R04 保存资料 ----

    @Test
    void saveInDraftKeepsStatusAndWritesAuditTrail() throws Exception {
        when(repository.lockOrder(7L)).thenReturn(StatusRow.of("DRAFT", 1));
        when(repository.findOrder(7L)).thenReturn(order("DRAFT", 2, "结晶轮异响", "张三"));
        when(repository.insertChangeLog(anyString(), anyString(), anyString(), anyString(), anyString(),
                any(), anyString())).thenReturn(11L);

        mvc.perform(put("/api/v1/repair-orders/7")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(REQUEST_ID, 1,
                                "\"description\":\"结晶轮异响\",\"reporterName\":\"张三\",\"photoAttachmentIds\":[]")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.changeLogId").value("11"))
                .andExpect(jsonPath("$.data.repairOrder.status").value("DRAFT"))
                .andExpect(jsonPath("$.data.repairOrder.description").value("结晶轮异响"));

        verify(repository).saveContent(eq(7L), eq("结晶轮异响"), eq(null), eq("张三"), any());
        verify(repository).replacePhotos(eq(7L), eq(List.of()));
    }

    @Test
    void saveIsRejectedWhenStatusIsNotEditable() throws Exception {
        when(repository.lockOrder(7L)).thenReturn(StatusRow.of("REVIEW_PENDING", 3));

        mvc.perform(put("/api/v1/repair-orders/7")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(REQUEST_ID, 3, "\"description\":\"改一下\"")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("STATE_CONFLICT"))
                .andExpect(jsonPath("$.data.status").value("REVIEW_PENDING"));

        verify(repository, never()).saveContent(anyLong(), any(), any(), any(), any());
    }

    @Test
    void staleVersionIsRejectedBeforeStateIsInspected() throws Exception {
        when(repository.lockOrder(7L)).thenReturn(StatusRow.of("DRAFT", 4));

        mvc.perform(put("/api/v1/repair-orders/7")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(REQUEST_ID, 2, "\"description\":\"改一下\"")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("VERSION_CONFLICT"))
                .andExpect(jsonPath("$.data.currentVersion").value(4))
                .andExpect(jsonPath("$.data.status").value("DRAFT"));
    }

    @Test
    void photoFromAnotherOrderIsRejected() throws Exception {
        when(repository.lockOrder(7L)).thenReturn(StatusRow.of("DRAFT", 1));
        when(repository.attachmentBelongsToOrder(88L, 7L)).thenReturn(false);

        mvc.perform(put("/api/v1/repair-orders/7")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(REQUEST_ID, 1, "\"photoAttachmentIds\":[\"88\"]")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.data.fieldErrors[0].field").value("photoAttachmentIds"));
    }

    // ---- S1-R05 提交 ----

    @Test
    void submitMovesDraftToReviewPendingAndRecordsFirstSubmission() throws Exception {
        when(repository.lockOrder(7L)).thenReturn(StatusRow.of("DRAFT", 2));
        when(repository.findOrder(7L)).thenReturn(order("REVIEW_PENDING", 3, "结晶轮异响", "张三"));

        mvc.perform(post("/api/v1/repair-orders/7/submissions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(REQUEST_ID, 2, "\"reason\":\"资料已补齐\"")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("REVIEW_PENDING"))
                .andExpect(jsonPath("$.data.actionId").value("0"));

        verify(repository).advanceStatus(eq(7L), eq("REVIEW_PENDING"), any(), any(), eq(null));
    }

    @Test
    void submitWithoutDescriptionIsRejectedBeforeStatusChanges() throws Exception {
        when(repository.lockOrder(7L)).thenReturn(StatusRow.of("DRAFT", 1));
        when(repository.findOrder(7L)).thenReturn(order("DRAFT", 1, null, "张三"));

        mvc.perform(post("/api/v1/repair-orders/7/submissions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(REQUEST_ID, 1, null)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.data.fieldErrors[0].field").value("description"));

        verify(repository, never()).advanceStatus(anyLong(), any(), any(), any(), any());
    }

    @Test
    void submitWithoutReporterIsRejected() throws Exception {
        when(repository.lockOrder(7L)).thenReturn(StatusRow.of("DRAFT", 1));
        when(repository.findOrder(7L)).thenReturn(order("DRAFT", 1, "结晶轮异响", null));

        mvc.perform(post("/api/v1/repair-orders/7/submissions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(REQUEST_ID, 1, null)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.data.fieldErrors[0].field").value("reporterName"));
    }

    // ---- S1-R06 审核 ----

    @Test
    void approveMovesReviewPendingToInRepair() throws Exception {
        when(repository.lockOrder(7L)).thenReturn(StatusRow.of("REVIEW_PENDING", 3));
        when(repository.findOrder(7L)).thenReturn(order("IN_REPAIR", 4, "结晶轮异响", "张三"));

        mvc.perform(post("/api/v1/repair-orders/7/reviews")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(REQUEST_ID, 3, "\"result\":\"APPROVE\"")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("IN_REPAIR"));

        verify(repository).insertAction(eq(7L), eq("REVIEW"), eq("APPROVE"),
                eq("REVIEW_PENDING"), eq("IN_REPAIR"), any(), any(), eq("张三"), eq(null), eq(null));
    }

    @Test
    void returnForInfoRequiresReason() throws Exception {
        when(repository.lockOrder(7L)).thenReturn(StatusRow.of("REVIEW_PENDING", 3));
        when(repository.findOrder(7L)).thenReturn(order("REVIEW_PENDING", 3, "描述不清", "张三"));

        mvc.perform(post("/api/v1/repair-orders/7/reviews")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(REQUEST_ID, 3, "\"result\":\"RETURN_FOR_INFO\"")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.data.fieldErrors[0].field").value("reason"));
    }

    @Test
    void duplicateClosesOrderWithoutTouchingCompletionTime() throws Exception {
        when(repository.lockOrder(7L)).thenReturn(StatusRow.of("REVIEW_PENDING", 3));
        when(repository.findOrder(7L)).thenReturn(order("CLOSED", 4, "重复报修", "张三"));

        mvc.perform(post("/api/v1/repair-orders/7/reviews")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(REQUEST_ID, 3, "\"result\":\"DUPLICATE\",\"reason\":\"与工单 RO-20261103-000003 重复\"")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("CLOSED"));

        verify(repository).advanceStatus(eq(7L), eq("CLOSED"), any(), eq(null), eq(null));
    }

    @Test
    void reviewResultMustBeKnown() throws Exception {
        mvc.perform(post("/api/v1/repair-orders/7/reviews")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(REQUEST_ID, 3, "\"result\":\"MAYBE\"")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.data.fieldErrors[0].field").value("result"));

        verifyNoInteractions(repository);
    }

    @Test
    void reviewInWrongStatusReturnsStateConflict() throws Exception {
        when(repository.lockOrder(7L)).thenReturn(StatusRow.of("DRAFT", 1));

        mvc.perform(post("/api/v1/repair-orders/7/reviews")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(REQUEST_ID, 1, "\"result\":\"APPROVE\"")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("STATE_CONFLICT"))
                .andExpect(jsonPath("$.data.status").value("DRAFT"))
                .andExpect(jsonPath("$.data.version").value(1));
    }

    // ---- S1-R07 / R08 维修与验收 ----

    @Test
    void repairResultMovesOrderToAcceptancePending() throws Exception {
        when(repository.lockOrder(7L)).thenReturn(StatusRow.of("IN_REPAIR", 5));
        when(repository.findOrder(7L)).thenReturn(order("ACCEPTANCE_PENDING", 6, "更换结晶轮", "张三"));

        mvc.perform(post("/api/v1/repair-orders/7/repair-results")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(REQUEST_ID, 5,
                                "\"performedAt\":\"2026-11-03T11:00:00+08:00\",\"description\":\"更换结晶轮并试运行\"")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("ACCEPTANCE_PENDING"));

        verify(repository).insertAction(eq(7L), eq("REPAIR_RESULT"), eq(null),
                eq("IN_REPAIR"), eq("ACCEPTANCE_PENDING"), any(), any(), eq("张三"), eq(null),
                eq("更换结晶轮并试运行"));
    }

    @Test
    void repairResultRequiresPerformedAt() throws Exception {
        mvc.perform(post("/api/v1/repair-orders/7/repair-results")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(REQUEST_ID, 5, "\"description\":\"更换结晶轮\"")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.data.fieldErrors[0].field").value("performedAt"));
    }

    @Test
    void acceptancePassCompletesOrderAndStampsCompletionTime() throws Exception {
        when(repository.lockOrder(7L)).thenReturn(StatusRow.of("ACCEPTANCE_PENDING", 6));
        when(repository.findOrder(7L)).thenReturn(order("COMPLETED", 7, "更换结晶轮", "张三"));

        mvc.perform(post("/api/v1/repair-orders/7/acceptances")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(REQUEST_ID, 6,
                                "\"result\":\"PASS\",\"acceptedAt\":\"2026-11-03T15:00:00+08:00\"")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("COMPLETED"));

        verify(repository).advanceStatus(eq(7L), eq("COMPLETED"), any(), eq(null), any());
    }

    @Test
    void acceptanceFailReturnsOrderToRepairWithoutCompletionTime() throws Exception {
        when(repository.lockOrder(7L)).thenReturn(StatusRow.of("ACCEPTANCE_PENDING", 6));
        when(repository.findOrder(7L)).thenReturn(order("IN_REPAIR", 7, "更换结晶轮", "张三"));

        mvc.perform(post("/api/v1/repair-orders/7/acceptances")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(REQUEST_ID, 6,
                                "\"result\":\"FAIL\",\"acceptedAt\":\"2026-11-03T15:00:00+08:00\",\"reason\":\"仍有异响\"")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("IN_REPAIR"));

        verify(repository).advanceStatus(eq(7L), eq("IN_REPAIR"), any(), eq(null), eq(null));
    }

    @Test
    void acceptanceTimeEarlierThanFirstSubmissionIsRejected() throws Exception {
        // 首次提交发生在 11-04，验收却填 11-03：会让概览的处理时长算出负数。
        when(repository.lockOrder(7L)).thenReturn(
                new StatusRow("ACCEPTANCE_PENDING", 6, LocalDateTime.parse("2026-11-04T10:00:00")));

        mvc.perform(post("/api/v1/repair-orders/7/acceptances")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(REQUEST_ID, 6,
                                "\"result\":\"PASS\",\"acceptedAt\":\"2026-11-03T15:00:00+08:00\"")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.data.fieldErrors[0].field").value("acceptedAt"));

        verify(repository, never()).advanceStatus(anyLong(), anyString(), any(), any(), any());
    }

    @Test
    void acceptanceFailWithoutReasonIsRejected() throws Exception {
        mvc.perform(post("/api/v1/repair-orders/7/acceptances")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(REQUEST_ID, 6,
                                "\"result\":\"FAIL\",\"acceptedAt\":\"2026-11-03T15:00:00+08:00\"")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.data.fieldErrors[0].field").value("reason"));
    }

    // ---- 幂等与读取 ----

    @Test
    void replayedSubmitReturnsStoredActionResultWithoutWritingAgain() throws Exception {
        var stored = new RepairOrderModels.RepairActionResult("7", "REVIEW_PENDING", "42", 3, NOW);
        when(idempotencyService.claim(eq(REQUEST_ID), anyString(), eq("7"), any(),
                eq(RepairOrderModels.RepairActionResult.class)))
                .thenReturn(java.util.Optional.of(stored));

        mvc.perform(post("/api/v1/repair-orders/7/submissions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(REQUEST_ID, 2, null)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.actionId").value("42"))
                .andExpect(jsonPath("$.data.status").value("REVIEW_PENDING"));

        verify(repository, never()).advanceStatus(anyLong(), any(), any(), any(), any());
    }

    @Test
    void actionHistoryIsPagedWithAttachmentIds() throws Exception {
        when(repository.findOrder(7L)).thenReturn(order("IN_REPAIR", 4, "结晶轮异响", "张三"));
        when(repository.countActions(7L)).thenReturn(1L);
        when(repository.findActions(7L, 0, 20)).thenReturn(List.of(
                new RepairOrderModels.RepairActionItem("3", "REVIEW", "APPROVE", "REVIEW_PENDING",
                        "IN_REPAIR", NOW, NOW, "张三", null, null, List.of("88"))));

        mvc.perform(get("/api/v1/repair-orders/7/actions"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").value(1))
                .andExpect(jsonPath("$.data.items[0].actionType").value("REVIEW"))
                .andExpect(jsonPath("$.data.items[0].nextStatus").value("IN_REPAIR"))
                .andExpect(jsonPath("$.data.items[0].attachmentIds[0]").value("88"));
    }

    @Test
    void detailOfMissingOrderReturns404() throws Exception {
        when(repository.findOrder(999L)).thenReturn(null);

        mvc.perform(get("/api/v1/repair-orders/999"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));
    }

    @Test
    void detailExposesAvailableActionsForCurrentStatus() throws Exception {
        when(repository.findOrder(7L)).thenReturn(order("REVIEW_PENDING", 3, "结晶轮异响", "张三"));

        mvc.perform(get("/api/v1/repair-orders/7"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("REVIEW_PENDING"))
                .andExpect(jsonPath("$.data.availableActions[0]").value("APPROVE"))
                .andExpect(jsonPath("$.data.firstSubmittedAt").value(startsWith("2026-11-03T08:00")))
                .andExpect(jsonPath("$.data.photoAttachmentIds").isEmpty());
    }

    @Test
    void orderNoFollowsDocumentedFormat() throws Exception {
        when(repository.findOrder(7L)).thenReturn(order("DRAFT", 1, null, null));

        mvc.perform(get("/api/v1/repair-orders/7"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.orderNo").value(matchesPattern("RO-\\d{8}-\\d{6}")));
    }
}
