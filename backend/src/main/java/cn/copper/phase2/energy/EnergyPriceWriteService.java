package cn.copper.phase2.energy;

import cn.copper.phase2.common.BusinessException;
import cn.copper.phase2.common.ChangeLogModels.ChangeLogWrite;
import cn.copper.phase2.common.ChangeLogRepository;
import cn.copper.phase2.common.ErrorCode;
import cn.copper.phase2.common.IdempotencyService;
import cn.copper.phase2.energy.EnergyModels.AffectedScope;
import cn.copper.phase2.energy.EnergyModels.PriceCorrectionResult;
import cn.copper.phase2.energy.EnergyModels.PriceWriteResult;
import cn.copper.phase2.energy.EnergyRepository.PriceRow;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * S2-E05 新增生效价、S2-E06 原价更正。
 *
 * <p>两个动作都不改历史：新增只插入新行，更正插入带 {@code supersedes_price_id} 的新行
 * 并把原行退出当前有效集合。原价格、原版本、原经办人全部保留，因为已核定的核算版本
 * 引用着它们——删掉或覆盖会让历史账无法复算。</p>
 *
 * <p>{@code costRunId} 与 {@code status} 是核算服务的产物。核算模块尚未实现，
 * 这里返回 null 与 PENDING，表示"已排队待补算"，而不是伪造一个运行号。</p>
 */
@Service
public class EnergyPriceWriteService {
    private static final String OBJECT_TYPE = "ENERGY_PRICE";

    /** 可空字段也要求显式出现：传 null 表示通用范围，漏传即校验失败。 */
    private static final List<String> CREATE_REQUIRED = List.of(
            "requestId", "energyType", "effectiveDate", "unitCode",
            "unitPriceYuanPerUnit", "operatorName");

    /** 可空但必须出现：传 null 表示通用范围，漏传会被当成"没填"而不是"全部车间"。 */
    private static final List<String> CREATE_NULLABLE = List.of("workshopId");

    private static final List<String> CORRECTION_REQUIRED = List.of(
            "requestId", "expectedVersion", "changes", "reason", "operatorName");

    /** decimal(24,10)：整数部分最多 14 位。超过的话 MySQL 会报错或截断，提前拦。 */
    private static final BigDecimal MAX_PRICE = new BigDecimal("100000000000000");

    private final EnergyRepository repository;
    private final ChangeLogRepository changeLog;
    private final IdempotencyService idempotency;

    public EnergyPriceWriteService(EnergyRepository repository, ChangeLogRepository changeLog,
                                   IdempotencyService idempotency) {
        this.repository = repository;
        this.changeLog = changeLog;
        this.idempotency = idempotency;
    }

    /** E05 新增一条生效价格。 */
    public PriceWriteResult create(JsonNode body) {
        CreateRequest request = parseCreate(body);
        return idempotency.execute(request.requestId(), "ENERGY_PRICE_CREATE", request,
                PriceWriteResult.class, () -> doCreate(request));
    }

    /** E06 更正原价格：保留原行，插入更正后的新行。 */
    public PriceCorrectionResult correct(String priceId, JsonNode body) {
        long id = EnergyPriceService.parsePriceId(priceId);
        CorrectionRequest request = parseCorrection(body);
        return idempotency.execute(request.requestId(), "ENERGY_PRICE_CORRECTION:" + id, request,
                PriceCorrectionResult.class, () -> doCorrect(id, request));
    }

    private PriceWriteResult doCreate(CreateRequest request) {
        assertWorkshopExists(request.workshopId());
        assertNoDuplicate(request.energyType(), request.workshopId(), request.effectiveDate(), null);

        long priceId = repository.insert(request.energyType(), request.workshopId(),
                request.effectiveDate(), request.unitCode(), request.unitPrice(),
                null, request.reason(), request.operatorName());

        Map<String, Object> after = snapshot(request);
        changeLog.insert(new ChangeLogWrite(OBJECT_TYPE, String.valueOf(priceId), "CREATE",
                null, after, request.reason(), request.operatorName(), null, 0, request.requestId()));

        AffectedScope scope = affectedScope(request.energyType(), request.workshopId(),
                request.effectiveDate(), priceId);
        return new PriceWriteResult(String.valueOf(priceId), 0, request.energyType(),
                idOrNull(request.workshopId()),
                scopeName(request.workshopId()), request.effectiveDate(), request.unitCode(),
                EnergyRepository.plain(request.unitPrice()), null, scope);
    }

