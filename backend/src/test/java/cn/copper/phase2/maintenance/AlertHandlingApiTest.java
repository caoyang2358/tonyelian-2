package cn.copper.phase2.maintenance;

import cn.copper.phase2.common.ApiExceptionHandler;
import cn.copper.phase2.maintenance.AlertEventModels.HandlingCommand;
import cn.copper.phase2.maintenance.AlertEventModels.HandlingResult;
import cn.copper.phase2.maintenance.IdempotencyRepository.StoredRequest;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.OffsetDateTime;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * S1-A03 人工处理：只追加处理记录并推进事件版本，不改异常事实。
 * 写法与巡检写接口一致——真实 Service + 真实幂等组件，仓储层打桩。
 */
@WebMvcTest(AlertEventController.class)
@Import({ApiExceptionHandler.class, MaintenanceExceptionHandler.class,
        AlertEventService.class, IdempotencyService.class})
class AlertHandlingApiTest {

    @Autowired private MockMvc mvc;
    @Autowired private ObjectMapper objectMapper;
    @MockitoBean private AlertEventRepository repository;
    @MockitoBean private IdempotencyRepository idempotencyRepository;

    private static final String REQUEST_ID = "3f2a1c88-1d4e-4a52-9c31-8f6b7d5e2a10";
    private static final OffsetDateTime UPDATED_AT = OffsetDateTime.parse("2026-11-03T21:00:00+08:00");
    private static final String BODY = """
            {"requestId":"%s","operatorName":"李四","expectedVersion":2,
             "action":"ACKNOWLEDGED","reason":"已通知当班班组检查"}
            """.formatted(REQUEST_ID);

    @Test
    void acknowledgeAppendsRecordAndBumpsEventVersion() throws Exception {
        when(idempotencyRepository.insertIfAbsent(anyString(), anyString(), anyString(), any()))
                .thenReturn(Optional.empty());
        when(repository.lockEventVersion(9L)).thenReturn(2);
        when(repository.insertHandlingRecord(9L, "ACKNOWLEDGED", "已通知当班班组检查", "李四")).thenReturn(5L);
        when(repository.touchEvent(9L)).thenReturn(new AlertEventRepository.EventTouch(3, UPDATED_AT));

        mvc.perform(post("/api/v1/alert-events/9/handling-records")
                        .contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("OK"))
                .andExpect(jsonPath("$.data.handlingRecordId").value("5"))
                .andExpect(jsonPath("$.data.handlingState").value("ACKNOWLEDGED"))
                .andExpect(jsonPath("$.data.version").value(3))
                .andExpect(jsonPath("$.data.updatedAt").value("2026-11-03T21:00:00+08:00"));

        verify(idempotencyRepository).complete(eq(REQUEST_ID), anyString());
    }

