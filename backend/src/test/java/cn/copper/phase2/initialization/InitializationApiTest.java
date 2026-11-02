package cn.copper.phase2.initialization;

import cn.copper.phase2.common.ApiExceptionHandler;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static cn.copper.phase2.initialization.InitializationModels.*;
import static org.hamcrest.Matchers.*;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest({HealthController.class, BootstrapController.class})
@Import(ApiExceptionHandler.class)
class InitializationApiTest {
    @Autowired private MockMvc mvc;
    @MockitoBean private InitializationService service;

    @Test
    void livenessDoesNotAccessDatabaseOrService() throws Exception {
        mvc.perform(get("/api/v1/health/live"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.code").value("OK"))
                .andExpect(jsonPath("$.message").value("成功"))
                .andExpect(jsonPath("$.data.status").value("UP"))
                .andExpect(jsonPath("$.timestamp").value(endsWith("+08:00")));
        verifyNoInteractions(service);
    }

    @Test
    void readinessReportsActualProbeSuccess() throws Exception {
        when(service.connectionReady()).thenReturn(true);
        mvc.perform(get("/api/v1/health/ready"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.database.status").value("UP"))
                .andExpect(jsonPath("$.data.database.check").value("SELECT 1"));
    }

    @Test
    void readinessReturns503WithoutLeakingConnectionDetails() throws Exception {
        when(service.connectionReady()).thenThrow(new DataAccessResourceFailureException(
                "jdbc:mysql://internal-host/private?password=do-not-expose; SECRET_STACK"));
        mvc.perform(get("/api/v1/health/ready"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("DATABASE_UNAVAILABLE"))
                .andExpect(jsonPath("$.data.status").value("DOWN"))
                .andExpect(jsonPath("$.data.database.status").value("DOWN"))
                .andExpect(content().string(not(containsString("do-not-expose"))))
                .andExpect(content().string(not(containsString("jdbc:mysql"))))
                .andExpect(content().string(not(containsString("SECRET_STACK"))));
    }

    @Test
    void readinessAlsoTreatsUnexpectedProbeResultAsUnavailable() throws Exception {
        when(service.connectionReady()).thenReturn(false);
        mvc.perform(get("/api/v1/health/ready"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.data.database.status").value("DOWN"));
    }

    @Test
    void overviewUsesTheInitializationContract() throws Exception {
        when(service.overview()).thenReturn(new BootstrapOverview("铜冶炼二期", "development",
                new DatabaseOverview("UP", "copper_phase2_dev", "8.0-test", 12, 1),
                new Counts(2, 8, 26), "INITIALIZATION_ONLY"));
        mvc.perform(get("/api/v1/bootstrap/overview"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.projectName").value("铜冶炼二期"))
                .andExpect(jsonPath("$.data.database.schemaName").value("copper_phase2_dev"))
                .andExpect(jsonPath("$.data.database.phase1TableCount").value(12))
                .andExpect(jsonPath("$.data.database.phase2TableCount").value(1))
                .andExpect(jsonPath("$.data.counts.productionEquipment").value(8))
                .andExpect(jsonPath("$.data.scope").value("INITIALIZATION_ONLY"));
    }

    @Test
    void sqlFailuresReturnTheSameSafeErrorEnvelope() throws Exception {
        when(service.overview()).thenThrow(new DataAccessResourceFailureException(
                "Access denied; internal-host; password=hidden-value"));
        mvc.perform(get("/api/v1/bootstrap/overview"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("DATABASE_UNAVAILABLE"))
                .andExpect(jsonPath("$.timestamp").exists())
                .andExpect(content().string(not(containsString("hidden-value"))))
                .andExpect(content().string(not(containsString("internal-host"))));
    }

    @Test
    void equipmentPreservesSharedIdentifiersAndNullablePerson() throws Exception {
        when(service.equipment()).thenReturn(new EquipmentList(List.of(
                new EquipmentItem(41, "SMELT-FUR-01", "1#熔化炉", 7, "车间一", null, false))));
        mvc.perform(get("/api/v1/equipment"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.items[0].id").value(41))
                .andExpect(jsonPath("$.data.items[0].code").value("SMELT-FUR-01"))
                .andExpect(jsonPath("$.data.items[0].workshopId").value(7))
                .andExpect(jsonPath("$.data.items[0].enabled").value(false));
    }

    @Test
    void unsupportedMethodUsesEnvelopeRatherThanAnHtmlError() throws Exception {
        mvc.perform(post("/api/v1/equipment")).andExpect(status().isMethodNotAllowed())
                .andExpect(jsonPath("$.code").value("METHOD_NOT_ALLOWED"));
        verifyNoInteractions(service);
    }
}