    private PriceCorrectionResult doCorrect(long priceId, CorrectionRequest request) {
        // 锁原价行：版本比对、停用与插入之间不允许其他事务插队，否则可能并存两条当前有效价。
        PriceRow current = repository.lockRow(priceId)
                .orElseThrow(() -> BusinessException.notFound("该价格不存在或已被删除。"));

        // 已停用的历史价不能再更正：对它操作只会又插一条同键行，然后被唯一键挡回来，
        // 报出"该能源已有价格"——那句提示会把人引向错误的方向。
        if (!current.current()) {
            throw new BusinessException(ErrorCode.VERSION_CONFLICT,
                    "该价格已被更正替换，不能再更正。请对当前有效的价格操作。");
        }

        if (current.version() != request.expectedVersion()) {
            throw BusinessException.versionConflict(request.expectedVersion(), current.version());
        }

        // changes 里没出现的字段保留原值；workshopId 显式传 null 才是"改为通用范围"。
        String energyType = request.energyType() != null ? request.energyType() : current.energyType();
        Long workshopId = request.workshopTouched() ? request.workshopId() : current.workshopId();
        LocalDate effectiveDate = request.effectiveDate() != null
                ? request.effectiveDate() : current.effectiveDate();
        String unitCode = request.unitCode() != null ? request.unitCode() : current.unitCode();
        BigDecimal unitPrice = request.unitPrice() != null ? request.unitPrice() : current.unitPrice();

        assertWorkshopExists(workshopId);
        // 排除自身：只改单价不改日期与范围时，新值与原值同键，这时不能判成重复。
        assertNoDuplicate(energyType, workshopId, effectiveDate, priceId);

        repository.deactivate(priceId);
        long newPriceId = repository.insert(energyType, workshopId, effectiveDate, unitCode,
                unitPrice, priceId, request.reason(), request.operatorName());

        Map<String, Object> before = snapshot(current);
        Map<String, Object> after = new LinkedHashMap<>();
        after.put("energyType", energyType);
        after.put("workshopId", idOrNull(workshopId));
        after.put("scope", scopeName(workshopId));
        after.put("effectiveDate", effectiveDate.toString());
        after.put("unitCode", unitCode);
        after.put("unitPriceYuanPerUnit", EnergyRepository.plain(unitPrice));
        after.put("supersedesPriceId", String.valueOf(priceId));

        long changeLogId = changeLog.insert(new ChangeLogWrite(OBJECT_TYPE, String.valueOf(priceId),
                "UPDATE", before, after, request.reason(), request.operatorName(),
                (int) current.version(), 0, request.requestId()));

        AffectedScope scope = affectedScope(energyType, workshopId, effectiveDate, newPriceId);
        return new PriceCorrectionResult(String.valueOf(priceId), String.valueOf(newPriceId), 0,
                String.valueOf(priceId), String.valueOf(changeLogId), scope, null, "PENDING");
    }

    /**
     * 本次写入影响的日期范围：从生效日开始，到下一条更晚的当前价之前一天为止。
     *
     * <p>没有下一条价时不设终点——这是"一直生效到下次调价"的正常状态，
     * 不是"影响范围未知"。</p>
     */
    private AffectedScope affectedScope(String energyType, Long workshopId, LocalDate from,
                                        Long priceId) {
        LocalDate end = repository.nextEffectiveDate(energyType, workshopId, from, priceId)
                .map(next -> next.minusDays(1))
                .orElse(null);
        List<String> workshopIds = workshopId == null ? List.of() : List.of(String.valueOf(workshopId));
        return new AffectedScope(from, end, workshopIds);
    }

