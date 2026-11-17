package cn.copper.phase2.energy;

import cn.copper.phase2.common.BusinessException;
import cn.copper.phase2.common.ChangeLogModels.ChangeLogWrite;
import cn.copper.phase2.common.ChangeLogRepository;
import cn.copper.phase2.common.ErrorCode;
import cn.copper.phase2.common.IdempotencyService;
import cn.copper.phase2.energy.EnergyModels.PriceCorrectionResult;
import cn.copper.phase2.energy.EnergyModels.PriceWriteResult;
import cn.copper.phase2.energy.EnergyRepository.PriceRow;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * S2-E05 / S2-E06 的写入契约。
 *
 * <p>这里盯的是几个容易被当成细节、但一错就伤到历史账的地方：</p>
 * <ul>
 *   <li>零价必须能存，负数必须存不进去——两者都"看起来像 0"，含义完全不同；</li>
 *   <li>更正必须保留原行，只新增一条带 supersedes 的行，不能原地改；</li>
 *   <li>只改单价时新值与原值同键，不能因为"同能源同范围同生效日"就误判成重复；</li>
 *   <li>版本不符时一个字都不能写。</li>
 * </ul>
 */
class EnergyPriceWriteServiceTest {
    private static final ObjectMapper JSON = new ObjectMapper();

    private static JsonNode json(String raw) {
        try {
            return JSON.readTree(raw);
        } catch (Exception error) {
            throw new IllegalStateException(error);
        }
    }

    @SuppressWarnings("unchecked")
    private static IdempotencyService passThrough() {
        IdempotencyService idempotency = mock(IdempotencyService.class);
        when(idempotency.execute(anyString(), anyString(), any(), any(), any()))
                .thenAnswer(invocation -> ((Supplier<Object>) invocation.getArgument(4)).get());
        return idempotency;
    }

    private static PriceRow row(long priceId, String energyType, Long workshopId,
                                String effectiveDate, String price, long version) {
        return new PriceRow(priceId, energyType, workshopId, LocalDate.parse(effectiveDate),
                EnergyModels.UNIT_BY_TYPE.get(energyType), new BigDecimal(price), true, null,
                "张工", null, version,
                OffsetDateTime.parse("2027-01-25T09:00:00+08:00"));
    }

    private static final String CREATE_BODY = """
            {
              "requestId": "30000000-0000-4000-8000-000000000001",
              "energyType": "ELECTRICITY",
              "workshopId": "2",
              "effectiveDate": "2027-02-01",
              "unitCode": "kWh",
              "unitPriceYuanPerUnit": "1.1000000000",
              "operatorName": "张工"
            }
            """;

    private static EnergyPriceWriteService service(EnergyRepository repository,
                                                   ChangeLogRepository changeLog) {
        return new EnergyPriceWriteService(repository, changeLog, passThrough());
    }

    @Test
    void 新增生效价返回新价格与受影响范围() {
        EnergyRepository repository = mock(EnergyRepository.class);
        ChangeLogRepository changeLog = mock(ChangeLogRepository.class);
        when(repository.workshopExists(2L)).thenReturn(true);
        when(repository.existsCurrent(anyString(), any(), any(), any())).thenReturn(false);
        when(repository.insert(anyString(), any(), any(), anyString(), any(), any(), any(), anyString()))
                .thenReturn(9202L);
        when(repository.nextEffectiveDate(anyString(), any(), any(), any())).thenReturn(Optional.empty());
        when(changeLog.insert(any())).thenReturn(9600L);

        PriceWriteResult result = service(repository, changeLog).create(json(CREATE_BODY));

        assertEquals("9202", result.priceId());
        assertEquals(0, result.version());
        assertEquals("WORKSHOP", result.scope());
        assertEquals("1.1000000000", result.unitPriceYuanPerUnit());
        assertEquals(LocalDate.parse("2027-02-01"), result.affectedScope().from());
        // 后面没有更晚的价：影响一直向后生效，不编造一个结束日。
        assertNull(result.affectedScope().to());
        assertEquals(java.util.List.of("2"), result.affectedScope().workshopIds());
        // 核算服务未接入，不能伪造运行号。
        assertNull(result.costRunId());
    }

