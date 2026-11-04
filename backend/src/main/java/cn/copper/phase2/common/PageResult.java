package cn.copper.phase2.common;

import java.util.List;

public record PageResult<T>(List<T> items, int page, int pageSize, long total) {
    public static <T> PageResult<T> of(List<T> items, int page, int pageSize, long total) {
        return new PageResult<>(List.copyOf(items), page, pageSize, total);
    }
}
