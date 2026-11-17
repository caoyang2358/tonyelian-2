#!/usr/bin/env python3
"""生产记录模块演示数据种子（S2-R01–R19 联调用）。

说明：
· 计划/订单/预留的正式入口是「订单与计划」模块的 S2-P01–P06，该模块尚未开发，
  因此本脚本直接写 p2_customer_order / p2_plan / p2_plan_task / p2_task_order /
  p2_stock_reservation / p2_stock_balance / p2_stock_movement(OPENING)。
  待订单与计划模块落地后，演示数据应改由其正式接口创建，本脚本随之退役。
· 物料字典与期初库存按《开发任务与验收》首轮验收口径：铜板 20.000 吨、
  Φ8 杆 4.000 吨，D 日车间一产杆 6.000 吨（铜板预留 6.300 吨）、
  D+1 日车间二为订单 A 产丝 8.000 吨。
· 幂等：全部语句用固定业务键 INSERT ... ON DUPLICATE KEY / 先查后插，可重复执行。
"""

from __future__ import annotations

import datetime as dt
import subprocess
import sys

sys.path.insert(0, __file__.rsplit("/", 1)[0].rsplit("\\", 1)[0])
from db_env import load_config, mysql_command, preflight

ZONE = dt.timezone(dt.timedelta(hours=8))
TODAY = dt.datetime.now(ZONE).date()


def run(config: dict[str, str], sql: str) -> str:
    result = subprocess.run(mysql_command(config, sql, tuple_only=True),
                            capture_output=True, text=True, encoding="utf-8")
    if result.returncode != 0:
        raise SystemExit(f"SQL 执行失败：{result.stderr.strip()}")
    return result.stdout.strip()


def scalar(config: dict[str, str], sql: str) -> str | None:
    return run(config, sql) or None


