package cn.copper.phase2.supplier;

import cn.copper.phase2.common.BusinessException;
import cn.copper.phase2.common.ErrorCode;
import cn.copper.phase2.common.PageResult;
import cn.copper.phase2.supplier.SupplierModels.SupplierDetail;
import cn.copper.phase2.supplier.SupplierModels.SupplierSummary;
import cn.copper.phase2.supplier.SupplierRepository.SupplierQuery;
import cn.copper.phase2.supplier.SupplierRepository.SupplierRow;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** S1-S01 / S1-S02 的读取契约：分页边界、ID 转字符串、缺失即 404。 */
class SupplierServiceTest {

    @Test
    void detailReturnsEquipmentIdsAsStringsInAscendingOrder() {
        SupplierRepository repository = mock(SupplierRepository.class);
        when(repository.findRow(7L)).thenReturn(Optional.of(new SupplierRow(7L, "华东机电", "张工",
                "021-0000", "备件供应", 3L, OffsetDateTime.parse("2026-11-04T09:00:00+08:00"))));
        when(repository.equipmentIds(7L)).thenReturn(List.of(2L, 11L));

        SupplierDetail detail = new SupplierService(repository).detail("7");

        assertEquals("7", detail.supplierId());
        assertEquals(List.of("2", "11"), detail.equipmentIds());
        assertEquals(3, detail.version());
        assertEquals("备件供应", detail.remark());
    }

    @Test
    void detailWithoutAssociationReturnsEmptyArrayNotNull() {
        SupplierRepository repository = mock(SupplierRepository.class);
        when(repository.findRow(7L)).thenReturn(Optional.of(new SupplierRow(7L, "华东机电", null,
                null, null, 1L, null)));
        when(repository.equipmentIds(7L)).thenReturn(List.of());

        SupplierDetail detail = new SupplierService(repository).detail("7");

        assertTrue(detail.equipmentIds().isEmpty());
    }

    @Test
    void missingSupplierIsNotFound() {
        SupplierRepository repository = mock(SupplierRepository.class);
        when(repository.findRow(404L)).thenReturn(Optional.empty());

        BusinessException error = assertThrows(BusinessException.class,
                () -> new SupplierService(repository).detail("404"));

        assertEquals(ErrorCode.NOT_FOUND, error.errorCode());
    }

    @Test
    void rejectsNonNumericSupplierId() {
        BusinessException error = assertThrows(BusinessException.class,
                () -> new SupplierService(mock(SupplierRepository.class)).detail("abc"));

        assertEquals(ErrorCode.VALIDATION_ERROR, error.errorCode());
    }

    @Test
    void pageSizeAboveHundredIsRejected() {
        BusinessException error = assertThrows(BusinessException.class,
                () -> new SupplierService(mock(SupplierRepository.class))
                        .search(null, null, 1, 101));

        assertEquals(ErrorCode.VALIDATION_ERROR, error.errorCode());
    }

    @Test
    void searchPassesFiltersThroughAndReturnsPagingMetadata() {
        SupplierRepository repository = mock(SupplierRepository.class);
        when(repository.search(any())).thenReturn(List.of(
                new SupplierSummary("7", "华东机电", "张工", "021-0000", 2)));
        when(repository.count(any())).thenReturn(1L);

        PageResult<SupplierSummary> page = new SupplierService(repository)
                .search(" 机电 ", "1", 2, 5);

        assertEquals(1, page.items().size());
        assertEquals(2, page.page());
        assertEquals(5, page.pageSize());
        assertEquals(1, page.total());
        assertEquals(2, page.items().get(0).equipmentCount());

        org.mockito.ArgumentCaptor<SupplierQuery> captor =
                org.mockito.ArgumentCaptor.forClass(SupplierQuery.class);
        verify(repository).search(captor.capture());
        assertEquals(" 机电 ", captor.getValue().keyword());
        assertEquals(1L, captor.getValue().equipmentId());
    }
}
