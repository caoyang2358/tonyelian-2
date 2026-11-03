package cn.copper.phase2.maintenance;

import cn.copper.phase2.common.ApiExceptionHandler;
import cn.copper.phase2.maintenance.InspectionModels.InspectionDetail;
import cn.copper.phase2.maintenance.InspectionModels.InspectionItem;
import cn.copper.phase2.maintenance.InspectionModels.InspectionQuery;
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
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(InspectionController.class)
@Import({ApiExceptionHandler.class, MaintenanceExceptionHandler.class, InspectionService.class})
class InspectionApiTest {

    @Autowired private MockMvc mvc;
    @MockitoBean private InspectionRepository repository;

    private static final OffsetDateTime INSPECTED_AT = OffsetDateTime.parse("2026-11-03T09:15:00+08:00");

    @Test
    void listReturnsPagedEnvelopeWithRepairLinks() throws Exception {
        when(repository.countInspections(any())).thenReturn(1L);
        when(repository.findInspections(any(), anyInt(), anyInt())).thenReturn(List.of(
                new InspectionItem("5", "3", "1#结晶牵引机", INSPECTED_AT, "李四", "ABNORMAL",
                        List.of("12", "15"), 2)));

        mvc.perform(get("/api/v1/inspections"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("OK"))
                .andExpect(jsonPath("$.data.page").value(1))
                .andExpect(jsonPath("$.data.pageSize").value(20))
                .andExpect(jsonPath("$.data.total").value(1))
                .andExpect(jsonPath("$.data.items[0].inspectionId").value("5"))
                .andExpect(jsonPath("$.data.items[0].equipmentName").value("1#结晶牵引机"))
                .andExpect(jsonPath("$.data.items[0].result").value("ABNORMAL"))
                .andExpect(jsonPath("$.data.items[0].repairOrderIds[0]").value("12"))
                .andExpect(jsonPath("$.data.items[0].repairOrderIds[1]").value("15"))
                .andExpect(jsonPath("$.data.items[0].inspectedAt").value(startsWith("2026-11-03T09:15")))
                .andExpect(jsonPath("$.data.items[0].inspectedAt").value(endsWith("+08:00")));
    }

    @Test
    void listWithoutRecordsReturnsEmptyPageInsteadOfError() throws Exception {
        when(repository.countInspections(any())).thenReturn(0L);

        mvc.perform(get("/api/v1/inspections").param("result", "NORMAL"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").value(0))
                .andExpect(jsonPath("$.data.items").isEmpty());
    }

    @Test
    void detailReturnsRecordedContentAndVersion() throws Exception {
        when(repository.findInspection(5L)).thenReturn(new InspectionDetail(
                "5", "3", "1#结晶牵引机", INSPECTED_AT, "李四", "检查结晶轮、牵引速度", "ABNORMAL",
                "牵引速度波动偏大", List.of("12"), INSPECTED_AT.plusMinutes(3), 2));

        mvc.perform(get("/api/v1/inspections/5"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.inspectionId").value("5"))
                .andExpect(jsonPath("$.data.content").value("检查结晶轮、牵引速度"))
                .andExpect(jsonPath("$.data.description").value("牵引速度波动偏大"))
                .andExpect(jsonPath("$.data.recordedAt").value(endsWith("+08:00")))
                .andExpect(jsonPath("$.data.version").value(2));
    }

    @Test
    void missingRecordReturns404WithoutFakeData() throws Exception {
        when(repository.findInspection(999L)).thenReturn(null);

        mvc.perform(get("/api/v1/inspections/999"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"))
                .andExpect(jsonPath("$.message").value("巡检记录不存在或已被删除，请刷新后重试。"))
                .andExpect(jsonPath("$.data").doesNotExist());
    }

    @Test
    void rejectsUnknownResult() throws Exception {
        mvc.perform(get("/api/v1/inspections").param("result", "MAYBE"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.data.fieldErrors[0].field").value("result"));

        verifyNoInteractions(repository);
    }

    @Test
    void rejectsNonNumericInspectionIdBeforeQuerying() throws Exception {
        mvc.perform(get("/api/v1/inspections/abc"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.data.fieldErrors[0].field").value("inspectionId"));

        verifyNoInteractions(repository);
    }

    @Test
    void rejectsNonNumericEquipmentFilter() throws Exception {
        mvc.perform(get("/api/v1/inspections").param("equipmentId", "SMELT-FUR-01"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.data.fieldErrors[0].field").value("equipmentId"));
    }

    @Test
    void rejectsInvertedTimeRange() throws Exception {
        mvc.perform(get("/api/v1/inspections")
                        .param("from", "2026-11-04T00:00:00+08:00")
                        .param("to", "2026-11-03T00:00:00+08:00"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.data.fieldErrors[0].field").value("from"));
    }

    @Test
    void filtersAreNormalisedBeforeReachingRepository() throws Exception {
        when(repository.countInspections(eq(new InspectionQuery("3", "NORMAL", null, null)))).thenReturn(0L);

        mvc.perform(get("/api/v1/inspections")
                        .param("equipmentId", " 3 ")
                        .param("result", "NORMAL"))
                .andExpect(status().isOk());
    }
}
