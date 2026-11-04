package cn.copper.phase2.sensor;

import cn.copper.phase2.common.BusinessException;
import cn.copper.phase2.common.ErrorCode;
import cn.copper.phase2.common.PageResult;
import cn.copper.phase2.sensor.SensorModels.LatestReading;
import cn.copper.phase2.sensor.SensorModels.SensorDetail;
import cn.copper.phase2.sensor.SensorRepository.MonitorRow;
import cn.copper.phase2.sensor.SensorRepository.ParameterContext;
import cn.copper.phase2.sensor.SensorRepository.SensorQuery;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** S1-P01/P06/P09/P10/P11 的读取契约：分页边界、枚举归一、缺值与过期的区分。 */
class SensorServiceTest {
    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");

    private static SensorRepository emptyRepository() {
        SensorRepository repository = mock(SensorRepository.class);
        when(repository.search(any(), any())).thenReturn(List.of());
        when(repository.count(any(), any())).thenReturn(0L);
        when(repository.parameters(anyLong(), anyInt(), anyInt())).thenReturn(List.of());
        when(repository.countParameters(anyLong())).thenReturn(0L);
        when(repository.ruleVersions(anyLong(), anyInt(), anyInt())).thenReturn(List.of());
        when(repository.countRuleVersions(anyLong())).thenReturn(0L);
        return repository;
    }

    /** 详情依赖关联检查来填 deletion，测试里用真实 checker 包住同一个 mock 仓储。 */
    private static SensorService service(SensorRepository repository, long staleAfterMinutes) {
        return new SensorService(repository, new SensorReferenceChecker(repository), staleAfterMinutes);
    }

    private static ParameterContext context(boolean parameterEnabled, boolean sensorEnabled) {
        return new ParameterContext(6L, 1L, "SPEED-001", "speed", "牵引速度",
                "M_PER_MIN", "DECIMAL", parameterEnabled, sensorEnabled, "DEV_DEMO_METADATA");
    }

    private static SensorDetail detailView() {
        return new SensorDetail("1", "SPEED-001", "牵引速度", "3", "CAST-01", "1#结晶牵引机",
                "1", "车间1 · 铜杆生产", "速度", "DEV_DEMO_METADATA", null, null,
                true, 1L, false, SensorModels.MONITORING_UNKNOWN,
                SensorModels.CONFIGURATION_READY, 0L, List.of(),
                new SensorModels.DeletionState(true, List.of()));
    }

    // ------------------------------------------------------------ P01

    @Test
    void listDefaultsToFirstPageOfTwenty() {
        SensorRepository repository = emptyRepository();

        PageResult<?> result = service(repository, 15)
                .search(null, null, null, null, null, null);

        ArgumentCaptor<SensorQuery> captor = ArgumentCaptor.forClass(SensorQuery.class);
        verify(repository).search(captor.capture(), any());
        assertEquals(1, result.page());
        assertEquals(20, result.pageSize());
        assertEquals(1, captor.getValue().page());
        assertEquals(20, captor.getValue().pageSize());
    }

    @Test
    void staleThresholdMovesWithConfiguredMinutes() {
        SensorRepository repository = emptyRepository();
        OffsetDateTime before = OffsetDateTime.now(ZONE);

        service(repository, 60).search(null, null, null, null, 1, 10);

        ArgumentCaptor<OffsetDateTime> captor = ArgumentCaptor.forClass(OffsetDateTime.class);
        verify(repository).search(any(), captor.capture());
        // 阈值应约为「现在 - 60 分钟」，留 5 秒容差给执行耗时。
        long diffSeconds = java.time.Duration.between(captor.getValue(), before).toSeconds();
        assertTrue(Math.abs(diffSeconds - 3600) < 5, "阈值偏差过大：" + diffSeconds);
    }

    @Test
    void monitoringStatusIsCaseInsensitiveAndValidated() {
        SensorRepository repository = emptyRepository();
        SensorService service = service(repository, 15);

        service.search(null, null, null, "online", 1, 10);
        ArgumentCaptor<SensorQuery> captor = ArgumentCaptor.forClass(SensorQuery.class);
        verify(repository).search(captor.capture(), any());
        assertEquals("ONLINE", captor.getValue().monitoringStatus());

        BusinessException error = assertThrows(BusinessException.class,
                () -> service.search(null, null, null, "OFFLINE", 1, 10));
        assertEquals(ErrorCode.VALIDATION_ERROR, error.errorCode());
        assertTrue(String.valueOf(error.details()).contains("monitoringStatus"),
                String.valueOf(error.details()));
    }