def main() -> None:
    config = load_config()
    if not preflight(config):
        raise SystemExit(1)

    # 1. 物料：铜板(102)/Φ8 铜杆(103 之外的既有行)由 V4 建立；补一个铜丝规格。
    run(config, """
        INSERT INTO p2_material (material_code, material_name, material_type, specification)
        VALUES ('WIRE-2.6', 'Φ2.6 铜丝', 'FINISHED_WIRE', 'Φ2.6')
        ON DUPLICATE KEY UPDATE material_name = VALUES(material_name);
    """)
    plate = scalar(config, "SELECT material_id FROM p2_material WHERE material_code='COPPER-PLATE';")
    rod = scalar(config, "SELECT material_id FROM p2_material WHERE material_code='COPPER-ROD-PHI8';")
    wire = scalar(config, "SELECT material_id FROM p2_material WHERE material_code='WIRE-2.6';")
    print(f"materials: plate={plate} rod={rod} wire={wire}")

    # 2. 期初库存（OPENING 每物料唯一）：铜板 20.000，Φ8 杆 4.000。
    for material, qty in ((plate, "20.000"), (rod, "4.000")):
        exists = scalar(config,
                        f"SELECT COUNT(*) FROM p2_stock_movement WHERE material_id={material} "
                        f"AND movement_type='OPENING';")
        if exists == "0":
            run(config, f"""
                INSERT INTO p2_stock_balance (material_id, physical_quantity_ton)
                VALUES ({material}, {qty});
                INSERT INTO p2_stock_movement (material_id, movement_type, source_type, source_id,
                    request_id, business_date, quantity_delta_ton, physical_after_ton, operator_name, reason)
                VALUES ({material}, 'OPENING', 'DEV_SEED', 'OPENING-{material}', 'dev-seed-opening-{material}',
                    '{TODAY}', {qty}, {qty}, '开发演示', 'DEV_DEMO 期初库存，非现场核验');
            """)
        else:
            print(f"opening movement for {material} exists; skip")

    # 3. 客户订单 A：要求 8.000 吨 Φ2.6 铜丝，交期 D+14，生产待处理。
    order_id = scalar(config, "SELECT order_id FROM p2_customer_order WHERE order_no='SO-DEMO-0001';")
    if order_id is None:
        run(config, f"""
            INSERT INTO p2_customer_order (order_no, customer_name, wire_material_id,
                required_quantity_ton, due_date, operator_name)
            VALUES ('SO-DEMO-0001', '华南线缆（演示客户）', {wire}, '8.000',
                '{TODAY + dt.timedelta(days=14)}', '开发演示');
        """)
        order_id = scalar(config, "SELECT order_id FROM p2_customer_order WHERE order_no='SO-DEMO-0001';")
    print(f"order A: {order_id}")

    # 4. 演示计划：D 日车间一产杆 6.000（板需求 6.300），D+1 日车间二为 A 产丝 8.000。
    plan_id = scalar(config, "SELECT plan_id FROM p2_plan WHERE reason LIKE 'DEV_DEMO 生产记录演示计划%';")
    if plan_id is None:
        demand = (f'{{"demo": true, "note": "DEV_DEMO 生产记录演示计划，由种子脚本创建"}}')
        run(config, f"""
            INSERT INTO p2_plan (status, request_id, confirmed_at, operator_name, reason, demand_snapshot)
            VALUES ('ACTIVE', 'dev-seed-production-plan', NOW(3), '开发演示',
                    'DEV_DEMO 生产记录演示计划，非现场排产', CAST('{demand}' AS JSON));
        """)
        plan_id = scalar(config, "SELECT plan_id FROM p2_plan WHERE request_id='dev-seed-production-plan';")

    def ensure_task(workshop: str, material: str, date: str, seq: int, qualified: str,
                    plate_ton: str | None, rod_use_ton: str | None) -> str:
        task_id = scalar(config, f"""
            SELECT task_id FROM p2_plan_task
            WHERE plan_id={plan_id} AND workshop_id={workshop} AND production_date='{date}'
              AND sequence_no={seq};
        """)
        if task_id:
            return task_id
        run(config, f"""
            INSERT INTO p2_plan_task (plan_id, workshop_id, material_id, production_date,
                sequence_no, planned_qualified_ton, planned_plate_ton, planned_rod_use_ton)
            VALUES ({plan_id}, {workshop}, {material}, '{date}', {seq}, {qualified},
                    {plate_ton if plate_ton else 'NULL'}, {rod_use_ton if rod_use_ton else 'NULL'});
        """)
        return scalar(config, f"""
            SELECT task_id FROM p2_plan_task
            WHERE plan_id={plan_id} AND workshop_id={workshop} AND production_date='{date}'
              AND sequence_no={seq};
        """)

    task_rod = ensure_task("1", rod, str(TODAY), 1, "6.000", "6.300", None)
    task_wire = ensure_task("2", wire, str(TODAY + dt.timedelta(days=1)), 1, "8.000", None, None)
    print(f"tasks: rod={task_rod} wire={task_wire}")

    # 5. 任务-订单关联（铜丝任务产出的 WIRE_OUTPUT 份额）。
    linked = scalar(config, f"""
        SELECT COUNT(*) FROM p2_task_order WHERE task_id={task_wire} AND order_id={order_id};
    """)
    if linked == "0":
        run(config, f"""
            INSERT INTO p2_task_order (task_id, order_id, demand_key, quantity_kind, quantity_ton)
            VALUES ({task_wire}, {order_id}, 'DEMO-WIRE-A', 'WIRE_OUTPUT', '8.000');
        """)

    # 6. 预留：铜板任务预留 6.300；车间二任务的杆需求由未来供料承诺覆盖（D 日杆任务 → D+1 丝任务）。
    plate_reserved = scalar(config, f"""
        SELECT COUNT(*) FROM p2_stock_reservation WHERE task_id={task_rod}
          AND material_id={plate} AND source_type='DEV_SEED';
    """)
    if plate_reserved == "0":
        run(config, f"""
            INSERT INTO p2_stock_reservation (material_id, plan_id, order_id, task_id, demand_key,
                source_type, source_id, reserved_quantity_ton)
            VALUES ({plate}, {plan_id}, NULL, {task_rod}, NULL, 'DEV_SEED',
                'PLATE-{task_rod}', '6.300');
        """)
    commitment = scalar(config, f"""
        SELECT COUNT(*) FROM p2_supply_commitment
        WHERE source_task_id={task_rod} AND target_task_id={task_wire};
    """)
    if commitment == "0":
        run(config, f"""
            INSERT INTO p2_supply_commitment (plan_id, source_task_id, target_task_id, material_id,
                demand_key, committed_quantity_ton)
            VALUES ({plan_id}, {task_rod}, {task_wire}, {rod}, 'DEMO-ROD-FOR-WIRE', '6.000');
        """)

    print("seed complete:")
    print(f"  plan={plan_id} rod_task={task_rod}(D={TODAY}) wire_task={task_wire}(D+1)")
    print(f"  order A={order_id} plate reservation 6.300 rod commitment 6.000")


if __name__ == "__main__":
    main()