    @Test
    void 新增时写入建档留痕() {
        EnergyRepository repository = mock(EnergyRepository.class);
        ChangeLogRepository changeLog = mock(ChangeLogRepository.class);
        when(repository.workshopExists(anyLong())).thenReturn(true);
        when(repository.existsCurrent(anyString(), any(), any(), any())).thenReturn(false);
        when(repository.insert(anyString(), any(), any(), anyString(), any(), any(), any(), anyString()))
                .thenReturn(9202L);
        when(repository.nextEffectiveDate(anyString(), any(), any(), any())).thenReturn(Optional.empty());
        when(changeLog.insert(any())).thenReturn(9600L);

        service(repository, changeLog).create(json(CREATE_BODY));

        ArgumentCaptor<ChangeLogWrite> captor = ArgumentCaptor.forClass(ChangeLogWrite.class);
        verify(changeLog).insert(captor.capture());
        assertEquals("ENERGY_PRICE", captor.getValue().objectType());
        assertEquals("CREATE", captor.getValue().action());
        assertEquals("张工", captor.getValue().operatorName());
    }

    @Test
    void 同能源同范围同生效日重复时提示核对原价格() {
        EnergyRepository repository = mock(EnergyRepository.class);
        when(repository.workshopExists(anyLong())).thenReturn(true);
        when(repository.existsCurrent(anyString(), any(), any(), any())).thenReturn(true);

        BusinessException error = assertThrows(BusinessException.class,
                () -> service(repository, mock(ChangeLogRepository.class)).create(json(CREATE_BODY)));

        assertEquals(ErrorCode.DUPLICATE_CODE, error.errorCode());
        assertTrue(error.getMessage().contains("更正原价格"));
        verify(repository, never()).insert(anyString(), any(), any(), anyString(), any(), any(), any(), anyString());
    }

    @Test
    void 明确零价可以保存() {
        EnergyRepository repository = mock(EnergyRepository.class);
        ChangeLogRepository changeLog = mock(ChangeLogRepository.class);
        when(repository.workshopExists(anyLong())).thenReturn(true);
        when(repository.existsCurrent(anyString(), any(), any(), any())).thenReturn(false);
        when(repository.insert(anyString(), any(), any(), anyString(), any(), any(), any(), anyString()))
                .thenReturn(9205L);
        when(repository.nextEffectiveDate(anyString(), any(), any(), any())).thenReturn(Optional.empty());
        when(changeLog.insert(any())).thenReturn(9601L);

        PriceWriteResult result = service(repository, changeLog).create(json("""
                {
                  "requestId": "30000000-0000-4000-8000-000000000010",
                  "energyType": "WATER",
                  "workshopId": "1",
                  "effectiveDate": "2027-03-01",
                  "unitCode": "m³",
                  "unitPriceYuanPerUnit": "0",
                  "operatorName": "张工"
                }
                """));

        assertEquals("0.0000000000", result.unitPriceYuanPerUnit());
    }

    @Test
    void 负数单价被拒绝() {
        BusinessException error = assertThrows(BusinessException.class,
                () -> service(mock(EnergyRepository.class), mock(ChangeLogRepository.class))
                        .create(json("""
                                {
                                  "requestId": "r-1", "energyType": "ELECTRICITY", "workshopId": "2",
                                  "effectiveDate": "2027-02-01", "unitCode": "kWh",
                                  "unitPriceYuanPerUnit": "-0.5", "operatorName": "张工"
                                }
                                """)));
        assertEquals(ErrorCode.VALIDATION_ERROR, error.errorCode());
    }

    @Test
    void 单位与能源不符被拒绝() {
        // 电按立方米计价：这种组合在数据库里合法，但业务上一定是选错了。
        BusinessException error = assertThrows(BusinessException.class,
                () -> service(mock(EnergyRepository.class), mock(ChangeLogRepository.class))
                        .create(json("""
                                {
                                  "requestId": "r-2", "energyType": "ELECTRICITY", "workshopId": "2",
                                  "effectiveDate": "2027-02-01", "unitCode": "m³",
                                  "unitPriceYuanPerUnit": "1.0", "operatorName": "张工"
                                }
                                """)));
        assertEquals(ErrorCode.VALIDATION_ERROR, error.errorCode());
    }