    private void assertWorkshopExists(Long workshopId) {
        if (workshopId != null && !repository.workshopExists(workshopId)) {
            throw BusinessException.invalid("workshopId", "车间不存在，请从参考数据中重新选择。");
        }
    }

    /** 同能源、同范围、同生效日只能有一条当前有效价；冲突时指向原价格让用户去核对。 */
    private void assertNoDuplicate(String energyType, Long workshopId, LocalDate effectiveDate,
                                   Long excludePriceId) {
        if (repository.existsCurrent(energyType, workshopId, effectiveDate, excludePriceId)) {
            throw new BusinessException(ErrorCode.DUPLICATE_CODE,
                    "该能源在同一范围、同一天已有生效价格。请打开原价格核对，"
                            + "确需改动请使用「更正原价格」并填写原因。");
        }
    }

    // ---------------------------------------------------------------- 解析

    private record CreateRequest(String requestId, String energyType, Long workshopId,
                                 LocalDate effectiveDate, String unitCode, BigDecimal unitPrice,
                                 String operatorName, String reason) {}

    /**
     * 更正请求。
     *
     * @param workshopTouched 请求里是否出现了 workshopId 这个键。出现且为 null 表示
     *                        "改为通用范围"；未出现表示"范围不变"。两者都合法，必须区分。
     */
    private record CorrectionRequest(String requestId, int expectedVersion, String reason,
                                     String operatorName, String energyType, Long workshopId,
                                     boolean workshopTouched, LocalDate effectiveDate,
                                     String unitCode, BigDecimal unitPrice) {}

    private static CreateRequest parseCreate(JsonNode body) {
        assertObject(body, CREATE_REQUIRED, CREATE_NULLABLE);

        Map<String, Object> errors = new LinkedHashMap<>();
        String requestId = text(body.get("requestId"));
        String operatorName = text(body.get("operatorName"));
        String energyType = EnergyPriceService.resolveEnergyType(text(body.get("energyType")));
        Long workshopId = optionalId(body.get("workshopId"));
        LocalDate effectiveDate = date(body.get("effectiveDate"), "effectiveDate", errors);
        String unitCode = text(body.get("unitCode"));
        BigDecimal unitPrice = price(body.get("unitPriceYuanPerUnit"), errors);
        String reason = text(body.get("reason"));

        if (requestId == null) {
            errors.put("requestId", "请求编号为必填项。");
        }
        if (operatorName == null) {
            errors.put("operatorName", "经办人为必填项。");
        } else if (operatorName.length() > EnergyModels.OPERATOR_MAX) {
            errors.put("operatorName", "经办人不得超过 " + EnergyModels.OPERATOR_MAX + " 个字符。");
        }
        if (reason != null && reason.length() > EnergyModels.REASON_MAX) {
            errors.put("reason", "说明不得超过 " + EnergyModels.REASON_MAX + " 个字符。");
        }
        checkUnit(errors, energyType, unitCode);
        if (!errors.isEmpty()) {
            throw BusinessException.invalid(errors);
        }
        return new CreateRequest(requestId, energyType, workshopId, effectiveDate, unitCode,
                unitPrice, operatorName, reason);
    }

