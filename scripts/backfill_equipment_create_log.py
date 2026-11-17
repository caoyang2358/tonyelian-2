#!/usr/bin/env python3
"""S1-E06 变更记录 · 为存量设备补录「新增」建档记录。

背景：一期 8 台设备是 seed 脚本直接建表来的，没有走 S1-E03 新增接口，
所以 p2_change_log 里没有对应的 CREATE 记录，变更时间线从第一次更正开始，
看不出设备是什么时候、以什么状态建档。新建设备走接口时本来就会写 CREATE
（EquipmentWriteService.doCreate），这里只补历史，不改代码。

补录规则（只补不改，不动任何既有记录）：

  · recorded_at  取 dim_equipment.created_time，排在所有更正之前；
  · after_value  取该设备第一条更正的前置快照 —— 那正是建档时的档案，
                 不重新拼 JSON，避免和既有审计记录对不上；
  · version_before 为 NULL（此前没有版本），version_after 为 0，
                 与该批数据第一条更正的 v0 → v1 自然衔接；
  · operator_name 记「系统初始化」而不写人名：这一步不是谁做的更正，
                 是导入动作本身，写人名等于伪造操作人；
  · request_id 统一 backfill-create-<id>，既能识别来源，也方便 --revert 撤回。

用法：

    python scripts/backfill_equipment_create_log.py            # 只预览，不写库
    python scripts/backfill_equipment_create_log.py --apply    # 写入
    python scripts/backfill_equipment_create_log.py --revert    # 按 request_id 前缀撤回

退出码 0 表示成功；1 表示有设备缺快照、预览被拒或写入后校验不通过。
"""

from __future__ import annotations

import argparse
import subprocess
import sys

from db_env import load_config, mysql_command, preflight

REQUEST_PREFIX = "backfill-create-"
REASON = "设备档案由一期导入建立，补录建档记录"
OPERATOR = "系统初始化"

# 待补设备：当前没有 CREATE，但有可用的首条更正前置快照。
CANDIDATES_SQL = """
SELECT e.equipment_id, e.equipment_code, e.created_time,
       (SELECT l.before_value FROM p2_change_log l
         WHERE l.object_type = 'EQUIPMENT' AND l.object_id = CAST(e.equipment_id AS CHAR)
           AND l.action = 'UPDATE'
         ORDER BY l.recorded_at, l.change_log_id LIMIT 1) AS snapshot,
       (SELECT MIN(l.recorded_at) FROM p2_change_log l
         WHERE l.object_type = 'EQUIPMENT' AND l.object_id = CAST(e.equipment_id AS CHAR)
           AND l.action = 'UPDATE') AS first_update_at
  FROM dim_equipment e
 WHERE NOT EXISTS (SELECT 1 FROM p2_change_log c
                    WHERE c.object_type = 'EQUIPMENT' AND c.object_id = CAST(e.equipment_id AS CHAR)
                      AND c.action = 'CREATE')
 ORDER BY e.equipment_id
"""

# 写入：快照用窗口函数取第一条更正的前置值，一条语句覆盖全部设备。
APPLY_SQL = """
INSERT INTO p2_change_log
    (object_type, object_id, action, before_value, after_value, reason,
     operator_name, recorded_at, version_before, version_after, request_id)
SELECT 'EQUIPMENT', CAST(e.equipment_id AS CHAR), 'CREATE', NULL, f.before_value,
       '{reason}', '{operator}', e.created_time, NULL, 0,
       CONCAT('{prefix}', e.equipment_id)
  FROM dim_equipment e
  JOIN (SELECT object_id, before_value,
               ROW_NUMBER() OVER (PARTITION BY object_id
                                  ORDER BY recorded_at, change_log_id) AS rn
          FROM p2_change_log
         WHERE object_type = 'EQUIPMENT' AND action = 'UPDATE') f
    ON f.object_id = CAST(e.equipment_id AS CHAR) AND f.rn = 1
 WHERE NOT EXISTS (SELECT 1 FROM p2_change_log c
                    WHERE c.object_type = 'EQUIPMENT' AND c.object_id = CAST(e.equipment_id AS CHAR)
                      AND c.action = 'CREATE')
   AND f.before_value IS NOT NULL
"""

REVERT_SQL = """
DELETE FROM p2_change_log
 WHERE object_type = 'EQUIPMENT' AND action = 'CREATE' AND request_id LIKE '{prefix}%'
"""