    @Test
    void blankKeywordMeansNoFilter() {
        SensorRepository repository = emptyRepository();

        service(repository, 15).search("  ", "   ", null, null, 1, 10);

        ArgumentCaptor<SensorQuery> captor = ArgumentCaptor.forClass(SensorQuery.class);
        verify(repository).search(captor.capture(), any());
        assertNull(captor.getValue().equipmentId());
        assertNull(captor.getValue().keyword());
    }

    @Test
    void pageAndPageSizeAreBounded() {
        SensorService service = service(emptyRepository(), 15);

        assertEquals(ErrorCode.VALIDATION_ERROR, assertThrows(BusinessException.class,
                () -> service.search(null, null, null, null, 0, 20)).errorCode());
        assertEquals(ErrorCode.VALIDATION_ERROR, assertThrows(BusinessException.class,
                () -> service.search(null, null, null, null, 1, 101)).errorCode());
    }

    // ------------------------------------------------------------ P02/P06

    @Test
    void unknownSensorIsNotFound() {
        SensorRepository repository = emptyRepository();
        when(repository.detail(anyLong(), any())).thenReturn(Optional.empty());
        SensorService service = service(repository, 15);

        assertEquals(ErrorCode.NOT_FOUND, assertThrows(BusinessException.class,
                () -> service.detail("99")).errorCode());
        assertEquals(ErrorCode.NOT_FOUND, assertThrows(BusinessException.class,
                () -> service.parameters("99", 1, 10)).errorCode());
    }

    @Test
    void detailCarriesParameterSummary() {
        SensorRepository repository = emptyRepository();
        when(repository.detail(eq(1L), any())).thenReturn(Optional.of(detailView()));
        when(repository.parameters(eq(1L), eq(1), anyInt())).thenReturn(List.of());

        SensorDetail detail = service(repository, 15).detail("1");

        assertEquals("SPEED-001", detail.sensorCode());
        assertEquals(SensorModels.CONFIGURATION_READY, detail.configurationState());
        // 摘要字段必须原样带到详情：前端用同一套解析处理列表与详情，缺一个就判响应不完整。
        assertEquals(1L, detail.parameterCount());
        assertTrue(detail.parameters().isEmpty());
    }

    @Test
    void nonNumericSensorIdIsAFieldError() {
        SensorRepository repository = emptyRepository();

        BusinessException error = assertThrows(BusinessException.class,
                () -> service(repository, 15).detail("abc"));

        assertEquals(ErrorCode.VALIDATION_ERROR, error.errorCode());
        verifyNoInteractions(repository);
    }

    // ------------------------------------------------------------ P09

    @Test
    void missingReadingIsUnknownNotStale() {
        SensorRepository repository = emptyRepository();
        when(repository.parameterContext(6L)).thenReturn(Optional.of(context(true, true)));
        when(repository.latest(6L)).thenReturn(Optional.empty());

        LatestReading reading = service(repository, 15).latest("6");

        assertEquals(SensorModels.MONITORING_UNKNOWN, reading.monitoringStatus());
        assertNull(reading.value());
        assertNull(reading.collectTime());
        assertEquals(List.of("latestReading"), reading.missingFields());
    }

    @Test
    void disabledParameterIsDisabledEvenWithFreshData() {
        SensorRepository repository = emptyRepository();
        when(repository.parameterContext(6L)).thenReturn(Optional.of(context(false, true)));
        when(repository.latest(6L)).thenReturn(Optional.of(new MonitorRow(
                new BigDecimal("12.5"), null, "M_PER_MIN", "GOOD", "NORMAL",
                OffsetDateTime.now(ZONE), OffsetDateTime.now(ZONE))));

        LatestReading reading = service(repository, 15).latest("6");

        assertEquals(SensorModels.MONITORING_DISABLED, reading.monitoringStatus());
        assertEquals("12.5", reading.value());
    }

    @Test
    void freshReadingIsOnlineAndOldReadingIsStale() {
        SensorRepository repository = emptyRepository();
        when(repository.parameterContext(6L)).thenReturn(Optional.of(context(true, true)));
        when(repository.latest(6L)).thenReturn(Optional.of(new MonitorRow(
                new BigDecimal("12.5"), null, "M_PER_MIN", "GOOD", "NORMAL",
                OffsetDateTime.now(ZONE).minusMinutes(1),
                OffsetDateTime.now(ZONE).minusMinutes(1))));

        assertEquals(SensorModels.MONITORING_ONLINE,
                service(repository, 15).latest("6").monitoringStatus());

        when(repository.latest(6L)).thenReturn(Optional.of(new MonitorRow(
                new BigDecimal("12.5"), null, "M_PER_MIN", "GOOD", "NORMAL",
                OffsetDateTime.now(ZONE).minusHours(3),
                OffsetDateTime.now(ZONE).minusHours(3))));

        assertEquals(SensorModels.MONITORING_STALE,
                service(repository, 15).latest("6").monitoringStatus());
    }

