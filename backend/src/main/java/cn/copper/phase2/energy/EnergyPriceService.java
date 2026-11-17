package cn.copper.phase2.energy;

import cn.copper.phase2.common.BusinessException;
import cn.copper.phase2.energy.EnergyModels.EnergyPricePage;
import cn.copper.phase2.energy.EnergyModels.EnergyPriceSummary;
import cn.copper.phase2.energy.EnergyModels.PriceMatch;
import cn.copper.phase2.energy.EnergyRepository.PriceQuery;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.List;

/**
 * S2-E04 能源价格查询。
 *
 * <p>列表与"按实际日匹配"是两件事，这里都提供：列表回答"录了哪些价"，
 * 匹配回答"某天某车间该用哪个价"。把匹配并入列表查询会掩盖一个关键区别——
 * 某天查无价格不是错误，而是"缺价"，要原样返回 null 让上层显示缺项。</p>
 */
@Service
public class EnergyPriceService {
    private static final int DEFAULT_PAGE_SIZE = 20;
    private static final int MAX_PAGE_SIZE = 100;
    private static final DateTimeFormatter DATE_FORMAT = DateTimeFormatter.ISO_LOCAL_DATE;

    private final EnergyRepository repository;

    public EnergyPriceService(EnergyRepository repository) {
        this.repository = repository;
    }

    /** E04：价格分页；同时给出能源与实际日时附匹配结果。 */
    public EnergyPricePage search(String energyType, String workshopId, String scope,
                                  String effectiveFrom, String effectiveTo, String actualDate,
                                  Boolean includeHistory, Integer page, Integer pageSize) {
        int resolvedPage = resolvePage(page);
        int resolvedSize = resolvePageSize(pageSize);
        String resolvedType = resolveEnergyType(energyType);
        String resolvedScope = resolveScope(scope);
        Long resolvedWorkshop = resolveWorkshopId(workshopId);
        if (EnergyModels.SCOPE_GENERAL.equals(resolvedScope) && resolvedWorkshop != null) {
            throw BusinessException.invalid("workshopId",
                    "范围选通用时不能再指定车间；要查某车间的专用价，请把范围改为车间专用。");
        }

        LocalDate from = parseDate(effectiveFrom, "effectiveFrom");
        LocalDate to = parseDate(effectiveTo, "effectiveTo");
        if (from != null && to != null && from.isAfter(to)) {
            throw BusinessException.invalid("effectiveTo", "生效日区间的结束日不得早于开始日。");
        }

        PriceQuery query = new PriceQuery(resolvedType, resolvedWorkshop, resolvedScope,
                from, to, Boolean.TRUE.equals(includeHistory), resolvedPage, resolvedSize);
        List<EnergyPriceSummary> items = repository.search(query);
        long total = repository.count(query);

        PriceMatch matched = matchFor(energyType, resolvedType, resolvedWorkshop, actualDate);
        return new EnergyPricePage(items, resolvedPage, resolvedSize, total, matched);
    }

    /**
     * 按实际用能日匹配价格。
     *
     * <p>只给能源与实际日、不给车间时，按通用范围取价——这是"该能源的默认价是多少"的合法问法。
     * 给了车间才走"专用优先、通用回退"的完整规则。</p>
     */
    private PriceMatch matchFor(String rawActualDate, String energyType, Long workshopId,
                                String actualDate) {
        LocalDate date = parseDate(actualDate, "actualDate");
        if (date == null) {
            return null;
        }
        if (energyType == null) {
            throw BusinessException.invalid("energyType",
                    "按实际日匹配价格时必须指定能源类型。");
        }
        return repository.matchPrice(energyType, workshopId, date).orElse(null);
    }

    static String resolveEnergyType(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String value = raw.trim().toUpperCase();
        if (!EnergyModels.ENERGY_TYPES.contains(value)) {
            throw BusinessException.invalid("energyType",
                    "能源类型只能是 " + String.join("、", EnergyModels.ENERGY_TYPES) + " 之一。");
        }
        return value;
    }

    static String resolveScope(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String value = raw.trim().toUpperCase();
        if (!EnergyModels.SCOPE_GENERAL.equals(value) && !EnergyModels.SCOPE_WORKSHOP.equals(value)) {
            throw BusinessException.invalid("scope",
                    "范围只能是 " + EnergyModels.SCOPE_GENERAL + " 或 " + EnergyModels.SCOPE_WORKSHOP + "。");
        }
        return value;
    }

    static Long resolveWorkshopId(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        return parsePositiveId(raw, "车间");
    }

    static long parsePriceId(String raw) {
        return parsePositiveId(raw, "价格");
    }

    static long parsePositiveId(String raw, String label) {
        try {
            long id = Long.parseLong(raw == null ? "" : raw.trim());
            if (id <= 0) {
                throw new NumberFormatException("非正整数");
            }
            return id;
        } catch (NumberFormatException error) {
            throw BusinessException.invalid("id", label + " ID 必须是正整数字符串。");
        }
    }

    /** 日期只接受 yyyy-MM-dd；空串按"未填写"处理，不把空字符串当成今天。 */
    static LocalDate parseDate(String raw, String field) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return LocalDate.parse(raw.trim(), DATE_FORMAT);
        } catch (DateTimeParseException error) {
            throw BusinessException.invalid(field, "日期格式应为 yyyy-MM-dd，例如 2026-01-31。");
        }
    }

    static int resolvePage(Integer page) {
        if (page == null) {
            return 1;
        }
        if (page < 1) {
            throw BusinessException.invalid("page", "页码必须从 1 开始。");
        }
        return page;
    }

    static int resolvePageSize(Integer pageSize) {
        if (pageSize == null) {
            return DEFAULT_PAGE_SIZE;
        }
        if (pageSize < 1 || pageSize > MAX_PAGE_SIZE) {
            throw BusinessException.invalid("pageSize", "每页条数必须在 1 到 " + MAX_PAGE_SIZE + " 之间。");
        }
        return pageSize;
    }
}
