#!/usr/bin/env python3
"""演示读数灌入脚本（S1-P09 最新值 / S1-P10 历史读数）。

只用于本机演示与联调，不属于接口契约的一部分：

    python scripts/seed_demo_readings.py                  # 写入 20 条读数
    python scripts/seed_demo_readings.py --count 40       # 改条数
    python scripts/seed_demo_readings.py --sensor-code TEMP-002 --parameter-code temperature
    python scripts/seed_demo_readings.py --clean          # 按标记清理演示读数
    python scripts/seed_demo_readings.py --dry-run        # 只打印将要执行的 SQL

为什么要写两张表：
  ods_sensor_data        原始读数，P10 历史查询直接按 sensor_code+parameter_code 读它
  ads_parameter_monitor  最新值快照，P09 **只读这张汇总表**，不读原始表
只灌 ods 的话，历史有数据、最新值仍然显示"待数据"，看起来像接口坏了。

所有演示行都带 source_system='DEMO-SEED'，--clean 按这个标记删除，
不会碰到真实采集数据。注意：一旦某测点有了 ods 记录，按 S1-P05 的规则
它的编码会被冻结、也不能再物理删除，所以造数据请挑肯定要保留的测点。
"""

import argparse
import datetime
import math
import os
import random
import subprocess
import sys

from db_env import load_config, preflight

SEED_SOURCE = 'DEMO-SEED'


def run_sql(sql: str, config: dict[str, str], tuple_only: bool = False) -> str:
    args = [config['bin'], '-h', config['host'], '-P', str(config['port']),
            '-u', config['user'], '--default-character-set=utf8mb4']
    if tuple_only:
        args += ['-N', '-B']
    args.append(config['name'])
    completed = subprocess.run(args, input=sql, text=True, capture_output=True,
                               env={**os.environ, 'MYSQL_PWD': config['password']})
    if completed.returncode != 0:
        raise SystemExit(f'执行 SQL 失败：{completed.stderr.strip()}')
    return completed.stdout


def resolve_target(config: dict[str, str], sensor_code: str, parameter_code: str) -> dict[str, str]:
    sql = f"""
        SELECT s.sensor_id, s.sensor_name, s.equipment_id, e.workshop_id,
               p.parameter_id, p.parameter_name, p.unit_code, p.decimal_places
          FROM dim_sensor s
          JOIN dim_sensor_parameter p ON p.sensor_id = s.sensor_id
          JOIN dim_equipment e ON e.equipment_id = s.equipment_id
         WHERE s.sensor_code = '{sensor_code}' AND p.parameter_code = '{parameter_code}';
    """
    output = run_sql(sql, config, tuple_only=True).strip()
    if not output:
        raise SystemExit(f'没有找到测点 {sensor_code} 下的参数 {parameter_code}，请先确认编码。')
    sensor_id, sensor_name, equipment_id, workshop_id, parameter_id, parameter_name, unit, places = \
        output.split('\t')
    return {
        'sensor_id': sensor_id, 'sensor_name': sensor_name,
        'equipment_id': equipment_id, 'workshop_id': workshop_id,
        'parameter_id': parameter_id, 'parameter_name': parameter_name,
        'unit': unit if unit != 'NULL' else '', 'places': places,
    }


def build_rows(count: int, interval_minutes: int, decimals: int) -> list[tuple[int, str, float, str]]:
    """温度类曲线：围绕 1090 缓慢波动，夹几个质量码异常，让页面上的质量列不是一片绿。"""
    now = datetime.datetime.now()
    rows = []
    for index in range(count):
        minutes_ago = (count - index) * interval_minutes
        moment = (now - datetime.timedelta(minutes=minutes_ago)).strftime('%Y-%m-%d %H:%M:%S')
        value = 1090 + 12 * math.sin(index / 3) + random.uniform(-2, 2)
        quality = 'UNCERTAIN' if index in (6, 14) else 'GOOD'
        rows.append((index, moment, round(value, decimals), quality))
    return rows