    @Test
    void 超过十位小数的单价被拒绝() {
        // decimal(24,10) 会把第 11 位静默截断，宁可报错也不留下一个"看起来对"的数。
        BusinessException error = assertThrows(BusinessException.class,
                () -> service(mock(EnergyRepository.class), mock(ChangeLogRepository.class))
                        .create(json("""
                                {
                                  "requestId": "r-3", "energyType": "ELECTRICITY", "workshopId": "2",
                                  "effectiveDate": "2027-02-01", "unitCode": "kWh",
                                  "unitPriceYuanPerUnit": "1.12345678901", "operatorName": "张工"
                                }
                                """)));
        assertEquals(ErrorCode.VALIDATION_ERROR, error.errorCode());
    }

    @Test
    void 更正经停用原行并插入带supersedes的新行() {
        EnergyRepository repository = mock(EnergyRepository.class);
        ChangeLogRepository changeLog = mock(ChangeLogRepository.class);
        when(repository.lockRow(9201L)).thenReturn(Optional.of(
                row(9201L, "ELECTRICITY", 2L, "2027-01-01", "1.0000000000", 0)));
        when(repository.existsCurrent(anyString(), any(), any(), eq(9201L))).thenReturn(false);
        when(repository.workshopExists(2L)).thenReturn(true);
        when(repository.insert(anyString(), any(), any(), anyString(), any(), eq(9201L), any(), anyString()))
                .thenReturn(9203L);
        when(repository.nextEffectiveDate(anyString(), any(), any(), eq(9203L)))
                .thenReturn(Optional.of(LocalDate.parse("2027-02-01")));
        when(changeLog.insert(any())).thenReturn(9602L);

        PriceCorrectionResult result = service(repository, changeLog).correct("9201", json("""
                {
                  "requestId": "30000000-0000-4000-8000-000000000002",
                  "expectedVersion": 0,
                  "changes": {"unitPriceYuanPerUnit": "0.9500000000"},
                  "reason": "核对一月实际约定价格后更正原录入值",
                  "operatorName": "张工"
                }
                """));

        assertEquals("9201", result.previousPriceId());
        assertEquals("9203", result.priceId());
        assertEquals("9201", result.supersedesPriceId());
        assertEquals("9602", result.changeLogId());
        assertEquals("PENDING", result.status());
        // 下一条价 2-1 生效，本次影响到 1-31 为止。
        assertEquals(LocalDate.parse("2027-01-31"), result.affectedScope().to());
        // 原行只退出"当前有效"，不能被物理删除。
        verify(repository).deactivate(9201L);
    }

    @Test
    void 只改单价时不会把自己判成重复() {
        EnergyRepository repository = mock(EnergyRepository.class);
        ChangeLogRepository changeLog = mock(ChangeLogRepository.class);
        when(repository.lockRow(9201L)).thenReturn(Optional.of(
                row(9201L, "ELECTRICITY", 2L, "2027-01-01", "1.0000000000", 0)));
        when(repository.existsCurrent(anyString(), any(), any(), eq(9201L))).thenReturn(false);
        when(repository.workshopExists(2L)).thenReturn(true);
        when(repository.insert(anyString(), any(), any(), anyString(), any(), eq(9201L), any(), anyString()))
                .thenReturn(9203L);
        when(repository.nextEffectiveDate(anyString(), any(), any(), any())).thenReturn(Optional.empty());
        when(changeLog.insert(any())).thenReturn(9602L);

        service(repository, changeLog).correct("9201", json("""
                {
                  "requestId": "r-4", "expectedVersion": 0,
                  "changes": {"unitPriceYuanPerUnit": "0.95"},
                  "reason": "更正录入值", "operatorName": "张工"
                }
                """));

        // 关键：唯一键检查必须排除原价格自身，否则改单价永远报重复。
        verify(repository).existsCurrent("ELECTRICITY", 2L, LocalDate.parse("2027-01-01"), 9201L);
    }