    @Test
    void readingWithoutUnitFallsBackToParameterUnit() {
        SensorRepository repository = emptyRepository();
        when(repository.parameterContext(6L)).thenReturn(Optional.of(context(true, true)));
        when(repository.latest(6L)).thenReturn(Optional.of(new MonitorRow(
                new BigDecimal("12.500000"), null, null, "GOOD", "NORMAL",
                OffsetDateTime.now(ZONE), OffsetDateTime.now(ZONE))));

        LatestReading reading = service(repository, 15).latest("6");

        assertEquals("12.500000", reading.value());
        assertEquals("M_PER_MIN", reading.unitCode());
    }

    // ------------------------------------------------------------ P10

    @Test
    void historyRequiresBothBounds() {
        SensorRepository repository = emptyRepository();
        when(repository.parameterContext(6L)).thenReturn(Optional.of(context(true, true)));
        SensorService service = service(repository, 15);

        assertEquals(ErrorCode.VALIDATION_ERROR, assertThrows(BusinessException.class,
                () -> service.history("6", null, "2026-11-04", 1, 10)).errorCode());
        assertEquals(ErrorCode.VALIDATION_ERROR, assertThrows(BusinessException.class,
                () -> service.history("6", "2026-11-04", null, 1, 10)).errorCode());
        verify(repository, never()).history(anyString(), anyString(), any(), any(), anyInt(), anyInt());
    }

    @Test
    void historyRejectsReversedAndOversizedRange() {
        SensorRepository repository = emptyRepository();
        when(repository.parameterContext(6L)).thenReturn(Optional.of(context(true, true)));
        SensorService service = service(repository, 15);

        assertEquals(ErrorCode.VALIDATION_ERROR, assertThrows(BusinessException.class,
                () -> service.history("6", "2026-11-05", "2026-11-04", 1, 10)).errorCode());
        assertEquals(ErrorCode.VALIDATION_ERROR, assertThrows(BusinessException.class,
                () -> service.history("6", "2026-01-01", "2026-11-04", 1, 10)).errorCode());
    }

    @Test
    void dateOnlyToBoundCoversWholeDay() {
        SensorRepository repository = emptyRepository();
        when(repository.parameterContext(6L)).thenReturn(Optional.of(context(true, true)));
        when(repository.history(anyString(), anyString(), any(), any(), anyInt(), anyInt()))
                .thenReturn(List.of());
        when(repository.countHistory(anyString(), anyString(), any(), any())).thenReturn(0L);

        service(repository, 15).history("6", "2026-11-04", "2026-11-04", 1, 10);

        ArgumentCaptor<OffsetDateTime> captor = ArgumentCaptor.forClass(OffsetDateTime.class);
        verify(repository).history(eq("SPEED-001"), eq("speed"), captor.capture(),
                captor.capture(), eq(1), eq(10));
        List<OffsetDateTime> bounds = captor.getAllValues();
        assertEquals(OffsetDateTime.parse("2026-11-04T00:00:00+08:00"), bounds.get(0));
        assertEquals(OffsetDateTime.parse("2026-11-05T00:00:00+08:00"), bounds.get(1));
    }

    @Test
    void localDateTimeIsReadAsBusinessZone() {
        assertEquals(OffsetDateTime.parse("2026-11-04T08:00:00+08:00"),
                SensorService.parseTime("2026-11-04T08:00:00", "from", false));
        // 带偏移的输入按时刻换算到厂区偏移，不能改变所指的时间点。
        assertEquals(OffsetDateTime.parse("2026-11-04T08:00:00+08:00"),
                SensorService.parseTime("2026-11-04T00:00:00Z", "from", false));
        assertEquals(ErrorCode.VALIDATION_ERROR, assertThrows(BusinessException.class,
                () -> SensorService.parseTime("昨天", "from", false)).errorCode());
    }

    // ------------------------------------------------------------ P11

    @Test
    void ruleVersionsPageForUnknownParameterIsNotFound() {
        SensorRepository repository = emptyRepository();
        when(repository.parameterContext(404L)).thenReturn(Optional.empty());

        assertEquals(ErrorCode.NOT_FOUND, assertThrows(BusinessException.class,
                () -> service(repository, 15).ruleVersions("404", 1, 10)).errorCode());
    }

    @Test
    void ruleVersionsUseRequestedPage() {
        SensorRepository repository = emptyRepository();
        when(repository.parameterContext(6L)).thenReturn(Optional.of(context(true, true)));

        service(repository, 15).ruleVersions("6", 2, 5);

        verify(repository).ruleVersions(6L, 2, 5);
        verify(repository).countRuleVersions(6L);
    }
}
