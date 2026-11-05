package cn.copper.phase2.maintenance;

/**
 * 分页参数换算：page 从 1 开始，pageSize 默认 20、上限 100（《接口通用约定》§2）。
 */
final class MaintenancePaging {
    static final int DEFAULT_PAGE_SIZE = 20;
    static final int MAX_PAGE_SIZE = 100;

    private MaintenancePaging() {}

    static int page(Integer page) {
        if (page == null) {
            return 1;
        }
        if (page < 1) {
            throw new InvalidRequestException("page", "页码必须从 1 开始。");
        }
        return page;
    }

    static int pageSize(Integer pageSize) {
        if (pageSize == null) {
            return DEFAULT_PAGE_SIZE;
        }
        if (pageSize < 1 || pageSize > MAX_PAGE_SIZE) {
            throw new InvalidRequestException("pageSize", "每页条数必须在 1 到 " + MAX_PAGE_SIZE + " 之间。");
        }
        return pageSize;
    }

    static int offset(int page, int pageSize) {
        return (page - 1) * pageSize;
    }
}
