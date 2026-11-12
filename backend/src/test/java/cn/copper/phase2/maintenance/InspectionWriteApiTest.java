package cn.copper.phase2.maintenance;

import cn.copper.phase2.common.ApiExceptionHandler;
import cn.copper.phase2.maintenance.IdempotencyRepository.StoredRequest;
import cn.copper.phase2.maintenance.InspectionModels.InspectionDetail;
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
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 写接口测试：真实 Controller + Service + IdempotencyService，SQL 层与幂等表替换成假实现。
 * 覆盖：正常新增、幂等重放、内容冲突、设备不存在、版本冲突、对象不存在、必填缺失。
 */
@WebMvcTest(InspectionController.class)
@Import({ApiExceptionHandler.class, MaintenanceExceptionHandler.class,
        InspectionService.class, IdempotencyService.class})
class InspectionWriteApiTest {
    private static final String REQUEST_ID = "6f1c1b7e-2f0a-4c3d-9a8e-5b7d2c1e4f60";
    private static final OffsetDateTime INSPECTED_AT = OffsetDateTime.parse("2026-11-03T09:15:00+08:00");
    private static final String BODY = """
            {
              "requestId": "%s",
              "operatorName": "张顺",
              "equipmentId": "3",
              "inspectedAt": "2026-11-03T09:15:00+08:00",
              "inspectorName": "李四",
              "content": "检查结晶轮与牵引速度",
              "result": "ABNORMAL",
              "description": "牵引速度波动偏大"
            }
            """.formatted(REQUEST_ID);

    @Autowired private MockMvc mvc;
    @Autowired private ObjectMapper objectMapper;
    @MockitoBean private InspectionRepository repository;
    @MockitoBean private IdempotencyRepository idempotencyRepository;

    private InspectionDetail detail(int version) {
        return new InspectionDetail("7", "3", "1#结晶牵引机", INSPECTED_AT, "李四", "张顺",
                "检查结晶轮与牵引速度", "ABNORMAL", "牵引速度波动偏大", List.of(), 0,
                INSPECTED_AT.plusMinutes(3), version);
    }

    @Test
    void createStoresRecordAndReturnsDetail() throws Exception {
        when(idempotencyRepository.insertIfAbsent(anyString(), anyString(), isNull(), any()))
                .thenReturn(Optional.empty());
        when(repository.equipmentExists(3L)).thenReturn(true);
        when(repository.insertInspection(anyLong(), any(), anyString(), anyString(), anyString(), any(), anyString()))
                .thenReturn(7L);
        when(repository.findInspection(7L)).thenReturn(detail(1));

        mvc.perform(post("/api/v1/inspections").contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("OK"))
                .andExpect(jsonPath("$.data.inspectionId").value("7"))
                .andExpect(jsonPath("$.data.result").value("ABNORMAL"))
                .andExpect(jsonPath("$.data.version").value(1));

        verify(idempotencyRepository).complete(eq(REQUEST_ID), anyString());
    }

