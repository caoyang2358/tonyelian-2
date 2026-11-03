package cn.copper.phase2.equipment;

import cn.copper.phase2.equipment.EquipmentModels.EquipmentItem;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentMatchers;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

import java.sql.ResultSet;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class EquipmentRepositoryTest {
    @Test
    void equipmentSqlMapsSharedIdentityAndBooleanWithoutInventingDefaults() throws Exception {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        ResultSet row = mock(ResultSet.class);
        when(row.getLong("id")).thenReturn(903L);
        when(row.getString("code")).thenReturn("DRAW-03");
        when(row.getString("name")).thenReturn("3#拉丝机");
        when(row.getLong("workshop_id")).thenReturn(82L);
        when(row.getString("workshop_name")).thenReturn("车间2");
        when(row.getString("responsible_person")).thenReturn(null);
        when(row.getBoolean("enabled")).thenReturn(false);
        when(jdbc.query(eq(EquipmentRepository.EQUIPMENT_SQL),
                ArgumentMatchers.<RowMapper<EquipmentItem>>any())).thenAnswer(call -> {
            RowMapper<EquipmentItem> mapper = call.getArgument(1);
            return List.of(mapper.mapRow(row, 0));
        });

        List<EquipmentItem> result = new EquipmentRepository(jdbc).productionEquipment();
        assertEquals(1, result.size());
        assertEquals(new EquipmentItem(903, "DRAW-03", "3#拉丝机", 82, "车间2", null, false), result.get(0));
        assertTrue(EquipmentRepository.EQUIPMENT_SQL.contains("e.equipment_usage = 'PRODUCTION'"));
        assertTrue(EquipmentRepository.EQUIPMENT_SQL.contains("w.workshop_id = e.workshop_id"));
        assertFalse(EquipmentRepository.EQUIPMENT_SQL.contains("e.enabled ="));
    }
}
