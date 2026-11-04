-- E06 变更记录契约需要的两条追溯维度：
--   version_before / version_after 记录这次改动前后的聚合版本；
--   request_id 把同一次提交产生的多条留痕归为一批，并可反查 p2_request_result 的幂等结果。
-- 三列均可空，对运维、知识库等模块已有的写入完全向后兼容；已执行的 V2 不做任何修改。
ALTER TABLE p2_change_log
    ADD COLUMN version_before INT NULL,
    ADD COLUMN version_after INT NULL,
    ADD COLUMN request_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NULL,
    ADD KEY idx_p2_change_request (request_id);
