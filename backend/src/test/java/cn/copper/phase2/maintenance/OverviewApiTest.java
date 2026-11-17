package cn.copper.phase2.maintenance;

import cn.copper.phase2.common.ApiExceptionHandler;
import cn.copper.phase2.common.PageResult;
import cn.copper.phase2.deadline.DeadlineModels.DeadlineBreakdown;
import cn.copper.phase2.deadline.DeadlineModels.DeadlineItem;
import cn.copper.phase2.deadline.DeadlineService;
import cn.copper.phase2.maintenance.OverviewModels.DurationBuckets;
import cn.copper.phase2.maintenance.OverviewModels.EquipmentReportCount;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.nullValue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(MaintenanceOverviewController.class)
@Import({ApiExceptionHandler.class, MaintenanceExceptionHandler.class, OverviewService.class})
class OverviewApiTest {

    @Autowired private MockMvc mvc;
    @MockitoBean private OverviewRepository repository;
    @MockitoBean private DeadlineService deadlineService;

    private static final String FROM = "2026-11-01T00:00:00+08:00";
    private static final String TO = "2026-11-04T00:00:00+08:00";

    /** 用 param 传值，避免把手写的 %2B 交给容器解码。 */
    private static MockHttpServletRequestBuilder ranged() {
        return get("/api/v1/maintenance-overview").param("from", FROM).param("to", TO);
    }