def seed(config: dict[str, str], args) -> None:
    target = resolve_target(config, args.sensor_code, args.parameter_code)
    decimals = int(target['places'])
    rows = build_rows(args.count, args.interval_minutes, decimals)

    values = ',\n'.join(
        f"('{SEED_SOURCE}', 'seed-{args.sensor_code}-{args.parameter_code}-{index}', "
        f"'{args.sensor_code}', '{args.parameter_code}', '{moment}', {value}, NULL, "
        f"'{target['unit']}', '{quality}')"
        for index, moment, value, quality in rows)
    latest_index, latest_moment, latest_value, _ = rows[-1]

    sql = f"""
DELETE FROM ods_sensor_data
 WHERE source_system = '{SEED_SOURCE}'
   AND sensor_code = '{args.sensor_code}'
   AND parameter_code = '{args.parameter_code}';

INSERT INTO ods_sensor_data
    (source_system, source_record_id, sensor_code, parameter_code, collect_time,
     value_num, value_text, source_unit, quality_code)
VALUES
{values};

INSERT INTO ads_parameter_monitor
    (sensor_id, parameter_id, workshop_id, equipment_id, unit_code,
     latest_value, latest_value_text, quality_status, limit_status,
     latest_collect_time, refresh_time)
VALUES
    ({target['sensor_id']}, {target['parameter_id']}, {target['workshop_id']},
     {target['equipment_id']}, '{target['unit']}', {latest_value}, NULL,
     'GOOD', '{args.limit_status}', '{latest_moment}', NOW())
ON DUPLICATE KEY UPDATE
    unit_code = VALUES(unit_code), latest_value = VALUES(latest_value),
    latest_value_text = NULL, quality_status = VALUES(quality_status),
    limit_status = VALUES(limit_status), latest_collect_time = VALUES(latest_collect_time),
    refresh_time = VALUES(refresh_time);
"""
    if args.dry_run:
        print(sql)
        return
    run_sql(sql, config)
    print(f'已写入 {args.count} 条读数：{args.sensor_code} / {args.parameter_code}'
          f'（{target["sensor_name"]} · {target["parameter_name"]}，单位 {target["unit"] or "无"}）')
    print(f'最新值快照 → {latest_value} @ {latest_moment}')


def clean(config: dict[str, str], args) -> None:
    target = resolve_target(config, args.sensor_code, args.parameter_code)
    sql = f"""
DELETE FROM ods_sensor_data
 WHERE source_system = '{SEED_SOURCE}'
   AND sensor_code = '{args.sensor_code}'
   AND parameter_code = '{args.parameter_code}';

DELETE FROM ads_parameter_monitor
 WHERE sensor_id = {target['sensor_id']} AND parameter_id = {target['parameter_id']};
"""
    if args.dry_run:
        print(sql)
        return
    run_sql(sql, config)
    print(f'已清理 {args.sensor_code} / {args.parameter_code} 的演示读数与最新值快照。')
    print('测点编码的冻结状态由 ods 记录决定，清理后可以再改编码或删除该测点。')


def main() -> int:
    parser = argparse.ArgumentParser(description='灌入或清理演示读数（P09/P10）')
    parser.add_argument('--sensor-code', default='TEMP-001')
    parser.add_argument('--parameter-code', default='temperature')
    parser.add_argument('--count', type=int, default=20)
    parser.add_argument('--interval-minutes', type=int, default=3)
    parser.add_argument('--limit-status', default='NORMAL',
                        choices=['NORMAL', 'WARNING', 'ALARM', 'UNKNOWN'])
    parser.add_argument('--clean', action='store_true')
    parser.add_argument('--dry-run', action='store_true')
    args = parser.parse_args()

    if args.count <= 0:
        raise SystemExit('--count 必须大于 0。')

    config = load_config()
    if not config['name'] or not config['user']:
        raise SystemExit('数据库连接信息不完整，请检查 .env 的 DB_NAME / DB_USER。')

    # 先探库再改数据：端口不对时立刻退出，不写半套演示读数。
    preflight(config)

    random.seed(20261104)
    if args.clean:
        clean(config, args)
    else:
        seed(config, args)
    return 0


if __name__ == '__main__':
    sys.exit(main())