    private static CorrectionRequest parseCorrection(JsonNode body) {
        assertObject(body, CORRECTION_REQUIRED, List.of());

        Map<String, Object> errors = new LinkedHashMap<>();
        String requestId = text(body.get("requestId"));
        String operatorName = text(body.get("operatorName"));
        String reason = text(body.get("reason"));
        Long expectedVersion = number(body.get("expectedVersion"));

        if (requestId == null) {
            errors.put("requestId", "请求编号为必填项。");
        }
        if (operatorName == null) {
            errors.put("operatorName", "经办人为必填项。");
        }
        if (reason == null) {
            errors.put("reason", "更正原因不得为空，它要进入变更记录。");
        } else if (reason.length() > EnergyModels.REASON_MAX) {
            errors.put("reason", "更正原因不得超过 " + EnergyModels.REASON_MAX + " 个字符。");
        }
        if (expectedVersion == null || expectedVersion < 0) {
            errors.put("expectedVersion", "更正时必须提供非负的当前版本号。");
        }

        JsonNode changes = body.get("changes");
        if (changes == null || !changes.isObject() || changes.isEmpty()) {
            errors.put("changes", "更正内容不得为空，至少填写一项要改的值。");
            changes = null;
        }

        String energyType = null;
        Long workshopId = null;
        boolean workshopTouched = false;
        LocalDate effectiveDate = null;
        String unitCode = null;
        BigDecimal unitPrice = null;
        if (changes != null) {
            if (changes.has("energyType")) {
                energyType = EnergyPriceService.resolveEnergyType(text(changes.get("energyType")));
                if (energyType == null) {
                    errors.put("changes.energyType", "能源类型不得为空，不能改成未指定。");
                }
            }
            if (changes.has("workshopId")) {
                workshopTouched = true;
                workshopId = optionalId(changes.get("workshopId"));
            }
            if (changes.has("effectiveDate")) {
                effectiveDate = date(changes.get("effectiveDate"), "changes.effectiveDate", errors);
                if (effectiveDate == null && !errors.containsKey("changes.effectiveDate")) {
                    errors.put("changes.effectiveDate", "生效日不得为空。");
                }
            }
            if (changes.has("unitCode")) {
                unitCode = text(changes.get("unitCode"));
            }
            if (changes.has("unitPriceYuanPerUnit")) {
                unitPrice = price(changes.get("unitPriceYuanPerUnit"), errors, "changes.unitPriceYuanPerUnit");
            }
        }

        // 单位与能源必须同源：改了能源没改单位时，用改后的能源复核传入的单位。
        if (energyType != null) {
            checkUnit(errors, energyType, unitCode);
        }
        if (!errors.isEmpty()) {
            throw BusinessException.invalid(errors);
        }
        return new CorrectionRequest(requestId, expectedVersion.intValue(), reason, operatorName,
                energyType, workshopId, workshopTouched, effectiveDate, unitCode, unitPrice);
    }

    private static void assertObject(JsonNode body, List<String> required, List<String> nullable) {
        if (body == null || !body.isObject()) {
            throw BusinessException.invalid("body", "请求体必须是 JSON 对象。");
        }
        Map<String, Object> missing = new LinkedHashMap<>();
        for (String field : required) {
            if (!body.has(field)) {
                missing.put(field, "该字段为必填项。");
            }
        }
        for (String field : nullable) {
            if (!body.has(field)) {
                missing.put(field, "该字段必须出现；可空时请显式传 null。");
            }
        }
        if (!missing.isEmpty()) {
            throw BusinessException.invalid(missing);
        }
    }

    /** 单位随能源固定；这里只校验，不做自动补全——静默改掉用户填的单位比报错更危险。 */
    private static void checkUnit(Map<String, Object> errors, String energyType, String unitCode) {
        if (energyType == null) {
            return;
        }
        String expected = EnergyModels.UNIT_BY_TYPE.get(energyType);
        if (unitCode == null) {
            errors.put("unitCode", "单位为必填项，" + EnergyModels.ENERGY_TYPE_NAMES.get(energyType)
                    + "的计量单位应为 " + expected + "。");
        } else if (!expected.equals(unitCode.trim())) {
            errors.put("unitCode", EnergyModels.ENERGY_TYPE_NAMES.get(energyType)
                    + "的计量单位应为 " + expected + "，不能填 " + unitCode.trim() + "。");
        }
    }

    /**
     * 单价：不允许负数，允许明确零，最多 10 位小数。
     *
     * <p>零是合法值——部分地区或自产能源确实按零计价；负数是录入错误，
     * 放进去会让成本算出负数，比拒绝更糟。</p>
     */
    private static BigDecimal price(JsonNode node, Map<String, Object> errors) {
        return price(node, errors, "unitPriceYuanPerUnit");
    }