    @Test
    void returnsCountsSamplesAndPerEquipmentBreakdown() throws Exception {
        when(repository.countInspections(any(), any(), isNull(), isNull())).thenReturn(12L);
        when(repository.countAlertEvents(any(), any(), isNull(), isNull())).thenReturn(5L);
        when(repository.countPendingRepairs(any(), any(), isNull(), isNull())).thenReturn(3L);
        when(repository.countCompletedRepairs(any(), any(), isNull(), isNull())).thenReturn(2L);
        when(repository.handlingStats(any(), any(), isNull(), isNull()))
                .thenReturn(new OverviewRepository.HandlingStats(new BigDecimal("754.45"), 2L));
        when(repository.durationBuckets(any(), any(), isNull(), isNull()))
                .thenReturn(new DurationBuckets(1, 0, 1));
        when(repository.reportCountsByEquipment(any(), any(), isNull(), isNull())).thenReturn(List.of(
                new EquipmentReportCount("3", "1#结晶牵引机", 3, 2),
                new EquipmentReportCount("1", "1#熔化炉", 1, 0)));
        when(deadlineService.breakdown(any(), any())).thenReturn(new DeadlineBreakdown(0L, 0L, 0L));

        mvc.perform(ranged())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("OK"))
                .andExpect(jsonPath("$.data.from").value(FROM))
                .andExpect(jsonPath("$.data.to").value(TO))
                .andExpect(jsonPath("$.data.inspectionCount").value(12))
                .andExpect(jsonPath("$.data.alertEventCount").value(5))
                .andExpect(jsonPath("$.data.pendingRepairCount").value(3))
                .andExpect(jsonPath("$.data.completedRepairCount").value(2))
                .andExpect(jsonPath("$.data.averageHandlingSeconds").value("754.5"))
                .andExpect(jsonPath("$.data.handlingSampleCount").value(2))
                .andExpect(jsonPath("$.data.durationBuckets.within30Minutes").value(1))
                .andExpect(jsonPath("$.data.durationBuckets.over2Hours").value(1))
                .andExpect(jsonPath("$.data.caliber.durationBuckets").exists())
                .andExpect(jsonPath("$.data.equipmentReportCounts[0].equipmentName").value("1#结晶牵引机"))
                .andExpect(jsonPath("$.data.equipmentReportCounts[0].reportCount").value(3))
                .andExpect(jsonPath("$.data.equipmentReportCounts[0].completedCount").value(2))
                .andExpect(jsonPath("$.data.generatedAt").exists())
                .andExpect(jsonPath("$.data.caliber.inspectionCount").exists())
                .andExpect(jsonPath("$.data.caliber.averageHandlingSeconds").exists());
    }

    @Test
    void deadlineCountComesFromDueSoonAndExpiredDeadlines() throws Exception {
        when(repository.handlingStats(any(), any(), isNull(), isNull()))
                .thenReturn(new OverviewRepository.HandlingStats(null, 0L));
        when(deadlineService.breakdown(any(), any())).thenReturn(new DeadlineBreakdown(1L, 1L, 0L));

        mvc.perform(ranged())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.deadlineReminderCount").value(2))
                .andExpect(jsonPath("$.data.deadlineBreakdown.dueSoon").value(1))
                .andExpect(jsonPath("$.data.deadlineBreakdown.expired").value(1))
                .andExpect(jsonPath("$.data.deadlineBreakdown.missing").value(0))
                .andExpect(jsonPath("$.data.deadlineReminderNote").value(containsString("设备档案")))
                .andExpect(jsonPath("$.data.deadlineReminderNote").value(containsString("不含维修工单")))
                .andExpect(jsonPath("$.data.averageHandlingSeconds").value(nullValue()))
                .andExpect(jsonPath("$.data.handlingSampleCount").value(0));
    }

    /**
     * 待补资料必须计入"待处理"，否则页面上会出现"0 条需处理"与"待补资料 24"并排的矛盾；
     * 同时条数是"设备 × 期限类型"，说明里要按条表述，不能写成"24 台设备"。
     */
    @Test
    void missingDeadlineCountsAsAttentionAndIsDescribedInRows() throws Exception {
        when(repository.handlingStats(any(), any(), isNull(), isNull()))
                .thenReturn(new OverviewRepository.HandlingStats(null, 0L));
        when(deadlineService.breakdown(any(), any())).thenReturn(new DeadlineBreakdown(0L, 0L, 24L));
        when(deadlineService.search(any(), any(), isNull(), eq("MISSING_DATA"), eq(1), anyInt()))
                .thenReturn(PageResult.of(List.of(
                        deadlineItem("1"), deadlineItem("1"), deadlineItem("1"),
                        deadlineItem("2"), deadlineItem("2"), deadlineItem("2")), 1, 100, 6));

        mvc.perform(ranged())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.deadlineReminderCount").value(24))
                .andExpect(jsonPath("$.data.deadlineMissingEquipmentCount").value(2))
                .andExpect(jsonPath("$.data.deadlineReminderNote").value(containsString("24 条")))
                .andExpect(jsonPath("$.data.deadlineReminderNote").value(containsString("2 台设备")))
                .andExpect(jsonPath("$.data.deadlineReminderNote").value(containsString("算不出到期日")));
    }

    /** 临期与超期要能按来源拆开，否则回答不了"是维保到期还是质保到期"。 */
    @Test
    void deadlineTypeCountsSplitByReminderType() throws Exception {
        when(repository.handlingStats(any(), any(), isNull(), isNull()))
                .thenReturn(new OverviewRepository.HandlingStats(null, 0L));
        when(repository.reportCountsByEquipment(any(), any(), isNull(), isNull())).thenReturn(List.of());
        when(deadlineService.breakdown(any(), any())).thenReturn(new DeadlineBreakdown(2L, 1L, 0L));
        when(deadlineService.search(any(), any(), isNull(), eq("DUE_SOON"), eq(1), anyInt()))
                .thenReturn(PageResult.of(List.of(
                        deadlineItem("1", "WARRANTY"), deadlineItem("2", "MAINTENANCE")), 1, 100, 2));
        when(deadlineService.search(any(), any(), isNull(), eq("EXPIRED"), eq(1), anyInt()))
                .thenReturn(PageResult.of(List.of(deadlineItem("3", "EXPECTED_LIFE")), 1, 100, 1));

        mvc.perform(ranged())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.deadlineTypeCounts.warranty").value(1))
                .andExpect(jsonPath("$.data.deadlineTypeCounts.maintenance").value(1))
                .andExpect(jsonPath("$.data.deadlineTypeCounts.expectedLife").value(1))
                .andExpect(jsonPath("$.data.caliber.deadlineTypeCounts").exists());
    }

    /** 工单时效单独一个数字：在办超 2 小时，不计入期限提醒。 */
    @Test
    void overdueRepairCountIsReportedSeparatelyFromDeadlines() throws Exception {
        when(repository.handlingStats(any(), any(), isNull(), isNull()))
                .thenReturn(new OverviewRepository.HandlingStats(null, 0L));
        when(repository.countOverdueRepairs(any(), isNull(), isNull())).thenReturn(5L);
        when(deadlineService.breakdown(any(), any())).thenReturn(new DeadlineBreakdown(0L, 0L, 0L));

        mvc.perform(ranged())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.overdueRepairCount").value(5))
                .andExpect(jsonPath("$.data.deadlineReminderCount").value(0))
                .andExpect(jsonPath("$.data.caliber.overdueRepairCount").value(containsString("2 小时")));
    }

    /** 概览的筛选条件要与期限统计一致地传下去，否则"按设备查看"会被算成全厂。 */
    @Test
    void deadlineCountReceivesSameEquipmentAndWorkshopFilter() throws Exception {
        when(repository.handlingStats(any(), any(), any(), any()))
                .thenReturn(new OverviewRepository.HandlingStats(null, 0L));
        when(repository.reportCountsByEquipment(any(), any(), any(), any())).thenReturn(List.of());
        when(deadlineService.breakdown(any(), any())).thenReturn(new DeadlineBreakdown(1L, 0L, 0L));

        mvc.perform(get("/api/v1/maintenance-overview")
                        .param("from", FROM).param("to", TO)
                        .param("equipmentId", "3").param("workshopId", "2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.deadlineReminderCount").value(1));

        ArgumentCaptor<Long> equipment = ArgumentCaptor.forClass(Long.class);
        ArgumentCaptor<Long> workshop = ArgumentCaptor.forClass(Long.class);
        verify(deadlineService).breakdown(equipment.capture(), workshop.capture());
        assertThat(equipment.getValue()).isEqualTo(3L);
        assertThat(workshop.getValue()).isEqualTo(2L);
    }

    @Test
    void emptyResultStillReturnsZeroCountsAndEmptyBreakdown() throws Exception {
        when(repository.handlingStats(any(), any(), isNull(), isNull()))
                .thenReturn(new OverviewRepository.HandlingStats(null, 0L));
        when(repository.reportCountsByEquipment(any(), any(), isNull(), isNull())).thenReturn(List.of());
        when(deadlineService.breakdown(any(), any())).thenReturn(new DeadlineBreakdown(0L, 0L, 0L));

        mvc.perform(ranged())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.inspectionCount").value(0))
                .andExpect(jsonPath("$.data.equipmentReportCounts").isEmpty())
                .andExpect(jsonPath("$.data.deadlineBreakdown.dueSoon").value(0))
                .andExpect(jsonPath("$.data.deadlineBreakdown.expired").value(0))
                .andExpect(jsonPath("$.data.deadlineBreakdown.missing").value(0));
    }

    @Test
    void equipmentAndWorkshopScopesArePassedToRepository() throws Exception {
        when(repository.handlingStats(any(), any(), any(), any()))
                .thenReturn(new OverviewRepository.HandlingStats(null, 0L));
        when(deadlineService.breakdown(any(), any())).thenReturn(new DeadlineBreakdown(0L, 0L, 0L));

        mvc.perform(ranged().param("workshopId", "2").param("equipmentId", "3"))
                .andExpect(status().isOk());

        ArgumentCaptor<LocalDateTime> start = ArgumentCaptor.forClass(LocalDateTime.class);
        ArgumentCaptor<LocalDateTime> end = ArgumentCaptor.forClass(LocalDateTime.class);
        verify(repository).countInspections(start.capture(), end.capture(), eq(2L), eq(3L));
        assertThat(start.getValue()).isEqualTo(LocalDateTime.parse("2026-11-01T00:00:00"));
        assertThat(end.getValue()).isEqualTo(LocalDateTime.parse("2026-11-04T00:00:00"));
    }

    @Test
    void missingTimeRangeIsRejected() throws Exception {
        mvc.perform(get("/api/v1/maintenance-overview").param("from", FROM))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.data.fieldErrors[0].field").value("to"));
        verifyNoInteractions(repository);
    }

    @Test
    void reversedTimeRangeIsRejected() throws Exception {
        mvc.perform(get("/api/v1/maintenance-overview").param("from", TO).param("to", FROM))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.data.fieldErrors[0].field").value("from"));
        verifyNoInteractions(repository);
    }

    @Test
    void malformedMomentIsRejected() throws Exception {
        mvc.perform(get("/api/v1/maintenance-overview").param("from", "2026-11-01").param("to", TO))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.data.fieldErrors[0].field").value("from"));
    }

    @Test
    void nonNumericWorkshopIdIsRejected() throws Exception {
        mvc.perform(ranged().param("workshopId", "ws-1"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.data.fieldErrors[0].field").value("workshopId"));
    }

    private static DeadlineItem deadlineItem(String equipmentId) {
        return deadlineItem(equipmentId, "WARRANTY");
    }

    /** 只关心 equipmentId 与来源类型，其余字段留空。 */
    private static DeadlineItem deadlineItem(String equipmentId, String reminderType) {
        return new DeadlineItem(equipmentId, null, null, null, null, null,
                reminderType, null, null, null, null, null, null, null, "MISSING_DATA", List.of());
    }
}
