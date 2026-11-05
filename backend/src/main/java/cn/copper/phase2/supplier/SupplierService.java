package cn.copper.phase2.supplier;

import cn.copper.phase2.common.BusinessException;
import cn.copper.phase2.common.PageResult;
import cn.copper.phase2.supplier.SupplierModels.SupplierDetail;
import cn.copper.phase2.supplier.SupplierModels.SupplierSummary;
import cn.copper.phase2.supplier.SupplierRepository.SupplierQuery;
import cn.copper.phase2.supplier.SupplierRepository.SupplierRow;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * S1-S01 / S1-S02 供应商只读查询。
 *
 * <p>详情里的关联设备直接读关联表当前事实，不缓存、不推断；
 * 没有任何关联时返回空数组，页面据此显示"尚未关联设备"，而不是用假数据填充。</p>
 */
@Service
public class SupplierService {
    private static final int DEFAULT_PAGE_SIZE = 20;
    private static final int MAX_PAGE_SIZE = 100;

    private final SupplierRepository repository;

    public SupplierService(SupplierRepository repository) {
        this.repository = repository;
    }

    /** S1-S01 分页查询；keyword 与 equipmentId 同时给出时取交集。 */
    public PageResult<SupplierSummary> search(String keyword, String equipmentId,
                                              Integer page, Integer pageSize) {
        int resolvedPage = resolvePage(page);
        int resolvedSize = resolvePageSize(pageSize);
        Long resolvedEquipmentId = equipmentId == null || equipmentId.isBlank()
                ? null
                : parseEquipmentId(equipmentId);
        SupplierQuery query = new SupplierQuery(keyword, resolvedEquipmentId, resolvedPage, resolvedSize);
        return PageResult.of(repository.search(query), resolvedPage, resolvedSize,
                repository.count(query));
    }

    /** S1-S02 详情；不存在返回 404，不返回空壳对象。 */
    public SupplierDetail detail(String supplierId) {
        return detail(parseSupplierId(supplierId));
    }

    SupplierDetail detail(long supplierId) {
        SupplierRow row = repository.findRow(supplierId)
                .orElseThrow(() -> BusinessException.notFound("供应商不存在或已被删除。"));
        return toDetail(row, repository.equipmentIds(supplierId));
    }

    static SupplierDetail toDetail(SupplierRow row, List<Long> equipmentIds) {
        List<String> ids = equipmentIds.stream().map(String::valueOf).toList();
        return new SupplierDetail(String.valueOf(row.supplierId()), row.name(), row.contactName(),
                row.phone(), row.remark(), ids, row.version(), row.updatedAt());
    }

    static long parseSupplierId(String raw) {
        return parsePositiveId(raw, "供应商");
    }

    static long parseEquipmentId(String raw) {
        return parsePositiveId(raw, "设备");
    }

    private static long parsePositiveId(String raw, String label) {
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
