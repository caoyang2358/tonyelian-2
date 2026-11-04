package cn.copper.phase2.deadline;

import cn.copper.phase2.common.BusinessException;
import cn.copper.phase2.common.PageResult;
import cn.copper.phase2.deadline.DeadlineModels.DeadlineItem;
import cn.copper.phase2.deadline.DeadlineRepository.DeadlineQuery;
import cn.copper.phase2.reminder.ReminderSettingModels.ReminderSetting;
import cn.copper.phase2.reminder.ReminderSettingService;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Locale;

/**
 * S1-T01 期限列表与提醒查询。
 *
 * <p>纯读接口：剩余天数与状态都在查询时现算，不落库。质保、维保的"即将到期"阈值
 * 取 {@code p2_reminder_setting} 里的当前配置（T02/T03 维护），寿命固定按一年评估。</p>
 *
 * <p>业务日期按厂区时区（{@code Asia/Shanghai}）取，不跟随 JVM 或数据库的服务器时区。
 * 否则部署在东八区以外的机器上，"到期当天剩余 0 天"会整体偏移一天。</p>
 */
@Service
public class DeadlineService {
    static final ZoneId BUSINESS_ZONE = ZoneId.of("Asia/Shanghai");

    private static final int DEFAULT_PAGE_SIZE = 20;
    private static final int MAX_PAGE_SIZE = 100;

    private final DeadlineRepository repository;
    private final ReminderSettingService reminderSetting;

    public DeadlineService(DeadlineRepository repository, ReminderSettingService reminderSetting) {
        this.repository = repository;
        this.reminderSetting = reminderSetting;
    }

    /** 分页查询；四个筛选条件同时给出时取交集。 */
    public PageResult<DeadlineItem> search(String equipmentId, String workshopId,
                                           String reminderType, String expiryState,
                                           Integer page, Integer pageSize) {
        int resolvedPage = resolvePage(page);
        int resolvedSize = resolvePageSize(pageSize);
        DeadlineQuery query = new DeadlineQuery(
                optionalId(equipmentId, "equipmentId", "设备"),
                optionalId(workshopId, "workshopId", "车间"),
                normalize(reminderType, DeadlineModels.REMINDER_TYPES, "reminderType"),
                normalize(expiryState, DeadlineModels.EXPIRY_STATES, "expiryState"),
                resolvedPage, resolvedSize);

        ReminderSetting setting = reminderSetting.current();
        LocalDate today = LocalDate.now(BUSINESS_ZONE);
        List<DeadlineItem> items = repository.search(query, setting.warrantyLeadDays(),
                setting.maintenanceLeadDays(), today);
        long total = repository.count(query, setting.warrantyLeadDays(),
                setting.maintenanceLeadDays(), today);
        return PageResult.of(items, resolvedPage, resolvedSize, total);
    }

    /** 空串按"不筛选"处理；大小写不敏感，但必须落在契约枚举内，拼错时直接 400 而不是静默返回空页。 */
    static String normalize(String raw, List<String> allowed, String field) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String value = raw.trim().toUpperCase(Locale.ROOT);
        if (!allowed.contains(value)) {
            throw BusinessException.invalid(field, "取值范围为 " + String.join("、", allowed) + "。");
        }
        return value;
    }

    static Long optionalId(String raw, String field, String label) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            long id = Long.parseLong(raw.trim());
            if (id <= 0) {
                throw new NumberFormatException("非正整数");
            }
            return id;
        } catch (NumberFormatException error) {
            throw BusinessException.invalid(field, label + " ID 必须是正整数字符串。");
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
