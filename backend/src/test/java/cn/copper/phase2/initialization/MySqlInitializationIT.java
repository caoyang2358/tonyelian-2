package cn.copper.phase2.initialization;

import cn.copper.phase2.equipment.EquipmentService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** Optional read-only verification against the separately initialized MySQL development database. */
@SpringBootTest
@AutoConfigureMockMvc
class MySqlInitializationIT {
    @Autowired private JdbcTemplate jdbc;
    @Autowired private InitializationService service;
    @Autowired private EquipmentService equipmentService;
    @Autowired private MockMvc mvc;

    @Test
    void verifiesActualMySqlAndReadOnlyInitializationChain() throws Exception {
        String product = jdbc.execute((ConnectionCallback<String>) c -> c.getMetaData().getDatabaseProductName());
        assertEquals("MySQL", product, "This check must not pass through an H2 compatibility substitute");
        assertEquals("+08:00", jdbc.queryForObject("SELECT @@session.time_zone", String.class));
        var overview = service.overview();
        assertEquals(System.getenv().getOrDefault("DB_NAME", "copper_phase2_dev"), overview.database().schemaName());
        assertEquals(12, overview.database().phase1TableCount());
        assertTrue(overview.database().phase2TableCount() >= 1);
        assertTrue(overview.counts().productionEquipment() > 0);
        assertEquals(overview.counts().productionEquipment(), equipmentService.equipment().items().size());
        mvc.perform(get("/api/v1/health/live")).andExpect(status().isOk());
        mvc.perform(get("/api/v1/health/ready")).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.database.status").value("UP"));
        mvc.perform(get("/api/v1/bootstrap/overview")).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.scope").value("INITIALIZATION_ONLY"));
        mvc.perform(get("/api/v1/equipment")).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items").isArray());
    }
}
