package cn.copper.phase2.sensor;

import cn.copper.phase2.sensor.SensorRepository.SensorQuery;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** S1-P01/P06/P11 的 SQL 与取值口径：筛选拼接、ID 串拆分、配置状态判定。 */
class SensorRepositoryTest {

    @Test
    void noFilterMeansNoCondition() {
        List<Object> args = new ArrayList<>();

        String sql = SensorRepository.where(
                new SensorQuery(null, null, null, null, 1, 20), args);

        assertEquals(" WHERE 1 = 1", sql);
        assertTrue(args.isEmpty());
    }

    @Test
    void keywordMatchesSensorAndEquipmentIdentity() {
        List<Object> args = new ArrayList<>();

        String sql = SensorRepository.where(
                new SensorQuery(null, "TEMP", null, null, 1, 20), args);

        // 编码、名称、设备编码、设备名称四路都要能搜到，四个占位符共用一个关键词。
        assertTrue(sql.contains("t.sensor_code LIKE ?"), sql);
        assertTrue(sql.contains("t.sensor_name LIKE ?"), sql);
        assertTrue(sql.contains("t.equipment_code LIKE ?"), sql);
        assertTrue(sql.contains("t.equipment_name LIKE ?"), sql);
        assertEquals(List.of("%TEMP%", "%TEMP%", "%TEMP%", "%TEMP%"), args);
    }

    @Test
    void filtersComposeInFixedOrder() {
        List<Object> args = new ArrayList<>();

        SensorRepository.where(new SensorQuery(3L, null, Boolean.FALSE, "STALE", 1, 20), args);

        assertEquals(List.of(3L, Boolean.FALSE, "STALE"), args);
    }

    @Test
    void baseSqlComputesMonitoringStatusInsteadOfReadingAColumn() {
        // 状态是现算列：库里没有 monitoring_status 字段，直接选它会在真实调用时报列不存在。
        assertTrue(SensorRepository.BASE_SQL.contains("AS monitoring_status"),
                SensorRepository.BASE_SQL);
        assertTrue(SensorRepository.BASE_SQL.contains("CASE WHEN s.enabled = 0 THEN 'DISABLED'"),
                "停用必须优先于缺数据判定");
        assertTrue(SensorRepository.BASE_SQL.contains("MAX(refresh_time)"),
                "最新刷新时间必须来自 ads_parameter_monitor");
    }

    @Test
    void parameterSqlPicksLatestRuleVersionOnly() {
        assertTrue(SensorRepository.PARAMETER_SQL.contains("MAX(rule_version_id)"),
                SensorRepository.PARAMETER_SQL);
        assertTrue(SensorRepository.PARAMETER_SQL.contains("ORDER BY dp.display_order"),
                SensorRepository.PARAMETER_SQL);
    }

    @Test
    void configurationNeedsBothParametersAndSource() {
        assertEquals(SensorModels.CONFIGURATION_PENDING,
                SensorRepository.configurationState(0, "DEV_DEMO_METADATA"));
        assertEquals(SensorModels.CONFIGURATION_PENDING,
                SensorRepository.configurationState(2, null));
        assertEquals(SensorModels.CONFIGURATION_PENDING,
                SensorRepository.configurationState(2, "   "));
        assertEquals(SensorModels.CONFIGURATION_READY,
                SensorRepository.configurationState(2, "DEV_DEMO_METADATA"));
    }

    @Test
    void affectedEventIdsSplitFromGroupConcat() {
        assertEquals(List.of(), SensorRepository.splitIds(null));
        assertEquals(List.of(), SensorRepository.splitIds(""));
        assertEquals(List.of(), SensorRepository.splitIds("  "));
        assertEquals(List.of("7", "9"), SensorRepository.splitIds("7,9"));
        assertEquals(List.of("7", "9"), SensorRepository.splitIds(" 7 , 9 "));
    }

    @Test
    void decimalKeepsFullScale() {
        assertEquals("12.500000", SensorRepository.decimalText(new BigDecimal("12.500000")));
        assertEquals("0.000001", SensorRepository.decimalText(new BigDecimal("0.000001")));
        assertNull(SensorRepository.decimalText(null));
    }
}
