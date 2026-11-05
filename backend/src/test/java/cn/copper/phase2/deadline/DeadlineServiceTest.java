package cn.copper.phase2.deadline;

import cn.copper.phase2.common.BusinessException;
import cn.copper.phase2.common.ErrorCode;
import cn.copper.phase2.common.PageResult;
import cn.copper.phase2.deadline.DeadlineModels.DeadlineItem;
import cn.copper.phase2.deadline.DeadlineRepository.DeadlineQuery;
import cn.copper.phase2.reminder.ReminderSettingModels.ReminderSetting;
import cn.copper.phase2.reminder.ReminderSettingService;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** S1-T01 的读取契约：分页边界、枚举归一、提前量取自设置表。 */
class DeadlineServiceTest {

    private static ReminderSettingService reminderWith(int warranty, int maintenance) {
        ReminderSettingService reminder = mock(ReminderSettingService.class);
        when(reminder.current()).thenReturn(new ReminderSetting(warranty, maintenance, 4L, null));
        return reminder;
    }

    private static DeadlineRepository emptyRepository() {
        DeadlineRepository repository = mock(DeadlineRepository.class);
        when(repository.search(any(), anyLong(), anyLong(), any())).thenReturn(List.of());
        when(repository.count(any(), anyLong(), anyLong(), any())).thenReturn(0L);
        return repository;
    }

    @Test
    void passesConfiguredLeadDaysAndBusinessDateToRepository() {
        DeadlineRepository repository = emptyRepository();

        PageResult<DeadlineItem> result = new DeadlineService(repository, reminderWith(15, 45))
                .search(null, null, null, null, null, null);

        ArgumentCaptor<DeadlineQuery> captor = ArgumentCaptor.forClass(DeadlineQuery.class);
        verify(repository).search(captor.capture(), eq(15L), eq(45L), any(LocalDate.class));
        verify(repository).count(any(), eq(15L), eq(45L), any(LocalDate.class));
        assertEquals(1, result.page());
        assertEquals(20, result.pageSize());
        assertTrue(result.items().isEmpty());
    }

    @Test
    void reminderTypeAndStateAreCaseInsensitive() {
        DeadlineRepository repository = emptyRepository();

        new DeadlineService(repository, reminderWith(30, 30))
                .search(null, null, "warranty", "due_soon", 1, 10);

        ArgumentCaptor<DeadlineQuery> captor = ArgumentCaptor.forClass(DeadlineQuery.class);
        verify(repository).search(captor.capture(), anyLong(), anyLong(), any());
        assertEquals("WARRANTY", captor.getValue().reminderType());
        assertEquals("DUE_SOON", captor.getValue().expiryState());
    }

    @Test
    void blankFiltersMeanNoFiltering() {
        DeadlineRepository repository = emptyRepository();

        new DeadlineService(repository, reminderWith(30, 30))
                .search("  ", "", " ", null, 1, 10);

        ArgumentCaptor<DeadlineQuery> captor = ArgumentCaptor.forClass(DeadlineQuery.class);
        verify(repository).search(captor.capture(), anyLong(), anyLong(), any());
        assertNull(captor.getValue().equipmentId());
        assertNull(captor.getValue().workshopId());
        assertNull(captor.getValue().reminderType());
    }

    @Test
    void unknownReminderTypeIsRejectedInsteadOfReturningEmptyPage() {
        DeadlineRepository repository = mock(DeadlineRepository.class);
        DeadlineService service = new DeadlineService(repository, reminderWith(30, 30));

        BusinessException error = assertThrows(BusinessException.class,
                () -> service.search(null, null, "WARRANTY_EXPIRED", null, 1, 20));

        assertEquals(ErrorCode.VALIDATION_ERROR, error.errorCode());
        assertTrue(String.valueOf(error.details()).contains("reminderType"), String.valueOf(error.details()));
        verifyNoInteractions(repository);
    }

    @Test
    void unknownExpiryStateIsRejected() {
        DeadlineRepository repository = mock(DeadlineRepository.class);
        DeadlineService service = new DeadlineService(repository, reminderWith(30, 30));

        BusinessException error = assertThrows(BusinessException.class,
                () -> service.search(null, null, null, "SOON", 1, 20));

        assertEquals(ErrorCode.VALIDATION_ERROR, error.errorCode());
        verifyNoInteractions(repository);
    }

    @Test
    void nonNumericEquipmentIdIsAFieldError() {
        DeadlineRepository repository = mock(DeadlineRepository.class);
        DeadlineService service = new DeadlineService(repository, reminderWith(30, 30));

        BusinessException error = assertThrows(BusinessException.class,
                () -> service.search("abc", null, null, null, 1, 20));

        assertEquals(ErrorCode.VALIDATION_ERROR, error.errorCode());
        assertTrue(String.valueOf(error.details()).contains("equipmentId"), String.valueOf(error.details()));
    }

    @Test
    void pageAndPageSizeAreBounded() {
        DeadlineService service = new DeadlineService(emptyRepository(), reminderWith(30, 30));

        assertEquals(ErrorCode.VALIDATION_ERROR, assertThrows(BusinessException.class,
                () -> service.search(null, null, null, null, 0, 20)).errorCode());
        assertEquals(ErrorCode.VALIDATION_ERROR, assertThrows(BusinessException.class,
                () -> service.search(null, null, null, null, 1, 0)).errorCode());
        assertEquals(ErrorCode.VALIDATION_ERROR, assertThrows(BusinessException.class,
                () -> service.search(null, null, null, null, 1, 101)).errorCode());
    }
}
