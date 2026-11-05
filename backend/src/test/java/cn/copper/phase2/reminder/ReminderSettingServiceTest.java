package cn.copper.phase2.reminder;

import cn.copper.phase2.common.BusinessException;
import cn.copper.phase2.common.ChangeLogModels.ChangeLogWrite;
import cn.copper.phase2.common.ChangeLogRepository;
import cn.copper.phase2.common.ErrorCode;
import cn.copper.phase2.common.IdempotencyService;
import cn.copper.phase2.reminder.ReminderSettingModels.ReminderSetting;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.OffsetDateTime;
import java.util.Map;
import java.util.Optional;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** S1-T02 / S1-T03：默认值补齐、非负校验、版本冲突与留痕。 */
class ReminderSettingServiceTest {
    private static final ObjectMapper JSON = new ObjectMapper();

    private static ReminderSetting setting(int warranty, int maintenance, long version) {
        return new ReminderSetting(warranty, maintenance, version,
                OffsetDateTime.parse("2026-11-04T09:00:00+08:00"));
    }

    @SuppressWarnings("unchecked")
    private static IdempotencyService passThrough() {
        IdempotencyService idempotency = mock(IdempotencyService.class);
        when(idempotency.execute(anyString(), anyString(), any(), any(), any()))
                .thenAnswer(invocation -> ((Supplier<Object>) invocation.getArgument(4)).get());
        return idempotency;
    }

    private static JsonNode body(String warranty, String maintenance, int version) throws Exception {
        return JSON.readTree("""
                {"requestId":"9a8b7c00-0000-4000-8000-000000000033","operatorName":"设备管理员",
                 "expectedVersion":%d,"warrantyLeadDays":%s,"maintenanceLeadDays":%s}
                """.formatted(version, warranty, maintenance));
    }

    @Test
    void missingRowIsBackfilledWithContractDefaults() {
        ReminderSettingRepository repository = mock(ReminderSettingRepository.class);
        when(repository.find()).thenReturn(Optional.empty(), Optional.of(setting(30, 30, 1)));

        ReminderSetting current = new ReminderSettingService(repository,
                mock(ChangeLogRepository.class), passThrough()).current();

        assertEquals(30, current.warrantyLeadDays());
        assertEquals(30, current.maintenanceLeadDays());
        verify(repository).insertDefault();
    }

    @Test
    void rejectsNegativeLeadDays() throws Exception {
        BusinessException error = assertThrows(BusinessException.class,
                () -> new ReminderSettingService(mock(ReminderSettingRepository.class),
                        mock(ChangeLogRepository.class), passThrough())
                        .update(body("-1", "30", 1)));

        assertEquals(ErrorCode.VALIDATION_ERROR, error.errorCode());
        Map<?, ?> fields = (Map<?, ?>) error.details().get("fields");
        assertTrue(fields.containsKey("warrantyLeadDays"), fields.toString());
    }

    @Test
    void rejectsNonIntegerLeadDays() throws Exception {
        BusinessException error = assertThrows(BusinessException.class,
                () -> new ReminderSettingService(mock(ReminderSettingRepository.class),
                        mock(ChangeLogRepository.class), passThrough())
                        .update(body("30.5", "30", 1)));

        Map<?, ?> fields = (Map<?, ?>) error.details().get("fields");
        assertTrue(fields.containsKey("warrantyLeadDays"), fields.toString());
    }

    @Test
    void staleVersionIsRejectedBeforeWriting() throws Exception {
        ReminderSettingRepository repository = mock(ReminderSettingRepository.class);
        when(repository.find()).thenReturn(Optional.of(setting(30, 30, 4)));
        when(repository.lock()).thenReturn(Optional.of(setting(30, 30, 4)));

        BusinessException error = assertThrows(BusinessException.class,
                () -> new ReminderSettingService(repository, mock(ChangeLogRepository.class),
                        passThrough()).update(body("15", "45", 1)));

        assertEquals(ErrorCode.VERSION_CONFLICT, error.errorCode());
        verify(repository, never()).update(anyInt(), anyInt(), anyLong());
    }

    @Test
    void updateWritesBothValuesAndAuditTrail() throws Exception {
        ReminderSettingRepository repository = mock(ReminderSettingRepository.class);
        ChangeLogRepository changeLog = mock(ChangeLogRepository.class);
        when(repository.find()).thenReturn(Optional.of(setting(30, 30, 1)),
                Optional.of(setting(15, 45, 2)));
        when(repository.lock()).thenReturn(Optional.of(setting(30, 30, 1)));
        when(repository.update(15, 45, 1)).thenReturn(1);

        ReminderSetting updated = new ReminderSettingService(repository, changeLog, passThrough())
                .update(body("15", "45", 1));

        assertEquals(15, updated.warrantyLeadDays());
        assertEquals(45, updated.maintenanceLeadDays());
        assertEquals(2, updated.version());

        ArgumentCaptor<ChangeLogWrite> captor = ArgumentCaptor.forClass(ChangeLogWrite.class);
        verify(changeLog).insert(captor.capture());
        assertEquals("REMINDER_SETTING", captor.getValue().objectType());
        assertEquals(1, captor.getValue().versionBefore());
        assertEquals(2, captor.getValue().versionAfter());
    }
}
