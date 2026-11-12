package cn.copper.phase2.maintenance;

import cn.copper.phase2.common.ApiExceptionHandler;
import cn.copper.phase2.maintenance.AlertEventModels.AlertEventDetail;
import cn.copper.phase2.maintenance.AlertEventModels.AlertEventItem;
import cn.copper.phase2.maintenance.AlertEventModels.AlertEventQuery;
import cn.copper.phase2.maintenance.AlertEventModels.HandlingRecordItem;
import cn.copper.phase2.maintenance.AlertEventModels.RepairOrderRef;
import cn.copper.phase2.maintenance.AlertEventModels.RuleSnapshot;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.endsWith;
import static org.hamcrest.Matchers.startsWith;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(AlertEventController.class)
@Import({ApiExceptionHandler.class, MaintenanceExceptionHandler.class, AlertEventService.class})
class AlertEventApiTest {

    @Autowired private MockMvc mvc;
    @MockitoBean private AlertEventRepository repository;
    /** 本类只覆盖读接口，写路径的幂等组件按空实现注入即可。 */
    @MockitoBean private IdempotencyService idempotencyService;

    private static final OffsetDateTime TRIGGERED_AT = OffsetDateTime.parse("2026-11-03T08:30:00+08:00");
    private static final OffsetDateTime RECOVERED_AT = OffsetDateTime.parse("2026-11-03T09:10:00+08:00");