    @Test
    void deferRepairIsAcceptedAsSecondAction() throws Exception {
        String body = """
                {"requestId":"%s","operatorName":"王五","expectedVersion":3,
                 "action":"DEFER_REPAIR","reason":"等计划停机一并处理"}
                """.formatted(REQUEST_ID);
        when(idempotencyRepository.insertIfAbsent(anyString(), anyString(), anyString(), any()))
                .thenReturn(Optional.empty());
        when(repository.lockEventVersion(9L)).thenReturn(3);
        when(repository.insertHandlingRecord(9L, "DEFER_REPAIR", "等计划停机一并处理", "王五")).thenReturn(6L);
        when(repository.touchEvent(9L)).thenReturn(new AlertEventRepository.EventTouch(4, UPDATED_AT));

        mvc.perform(post("/api/v1/alert-events/9/handling-records")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.handlingState").value("DEFER_REPAIR"))
                .andExpect(jsonPath("$.data.version").value(4));
    }

    @Test
    void repeatedRequestReplaysStoredResultWithoutSecondInsert() throws Exception {
        HandlingCommand request = objectMapper.readValue(BODY, HandlingCommand.class);
        byte[] hash = MessageDigest.getInstance("SHA-256")
                .digest(objectMapper.writeValueAsString(request).getBytes(StandardCharsets.UTF_8));
        String storedResponse = objectMapper.writeValueAsString(
                new HandlingResult("5", "ACKNOWLEDGED", 3, UPDATED_AT));

        when(idempotencyRepository.insertIfAbsent(eq(REQUEST_ID), eq("ALERT_HANDLING_CREATE"), eq("9"), any()))
                .thenReturn(Optional.of(new StoredRequest(
                        "ALERT_HANDLING_CREATE", "9", hash, storedResponse)));

        mvc.perform(post("/api/v1/alert-events/9/handling-records")
                        .contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.handlingRecordId").value("5"))
                .andExpect(jsonPath("$.data.version").value(3));

        verify(repository, never()).insertHandlingRecord(anyLong(), anyString(), anyString(), anyString());
        verify(repository, never()).touchEvent(anyLong());
    }

    @Test
    void staleVersionReturnsConflictWithCurrentVersion() throws Exception {
        when(idempotencyRepository.insertIfAbsent(anyString(), anyString(), anyString(), any()))
                .thenReturn(Optional.empty());
        when(repository.lockEventVersion(9L)).thenReturn(4);

        mvc.perform(post("/api/v1/alert-events/9/handling-records")
                        .contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("VERSION_CONFLICT"))
                .andExpect(jsonPath("$.data.currentVersion").value(4));

        verify(repository, never()).insertHandlingRecord(anyLong(), anyString(), anyString(), anyString());
    }

    @Test
    void missingEventReturnsNotFound() throws Exception {
        when(idempotencyRepository.insertIfAbsent(anyString(), anyString(), anyString(), any()))
                .thenReturn(Optional.empty());
        when(repository.lockEventVersion(9L)).thenReturn(null);

        mvc.perform(post("/api/v1/alert-events/9/handling-records")
                        .contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));
    }

    @Test
    void actionOutsideTheTwoAllowedValuesIsRejected() throws Exception {
        String body = """
                {"requestId":"%s","operatorName":"李四","expectedVersion":2,
                 "action":"IGNORE","reason":"随便"}
                """.formatted(REQUEST_ID);

        mvc.perform(post("/api/v1/alert-events/9/handling-records")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.data.fieldErrors[0].field").value("action"));
        verifyNoInteractions(repository, idempotencyRepository);
    }

    @Test
    void missingReasonIsRejected() throws Exception {
        String body = """
                {"requestId":"%s","operatorName":"李四","expectedVersion":2,"action":"ACKNOWLEDGED"}
                """.formatted(REQUEST_ID);

        mvc.perform(post("/api/v1/alert-events/9/handling-records")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.data.fieldErrors[0].field").value("reason"));
        verifyNoInteractions(repository);
    }

    @Test
    void missingExpectedVersionIsRejected() throws Exception {
        String body = """
                {"requestId":"%s","operatorName":"李四","action":"ACKNOWLEDGED","reason":"已通知"}
                """.formatted(REQUEST_ID);

        mvc.perform(post("/api/v1/alert-events/9/handling-records")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.data.fieldErrors[0].field").value("expectedVersion"));
    }

    @Test
    void malformedRequestIdIsRejected() throws Exception {
        String body = """
                {"requestId":"abc","operatorName":"李四","expectedVersion":2,
                 "action":"ACKNOWLEDGED","reason":"已通知"}
                """;

        mvc.perform(post("/api/v1/alert-events/9/handling-records")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.data.fieldErrors[0].field").value("requestId"));
    }

    @Test
    void nonNumericEventIdIsRejected() throws Exception {
        mvc.perform(post("/api/v1/alert-events/abc/handling-records")
                        .contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.data.fieldErrors[0].field").value("eventId"));
        verifyNoInteractions(repository);
    }
}