    @Test
    void repeatedRequestReplaysStoredResponseWithoutSecondInsert() throws Exception {
        String payload = objectMapper.writeValueAsString(objectMapper.readValue(BODY,
                InspectionModels.InspectionCreateCommand.class));
        byte[] hash = MessageDigest.getInstance("SHA-256").digest(payload.getBytes(StandardCharsets.UTF_8));
        String storedResponse = objectMapper.writeValueAsString(detail(1));

        when(idempotencyRepository.insertIfAbsent(eq(REQUEST_ID), eq("INSPECTION_CREATE"), isNull(), any()))
                .thenReturn(Optional.of(new StoredRequest("INSPECTION_CREATE", null, hash, storedResponse)));

        mvc.perform(post("/api/v1/inspections").contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.inspectionId").value("7"))
                .andExpect(jsonPath("$.data.version").value(1));

        verify(repository, never()).insertInspection(anyLong(), any(), anyString(), anyString(), anyString(), any(), anyString());
        verify(idempotencyRepository, never()).complete(anyString(), anyString());
    }

    @Test
    void sameRequestIdWithDifferentContentIsRejected() throws Exception {
        byte[] otherHash = MessageDigest.getInstance("SHA-256").digest("different".getBytes(StandardCharsets.UTF_8));
        when(idempotencyRepository.insertIfAbsent(anyString(), anyString(), isNull(), any()))
                .thenReturn(Optional.of(new StoredRequest("INSPECTION_CREATE", null, otherHash, "{}")));

        mvc.perform(post("/api/v1/inspections").contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("IDEMPOTENCY_CONFLICT"));

        verify(repository, never()).insertInspection(anyLong(), any(), anyString(), anyString(), anyString(), any(), anyString());
    }

    @Test
    void unknownEquipmentIsRejectedBeforeInsert() throws Exception {
        when(idempotencyRepository.insertIfAbsent(anyString(), anyString(), isNull(), any()))
                .thenReturn(Optional.empty());
        when(repository.equipmentExists(3L)).thenReturn(false);

        mvc.perform(post("/api/v1/inspections").contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.data.fieldErrors[0].field").value("equipmentId"));
    }

    @Test
    void missingRequestIdIsRejected() throws Exception {
        mvc.perform(post("/api/v1/inspections").contentType(MediaType.APPLICATION_JSON)
                        .content(BODY.replace(REQUEST_ID, "")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.data.fieldErrors[0].field").value("requestId"));
    }

    @Test
    void nonUuidRequestIdIsRejected() throws Exception {
        mvc.perform(post("/api/v1/inspections").contentType(MediaType.APPLICATION_JSON)
                        .content(BODY.replace(REQUEST_ID, "abc-123")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.data.fieldErrors[0].field").value("requestId"))
                .andExpect(jsonPath("$.message").value("requestId 应为 36 位 UUID。"));
    }

    @Test
    void malformedJsonIsReportedAsValidationError() throws Exception {
        mvc.perform(post("/api/v1/inspections").contentType(MediaType.APPLICATION_JSON).content("{not json"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
    }

    @Test
    void updateSavesCorrectionAndReturnsChangeLogId() throws Exception {
        String body = """
                {
                  "requestId": "%s",
                  "operatorName": "张顺",
                  "expectedVersion": 1,
                  "reason": "巡检人姓名录错",
                  "inspectedAt": "2026-11-03T09:15:00+08:00",
                  "inspectorName": "李四四",
                  "content": "检查结晶轮与牵引速度",
                  "result": "NORMAL",
                  "description": "复核后确认无异常"
                }
                """.formatted(REQUEST_ID);
        when(idempotencyRepository.insertIfAbsent(anyString(), anyString(), anyString(), any()))
                .thenReturn(Optional.empty());
        when(repository.lockVersion(7L)).thenReturn(1);
        when(repository.findInspection(7L)).thenReturn(detail(1), detail(2));
        when(repository.insertChangeLog(anyString(), anyString(), anyString(), anyString(), anyString(), anyString(), anyString()))
                .thenReturn(42L);

        mvc.perform(put("/api/v1/inspections/7").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.changeLogId").value("42"))
                .andExpect(jsonPath("$.data.inspection.version").value(2));

        verify(repository).updateInspection(7L,
                java.time.LocalDateTime.of(2026, 11, 3, 9, 15), "李四四",
                "检查结晶轮与牵引速度", "NORMAL", "复核后确认无异常");
    }

    @Test
    void staleVersionIsRejectedWithCurrentVersion() throws Exception {
        String body = """
                {
                  "requestId": "%s",
                  "operatorName": "张顺",
                  "expectedVersion": 1,
                  "reason": "巡检人姓名录错",
                  "inspectedAt": "2026-11-03T09:15:00+08:00",
                  "inspectorName": "李四四",
                  "content": "检查结晶轮与牵引速度",
                  "result": "NORMAL"
                }
                """.formatted(REQUEST_ID);
        when(idempotencyRepository.insertIfAbsent(anyString(), anyString(), anyString(), any()))
                .thenReturn(Optional.empty());
        when(repository.lockVersion(7L)).thenReturn(2);

        mvc.perform(put("/api/v1/inspections/7").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("VERSION_CONFLICT"))
                .andExpect(jsonPath("$.data.currentVersion").value(2));

        verify(repository, never()).updateInspection(anyLong(), any(), anyString(), anyString(), anyString(), any());
    }

    @Test
    void updatingMissingRecordReturns404() throws Exception {
        String body = """
                {
                  "requestId": "%s",
                  "operatorName": "张顺",
                  "expectedVersion": 1,
                  "reason": "更正",
                  "inspectedAt": "2026-11-03T09:15:00+08:00",
                  "inspectorName": "李四",
                  "content": "内容",
                  "result": "NORMAL"
                }
                """.formatted(REQUEST_ID);
        when(idempotencyRepository.insertIfAbsent(anyString(), anyString(), anyString(), any()))
                .thenReturn(Optional.empty());
        when(repository.lockVersion(999L)).thenReturn(null);

        mvc.perform(put("/api/v1/inspections/999").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));
    }

    @Test
    void updateWithoutReasonIsRejected() throws Exception {
        String body = """
                {
                  "requestId": "%s",
                  "operatorName": "张顺",
                  "expectedVersion": 1,
                  "inspectedAt": "2026-11-03T09:15:00+08:00",
                  "inspectorName": "李四",
                  "content": "内容",
                  "result": "NORMAL"
                }
                """.formatted(REQUEST_ID);

        mvc.perform(put("/api/v1/inspections/7").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.data.fieldErrors[0].field").value("reason"));
    }

    @Test
    void updateWithoutExpectedVersionIsRejected() throws Exception {
        String body = """
                {
                  "requestId": "%s",
                  "operatorName": "张顺",
                  "reason": "更正",
                  "inspectedAt": "2026-11-03T09:15:00+08:00",
                  "inspectorName": "李四",
                  "content": "内容",
                  "result": "NORMAL"
                }
                """.formatted(REQUEST_ID);

        mvc.perform(put("/api/v1/inspections/7").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.data.fieldErrors[0].field").value("expectedVersion"));
    }

    @Test
    void invalidResultOnCreateIsRejected() throws Exception {
        mvc.perform(post("/api/v1/inspections").contentType(MediaType.APPLICATION_JSON)
                        .content(BODY.replace("ABNORMAL", "MAYBE")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.data.fieldErrors[0].field").value("result"));
    }
}