    @Test
    void emptyTableReturnsEmptyPageInsteadOfError() throws Exception {
        when(repository.countEvents(any())).thenReturn(0L);

        mvc.perform(get("/api/v1/alert-events"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("OK"))
                .andExpect(jsonPath("$.data.page").value(1))
                .andExpect(jsonPath("$.data.pageSize").value(20))
                .andExpect(jsonPath("$.data.total").value(0))
                .andExpect(jsonPath("$.data.items").isEmpty());
    }

    @Test
    void listReturnsStringIdsLocalOffsetAndDerivedStates() throws Exception {
        when(repository.countEvents(any())).thenReturn(1L);
        when(repository.findEvents(any(), anyInt(), anyInt())).thenReturn(List.of(
                new AlertEventItem("9", "3", "1#结晶牵引机", "11", "TC-001", "21", "TEMP", "熔化温度",
                        TRIGGERED_AT, TRIGGERED_AT, RECOVERED_AT, RECOVERED_AT, "RECOVERED",
                        AlertEventStates.RECOVERED, AlertEventStates.PENDING,
                        AlertEventStates.PRIORITY_NORMAL, "82.5", "℃", AlertEventStates.SOURCE_REALTIME,
                        List.of("14"), List.of("IN_REPAIR"), 1)));

        mvc.perform(get("/api/v1/alert-events"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items[0].eventId").value("9"))
                .andExpect(jsonPath("$.data.items[0].equipmentId").value("3"))
                .andExpect(jsonPath("$.data.items[0].sensorCode").value("TC-001"))
                .andExpect(jsonPath("$.data.items[0].parameterCode").value("TEMP"))
                .andExpect(jsonPath("$.data.items[0].parameterName").value("熔化温度"))
                .andExpect(jsonPath("$.data.items[0].eventState").value("RECOVERED"))
                .andExpect(jsonPath("$.data.items[0].handlingState").value("PENDING"))
                .andExpect(jsonPath("$.data.items[0].priority").value("NORMAL"))
                .andExpect(jsonPath("$.data.items[0].triggerValue").value("82.5"))
                .andExpect(jsonPath("$.data.items[0].unitCode").value("℃"))
                .andExpect(jsonPath("$.data.items[0].repairOrderIds[0]").value("14"))
                .andExpect(jsonPath("$.data.items[0].triggeredAt").value(startsWith("2026-11-03T08:30")))
                .andExpect(jsonPath("$.data.items[0].triggeredAt").value(endsWith("+08:00")));
    }

    @Test
    void filtersArePassedToRepository() throws Exception {
        when(repository.countEvents(any())).thenReturn(0L);

        mvc.perform(get("/api/v1/alert-events")
                        .param("equipmentId", "3")
                        .param("sensorId", "11")
                        .param("parameterId", "21")
                        .param("eventState", "ongoing")
                        .param("handlingState", "pending")
                        .param("from", "2026-11-01T00:00:00+08:00")
                        .param("to", "2026-11-04T00:00:00+08:00"))
                .andExpect(status().isOk());

        ArgumentCaptor<AlertEventQuery> captor = ArgumentCaptor.forClass(AlertEventQuery.class);
        verify(repository).countEvents(captor.capture());
        AlertEventQuery query = captor.getValue();
        assertThat(query.equipmentId()).isEqualTo("3");
        assertThat(query.sensorId()).isEqualTo("11");
        assertThat(query.parameterId()).isEqualTo("21");
        assertThat(query.eventState()).isEqualTo("ONGOING");
        assertThat(query.handlingState()).isEqualTo("PENDING");
        assertThat(query.from()).isEqualTo("2026-11-01T00:00:00+08:00");
        assertThat(query.to()).isEqualTo("2026-11-04T00:00:00+08:00");
    }

    @Test
    void sortDefaultsToTimeAndIsPassedToRepository() throws Exception {
        when(repository.countEvents(any())).thenReturn(0L);

        mvc.perform(get("/api/v1/alert-events")).andExpect(status().isOk());

        ArgumentCaptor<AlertEventQuery> captor = ArgumentCaptor.forClass(AlertEventQuery.class);
        verify(repository).countEvents(captor.capture());
        assertThat(captor.getValue().sort()).isEqualTo(AlertEventStates.SORT_TIME);
    }

    @Test
    void sortPriorityIsPassedToRepository() throws Exception {
        when(repository.countEvents(any())).thenReturn(0L);

        mvc.perform(get("/api/v1/alert-events").param("sort", "priority"))
                .andExpect(status().isOk());

        ArgumentCaptor<AlertEventQuery> captor = ArgumentCaptor.forClass(AlertEventQuery.class);
        verify(repository).countEvents(captor.capture());
        assertThat(captor.getValue().sort()).isEqualTo(AlertEventStates.SORT_PRIORITY);
    }

    @Test
    void invalidSortIsRejectedBeforeQuerying() throws Exception {
        mvc.perform(get("/api/v1/alert-events").param("sort", "priority_desc"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.data.fieldErrors[0].field").value("sort"));
        verifyNoInteractions(repository);
    }

    @Test
    void invalidEventStateIsRejectedBeforeQuerying() throws Exception {
        mvc.perform(get("/api/v1/alert-events").param("eventState", "CLOSED"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.data.fieldErrors[0].field").value("eventState"));
        verifyNoInteractions(repository);
    }

    @Test
    void invalidHandlingStateIsRejected() throws Exception {
        mvc.perform(get("/api/v1/alert-events").param("handlingState", "IGNORED"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.data.fieldErrors[0].field").value("handlingState"));
        verifyNoInteractions(repository);
    }

    @Test
    void reversedTimeRangeIsRejected() throws Exception {
        mvc.perform(get("/api/v1/alert-events")
                        .param("from", "2026-11-04T00:00:00+08:00")
                        .param("to", "2026-11-01T00:00:00+08:00"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.data.fieldErrors[0].field").value("from"));
    }

    @Test
    void malformedMomentIsRejected() throws Exception {
        mvc.perform(get("/api/v1/alert-events").param("from", "2026-11-01"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.data.fieldErrors[0].field").value("from"));
    }

    @Test
    void nonNumericEquipmentIdIsRejected() throws Exception {
        mvc.perform(get("/api/v1/alert-events").param("equipmentId", "eq-1"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.data.fieldErrors[0].field").value("equipmentId"));
    }

    @Test
    void pageSizeAboveLimitIsRejected() throws Exception {
        mvc.perform(get("/api/v1/alert-events").param("pageSize", "101"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.data.fieldErrors[0].field").value("pageSize"));
    }

    @Test
    void detailCarriesTriggerEvidenceRuleSnapshotAndRepairState() throws Exception {
        AlertEventDetail detail = detailWith(
                new RuleSnapshot("31", "4", "60", "70", "55", "75", "℃", "SUSTAINED", 60,
                        OffsetDateTime.parse("2026-10-01T00:00:00+08:00")),
                List.of(new RepairOrderRef("14", "RO-20261103-000014", "ACCEPTANCE_PENDING")));

        when(repository.findEvent(9L)).thenReturn(detail);

        mvc.perform(get("/api/v1/alert-events/9"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.eventId").value("9"))
                .andExpect(jsonPath("$.data.eventState").value("RECOVERED"))
                .andExpect(jsonPath("$.data.triggerValue").value("78.5"))
                .andExpect(jsonPath("$.data.unitCode").value("℃"))
                .andExpect(jsonPath("$.data.sourceReference.sampleId").value("S-20261103-1"))
                .andExpect(jsonPath("$.data.ruleVersionId").value("31"))
                .andExpect(jsonPath("$.data.ruleSnapshot.triggerMode").value("SUSTAINED"))
                .andExpect(jsonPath("$.data.ruleSnapshot.durationSeconds").value(60))
                .andExpect(jsonPath("$.data.ruleSnapshot.alarmUpper").value("75"))
                .andExpect(jsonPath("$.data.ruleSnapshot.effectiveAt").value(startsWith("2026-10-01T00:00")))
                .andExpect(jsonPath("$.data.repairOrders[0].orderNo").value("RO-20261103-000014"))
                .andExpect(jsonPath("$.data.repairOrders[0].status").value("ACCEPTANCE_PENDING"));
    }

    @Test
    void missingEventReturnsNotFound() throws Exception {
        when(repository.findEvent(anyLong())).thenReturn(null);

        mvc.perform(get("/api/v1/alert-events/404"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));
    }

    @Test
    void nonNumericEventIdIsRejected() throws Exception {
        mvc.perform(get("/api/v1/alert-events/abc"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.data.fieldErrors[0].field").value("eventId"));
        verifyNoInteractions(repository);
    }

    @Test
    void handlingHistoryReturnsEveryActionNewestFirst() throws Exception {
        when(repository.eventExists(9L)).thenReturn(true);
        when(repository.countHandlingRecords(9L)).thenReturn(2L);
        when(repository.findHandlingRecords(eq(9L), anyInt(), anyInt())).thenReturn(List.of(
                new HandlingRecordItem("5", "DEFER_REPAIR", "等下次停机一并处理", "王五",
                        OffsetDateTime.parse("2026-11-03T10:00:00+08:00")),
                new HandlingRecordItem("4", "ACKNOWLEDGED", "已通知班组", "李四",
                        OffsetDateTime.parse("2026-11-03T09:30:00+08:00"))));

        mvc.perform(get("/api/v1/alert-events/9/handling-records"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").value(2))
                .andExpect(jsonPath("$.data.items[0].handlingRecordId").value("5"))
                .andExpect(jsonPath("$.data.items[0].action").value("DEFER_REPAIR"))
                .andExpect(jsonPath("$.data.items[0].reason").value("等下次停机一并处理"))
                .andExpect(jsonPath("$.data.items[0].operatorName").value("王五"))
                .andExpect(jsonPath("$.data.items[0].recordedAt").value(endsWith("+08:00")));
    }

    @Test
    void handlingHistoryOfUnknownEventReturnsNotFound() throws Exception {
        when(repository.eventExists(404L)).thenReturn(false);

        mvc.perform(get("/api/v1/alert-events/404/handling-records"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));
    }

    @Test
    void handlingHistoryOfEventWithoutRecordsIsEmptyPage() throws Exception {
        when(repository.eventExists(9L)).thenReturn(true);
        when(repository.countHandlingRecords(9L)).thenReturn(0L);

        mvc.perform(get("/api/v1/alert-events/9/handling-records"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").value(0))
                .andExpect(jsonPath("$.data.items").isEmpty());
    }

    @Test
    void manualEventIsCreatedAndMarkedAsManualInSourceReference() throws Exception {
        when(repository.findSensorParameter(8L, 3L)).thenReturn(sensorParameter());
        when(repository.findActiveEventId(3L)).thenReturn(null);
        when(repository.findLatestRuleVersionId(3L)).thenReturn(4L);
        when(repository.insertManualEvent(anyLong(), anyLong(), anyLong(), anyLong(),
                any(), any(), any(), any(), any())).thenReturn(42L);
        when(repository.findEvent(42L)).thenReturn(manualDetail());

        mvc.perform(post("/api/v1/alert-events")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(manualBody("2026-11-12T10:00:00+08:00")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.code").value("OK"))
                .andExpect(jsonPath("$.data.eventId").value("42"))
                .andExpect(jsonPath("$.data.parameterName").value("底部温度"));

        ArgumentCaptor<String> reference = ArgumentCaptor.forClass(String.class);
        verify(repository).insertManualEvent(eq(1L), eq(8L), eq(3L), eq(4L),
                any(), any(), any(), eq("C"), reference.capture());
        // 来源必须固定标成人工补录，并带上补录人与原因，避免和采集判定混在一起
        assertThat(reference.getValue())
                .contains("\"type\":\"MANUAL\"")
                .contains("张三")
                .contains("采集器故障");
        verify(idempotencyService).complete(any(), any(AlertEventDetail.class));
    }

    @Test
    void manualEventIsRejectedWhenParameterAlreadyHasActiveEvent() throws Exception {
        when(repository.findSensorParameter(8L, 3L)).thenReturn(sensorParameter());
        when(repository.findActiveEventId(3L)).thenReturn(5L);

        mvc.perform(post("/api/v1/alert-events")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(manualBody("2026-11-12T10:00:00+08:00")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("EVENT_ALREADY_ACTIVE"))
                .andExpect(jsonPath("$.data.activeEventId").value("5"))
                .andExpect(jsonPath("$.message", startsWith("该参数已有进行中的异常事件")));

        verify(repository, org.mockito.Mockito.never()).insertManualEvent(anyLong(), anyLong(), anyLong(),
                anyLong(), any(), any(), any(), any(), any());
    }

    @Test
    void manualEventRejectsFutureStartedAtBeforeTouchingDatabase() throws Exception {
        mvc.perform(post("/api/v1/alert-events")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(manualBody("2099-01-01T08:00:00+08:00")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.data.fieldErrors[0].field").value("startedAt"));

        verifyNoInteractions(repository);
    }

    private static AlertEventRepository.SensorParameterRef sensorParameter() {
        return new AlertEventRepository.SensorParameterRef(
                8L, 1L, "1#熔化炉", "TEMP-003", 3L, "temperature", "底部温度", "C");
    }

    private static String manualBody(String startedAt) {
        return """
                {"requestId":"3f1a2b4c-5d6e-4f70-8a91-b2c3d4e5f607","sensorId":"8","parameterId":"3",
                 "triggerValue":"97.2","startedAt":"%s",
                 "reason":"采集器故障，现场温度表读数 97.2℃","operatorName":"张三"}
                """.formatted(startedAt);
    }

    private static AlertEventDetail manualDetail() {
        return new AlertEventDetail(
                "42", "1", "1#熔化炉", "8", "TEMP-003", "3", "temperature", "底部温度",
                OffsetDateTime.parse("2026-11-12T10:00:00+08:00"), OffsetDateTime.parse("2026-11-12T10:10:00+08:00"),
                null, null, null,
                AlertEventStates.ONGOING, AlertEventStates.PENDING, AlertEventStates.PRIORITY_URGENT,
                "97.2", "C", Map.of("type", "MANUAL", "operator", "张三", "reason", "采集器故障"),
                "4", null, List.of(), 1);
    }

    private static AlertEventDetail detailWith(RuleSnapshot snapshot, List<RepairOrderRef> orders) {
        return new AlertEventDetail(
                "9", "3", "1#结晶牵引机", "11", "TC-001", "21", "TEMP", "熔化温度",
                TRIGGERED_AT.minusMinutes(20), TRIGGERED_AT, RECOVERED_AT, RECOVERED_AT, "RECOVERED",
                AlertEventStates.RECOVERED, AlertEventStates.DEFER_REPAIR,
                AlertEventStates.PRIORITY_LOW,
                "78.5", "℃", Map.of("sampleId", "S-20261103-1"), "31",
                snapshot, orders, 2);
    }
}