    private static BigDecimal price(JsonNode node, Map<String, Object> errors, String field) {
        String raw;
        if (node == null || node.isNull()) {
            errors.put(field, "单价为必填项。");
            return null;
        }
        if (node.isNumber()) {
            raw = node.asText();
        } else if (node.isTextual()) {
            raw = node.asText().trim();
        } else {
            errors.put(field, "单价必须是数字或数字字符串。");
            return null;
        }
        BigDecimal value;
        try {
            value = new BigDecimal(raw);
        } catch (NumberFormatException error) {
            errors.put(field, "单价必须是有效数字。");
            return null;
        }
        if (value.signum() < 0) {
            errors.put(field, "单价不能为负数；免费或自产请填 0。");
            return null;
        }
        if (value.stripTrailingZeros().scale() > EnergyModels.PRICE_SCALE) {
            errors.put(field, "单价最多保留 " + EnergyModels.PRICE_SCALE + " 位小数。");
            return null;
        }
        if (value.compareTo(MAX_PRICE) >= 0) {
            errors.put(field, "单价超出可存储范围。");
            return null;
        }
        // 归一到数据库列精度：录入 "0.8" 与 "0.8000000000" 是同一个价，
        // 响应里统一成 10 位小数，前端与对账都不用再猜"到底存了几位"。
        return value.setScale(EnergyModels.PRICE_SCALE);
    }

    private static LocalDate date(JsonNode node, String field, Map<String, Object> errors) {
        if (node == null || node.isNull()) {
            return null;
        }
        if (!node.isTextual()) {
            errors.put(field, "日期必须是 yyyy-MM-dd 格式的字符串。");
            return null;
        }
        String raw = node.asText().trim();
        if (raw.isEmpty()) {
            errors.put(field, "日期不得为空。");
            return null;
        }
        try {
            return LocalDate.parse(raw);
        } catch (DateTimeParseException error) {
            errors.put(field, "日期格式应为 yyyy-MM-dd，例如 2026-01-31。");
            return null;
        }
    }

    private static Long optionalId(JsonNode node) {
        if (node == null || node.isNull()) {
            return null;
        }
        if (node.isIntegralNumber()) {
            return node.asLong();
        }
        if (node.isTextual()) {
            String raw = node.asText().trim();
            if (raw.isEmpty()) {
                return null;
            }
            try {
                return Long.parseLong(raw);
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
        return null;
    }

    private static String text(JsonNode node) {
        if (node == null || !node.isTextual()) {
            return null;
        }
        String value = node.asText().trim();
        return value.isEmpty() ? null : value;
    }

    private static Long number(JsonNode node) {
        if (node == null) {
            return null;
        }
        if (node.isIntegralNumber()) {
            return node.asLong();
        }
        if (node.isTextual()) {
            try {
                return Long.parseLong(node.asText().trim());
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
        return null;
    }

    private static Map<String, Object> snapshot(CreateRequest request) {
        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("energyType", request.energyType());
        snapshot.put("workshopId", idOrNull(request.workshopId()));
        snapshot.put("scope", scopeName(request.workshopId()));
        snapshot.put("effectiveDate", request.effectiveDate().toString());
        snapshot.put("unitCode", request.unitCode());
        snapshot.put("unitPriceYuanPerUnit", EnergyRepository.plain(request.unitPrice()));
        return snapshot;
    }

    private static Map<String, Object> snapshot(PriceRow row) {
        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("energyType", row.energyType());
        snapshot.put("workshopId", idOrNull(row.workshopId()));
        snapshot.put("scope", scopeName(row.workshopId()));
        snapshot.put("effectiveDate", row.effectiveDate().toString());
        snapshot.put("unitCode", row.unitCode());
        snapshot.put("unitPriceYuanPerUnit", EnergyRepository.plain(row.unitPrice()));
        return snapshot;
    }

    private static String scopeName(Long workshopId) {
        return workshopId == null ? EnergyModels.SCOPE_GENERAL : EnergyModels.SCOPE_WORKSHOP;
    }

    private static String idOrNull(Long id) {
        return id == null ? null : String.valueOf(id);
    }
}