    @Test
    void 版本不符时不写任何数据() {
        EnergyRepository repository = mock(EnergyRepository.class);
        when(repository.lockRow(9201L)).thenReturn(Optional.of(
                row(9201L, "ELECTRICITY", 2L, "2027-01-01", "1.0000000000", 3)));

        BusinessException error = assertThrows(BusinessException.class,
                () -> service(repository, mock(ChangeLogRepository.class)).correct("9201", json("""
                        {
                          "requestId": "r-5", "expectedVersion": 0,
                          "changes": {"unitPriceYuanPerUnit": "0.95"},
                          "reason": "更正录入值", "operatorName": "张工"
                        }
                        """)));

        assertEquals(ErrorCode.VERSION_CONFLICT, error.errorCode());
        verify(repository, never()).deactivate(anyLong());
        verify(repository, never()).insert(anyString(), any(), any(), anyString(), any(), any(), any(), anyString());
    }

    @Test
    void 更正时把范围改成通用需显式传null() {
        EnergyRepository repository = mock(EnergyRepository.class);
        ChangeLogRepository changeLog = mock(ChangeLogRepository.class);
        when(repository.lockRow(9201L)).thenReturn(Optional.of(
                row(9201L, "ELECTRICITY", 2L, "2027-01-01", "1.0000000000", 0)));
        when(repository.existsCurrent(anyString(), isNull(), any(), eq(9201L))).thenReturn(false);
        when(repository.insert(anyString(), isNull(), any(), anyString(), any(), eq(9201L), any(), anyString()))
                .thenReturn(9204L);
        when(repository.nextEffectiveDate(anyString(), isNull(), any(), any())).thenReturn(Optional.empty());
        when(changeLog.insert(any())).thenReturn(9603L);

        service(repository, changeLog).correct("9201", json("""
                {
                  "requestId": "r-6", "expectedVersion": 0,
                  "changes": {"workshopId": null},
                  "reason": "该价格实际为全厂通用价", "operatorName": "张工"
                }
                """));

        // 车间被显式置空：查唯一键与插入都必须按"通用范围"走。
        verify(repository).existsCurrent("ELECTRICITY", null, LocalDate.parse("2027-01-01"), 9201L);
    }

    @Test
    void 更正缺原因被拒绝() {
        BusinessException error = assertThrows(BusinessException.class,
                () -> service(mock(EnergyRepository.class), mock(ChangeLogRepository.class))
                        .correct("9201", json("""
                                {
                                  "requestId": "r-7", "expectedVersion": 0,
                                  "changes": {"unitPriceYuanPerUnit": "0.95"},
                                  "operatorName": "张工"
                                }
                                """)));
        assertEquals(ErrorCode.VALIDATION_ERROR, error.errorCode());
        // 更正原因不可空，缺失时应说"必填"，而不是笼统的"请显式传 null"。
        @SuppressWarnings("unchecked")
        java.util.Map<String, Object> fields = (java.util.Map<String, Object>) error.details().get("fields");
        assertEquals("该字段为必填项。", fields.get("reason"));
    }

    @Test
    void 已停用的历史价不能再更正() {
        EnergyRepository repository = mock(EnergyRepository.class);
        PriceRow stale = new PriceRow(9201L, "ELECTRICITY", 2L, LocalDate.parse("2027-01-01"),
                "kWh", new BigDecimal("0.8000000000"), false, null, "张工", null, 0,
                OffsetDateTime.parse("2027-01-25T09:00:00+08:00"));
        when(repository.lockRow(9201L)).thenReturn(Optional.of(stale));

        BusinessException error = assertThrows(BusinessException.class,
                () -> service(repository, mock(ChangeLogRepository.class)).correct("9201", json("""
                        {
                          "requestId": "r-8", "expectedVersion": 0,
                          "changes": {"unitPriceYuanPerUnit": "0.86"},
                          "reason": "重复更正测试", "operatorName": "张工"
                        }
                        """)));

        // 报"已被更正替换"而不是"该能源已有价格"——后者会把人引去改另一条。
        assertEquals(ErrorCode.VERSION_CONFLICT, error.errorCode());
        assertTrue(error.getMessage().contains("已被更正"));
        verify(repository, never()).deactivate(anyLong());
    }
}