# 校验：只查本次补录过的设备——走接口新建的设备本来就是 v0 → v1，不该被拿来一起判。
VERIFY_SQL = """
SELECT e.equipment_id,
       (SELECT COUNT(*) FROM p2_change_log l
         WHERE l.object_type = 'EQUIPMENT' AND l.object_id = CAST(e.equipment_id AS CHAR)) AS total,
       (SELECT l.action FROM p2_change_log l
         WHERE l.object_type = 'EQUIPMENT' AND l.object_id = CAST(e.equipment_id AS CHAR)
         ORDER BY l.recorded_at, l.change_log_id LIMIT 1) AS earliest_action,
       (SELECT l.version_after FROM p2_change_log l
         WHERE l.object_type = 'EQUIPMENT' AND l.object_id = CAST(e.equipment_id AS CHAR)
           AND l.action = 'CREATE' ORDER BY l.change_log_id DESC LIMIT 1) AS create_va,
       (SELECT l.version_before FROM p2_change_log l
         WHERE l.object_type = 'EQUIPMENT' AND l.object_id = CAST(e.equipment_id AS CHAR)
           AND l.action = 'UPDATE'
         ORDER BY l.recorded_at, l.change_log_id LIMIT 1) AS first_update_vb
  FROM dim_equipment e
 WHERE EXISTS (SELECT 1 FROM p2_change_log b
                WHERE b.object_type = 'EQUIPMENT' AND b.object_id = CAST(e.equipment_id AS CHAR)
                  AND b.request_id LIKE '{prefix}%')
 ORDER BY e.equipment_id
"""


def run(config: dict[str, str], statement: str, tuple_only: bool = True) -> list[list[str]]:
    command = mysql_command(config, statement, tuple_only=tuple_only)
    result = subprocess.run(command, capture_output=True, text=True, encoding="utf-8")
    if result.returncode != 0:
        raise SystemExit("SQL 执行失败：\n" + (result.stderr or "").strip())
    rows = []
    for line in (result.stdout or "").splitlines():
        if not line.strip():
            continue
        rows.append(line.split("\t"))
    return rows


def show_plan(rows: list[list[str]]) -> int:
    if not rows:
        print("没有需要补录的设备：每台现役设备都已有 CREATE 记录。")
        return 0
    print("将补录 %d 台设备的建档记录（recorded_at 取建档时间，排在所有更正之前）：\n" % len(rows))
    print("  %-4s %-14s %-24s %-9s" % ("ID", "设备编码", "建档时间", "快照来源"))
    missing = 0
    for equipment_id, code, created_time, snapshot, first_update_at in rows:
        if not snapshot or snapshot == "NULL":
            missing += 1
            print("  %-4s %-14s %-24s %s" % (equipment_id, code, created_time, "缺失（首条更正无前置快照）"))
            continue
        print("  %-4s %-14s %-24s %s" % (equipment_id, code, created_time,
                                         "首条更正 " + str(first_update_at)[:19]))
    print("\n操作人记为「%s」，原因记为「%s」。" % (OPERATOR, REASON))
    if missing:
        print("其中 %d 台缺少首条更正的前置快照，写入时会被跳过（不凭空构造档案）。" % missing)
        return 1
    return 0


def show_verify(rows: list[list[str]]) -> int:
    print("\n补录后时间线校验：\n")
    print("  %-4s %-7s %-8s %-10s %-13s %s" % ("ID", "记录数", "最早一条", "建档版本", "首条更正 from", "结论"))
    failed = 0
    for equipment_id, total, earliest, create_va, first_vb in rows:
        ok = earliest == "CREATE" and create_va == "0" and first_vb == "0"
        if not ok:
            failed += 1
        print("  %-4s %-7s %-8s %-10s %-13s %s"
              % (equipment_id, total, earliest, create_va, first_vb, "正常" if ok else "需人工确认"))
    return 1 if failed else 0


def main() -> int:
    parser = argparse.ArgumentParser(description="为存量设备补录「新增」建档记录")
    parser.add_argument("--apply", action="store_true", help="真正写库；不带此参数只预览")
    parser.add_argument("--revert", action="store_true", help="删除本次补录的记录（按 request_id 前缀）")
    args = parser.parse_args()

    config = load_config()
    preflight(config)

    if args.revert:
        before = run(config, "SELECT COUNT(*) FROM p2_change_log WHERE request_id LIKE '%s%%'"
                     % REQUEST_PREFIX)
        run(config, REVERT_SQL.format(prefix=REQUEST_PREFIX), tuple_only=False)
        print("已撤回补录记录 %s 条。" % (before[0][0] if before else "0"))
        return 0

    rows = run(config, CANDIDATES_SQL)
    status = show_plan(rows)
    if not args.apply:
        print("\n这是预览，未写库。确认无误后加 --apply 执行。")
        return status

    if not rows:
        return 0

    run(config, APPLY_SQL.format(reason=REASON, operator=OPERATOR, prefix=REQUEST_PREFIX),
        tuple_only=False)
    written = run(config, "SELECT COUNT(*) FROM p2_change_log WHERE request_id LIKE '%s%%'"
                  % REQUEST_PREFIX)
    print("\n已写入 %s 条建档记录。" % (written[0][0] if written else "0"))

    return show_verify(run(config, VERIFY_SQL.format(prefix=REQUEST_PREFIX)))


if __name__ == "__main__":
    sys.exit(main())
