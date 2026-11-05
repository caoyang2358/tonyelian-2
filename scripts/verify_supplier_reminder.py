#!/usr/bin/env python3
"""S1-S01—S04 供应商 / S1-T02—T03 提醒设置 验收脚本。

对运行中的后端做一遍端到端验证：
  供应商  列表、详情、关键字与设备过滤、新建（去重）、整体更新、空数组解绑、
          版本冲突、设备不存在、幂等重放
  提醒    读取默认值、更新两个天数、版本冲突、还原原值

只用标准库：
    python scripts/verify_supplier_reminder.py
    python scripts/verify_supplier_reminder.py --base-url http://127.0.0.1:18280

说明：契约没有定义供应商删除接口，因此脚本创建的"验收临时供应商"会在结束时解绑设备并保留记录，
名称带 ZZ-VERIFY 前缀，排在列表末尾便于人工清理。退出码 0 表示全部通过。
"""

import argparse
import json
import sys
import time
import urllib.error
import urllib.request

TAG = "ZZ-VERIFY"

passes = []
failures = []

# 显式禁用代理：本机联调不应被系统 HTTP_PROXY 拦走。
OPENER = urllib.request.build_opener(urllib.request.ProxyHandler({}))


def call(method, url, body=None):
    data = None if body is None else json.dumps(body, ensure_ascii=False).encode("utf-8")
    request = urllib.request.Request(url, data=data, method=method)
    if data is not None:
        request.add_header("Content-Type", "application/json")
    try:
        with OPENER.open(request, timeout=10) as response:
            return response.status, json.loads(response.read().decode("utf-8"))
    except urllib.error.HTTPError as error:
        raw = error.read().decode("utf-8")
        try:
            return error.code, json.loads(raw)
        except json.JSONDecodeError:
            return error.code, {"raw": raw}


def check(name, ok, detail=""):
    if ok:
        passes.append(name)
        print(f"  PASS  {name}")
    else:
        failures.append((name, detail))
        print(f"  FAIL  {name}")
        if detail:
            print(f"        {detail}")


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--base-url", default="http://127.0.0.1:18280")
    args = parser.parse_args()
    root = args.base_url.rstrip("/") + "/api/v1"
    suppliers_url = root + "/suppliers"
    reminder_url = root + "/reminder-settings"
    stamp = str(int(time.time()))
    operator = "验收脚本"

    print(f"目标服务：{root}\n")

    # ---------- 0. 服务与前置数据 ----------
    print("[0] 服务连通与前置设备")
    status, payload = call("GET", root + "/equipment?page=1&pageSize=5")
    if status != 200:
        print(f"  服务不可用（HTTP {status}），请先启动后端。")
        return 1
    equipment_ids = [item["id"] for item in payload["data"]["items"]][:2]
    check("设备列表可读且至少有一台设备", len(equipment_ids) >= 1, f"实际 {equipment_ids}")
    if not equipment_ids:
        return 1
    first = str(equipment_ids[0])
    second = str(equipment_ids[1]) if len(equipment_ids) > 1 else None

    status, payload = call("GET", suppliers_url + "?page=1&pageSize=1")
    base_total = (payload.get("data") or {}).get("total") if status == 200 else None
    check("S01 列表可读", status == 200 and base_total is not None, f"实际 HTTP {status}")

    # ---------- 1. 详情 404 ----------
    print("\n[1] 不存在的供应商")
    status, payload = call("GET", suppliers_url + "/999999")
    check("S02 返回 404", status == 404, f"实际 HTTP {status}")
    check("错误码为 NOT_FOUND", payload.get("code") == "NOT_FOUND", f"实际 {payload.get('code')}")

    # ---------- 2. 新建 ----------
    print("\n[2] S03 新建并关联设备（含去重）")
    name = f"{TAG}-{stamp}"
    create_body = {
        "requestId": f"{TAG}-CREATE-{stamp}",
        "operatorName": operator,
        "name": name,
        "contactName": "验收联系人",
        "phone": "000-00000000",
        "remark": "自动化验收创建",
        "equipmentIds": [first] + ([second, first] if second else [first]),
    }
    status, payload = call("POST", suppliers_url, create_body)
    if status != 200:
        print(f"  新建失败（HTTP {status}）：{payload}")
        return 1
    created = payload["data"]
    supplier_id = created["supplierId"]
    expected_ids = [first] if not second else [first, second]
    print(f"  新建 supplierId={supplier_id} equipmentIds={created['equipmentIds']}")
    check("新建返回 version=1", created["version"] == 1, f"实际 {created['version']}")
    check("设备 ID 已去重且保留顺序", created["equipmentIds"] == expected_ids,
          f"期望 {expected_ids}，实际 {created['equipmentIds']}")

    # ---------- 3. 查询与过滤 ----------
    print("\n[3] S01 查询与过滤")
    status, payload = call("GET", suppliers_url + f"?equipmentId={first}")
    hits = [row["supplierId"] for row in (payload.get("data") or {}).get("items") or []]
    check("按设备过滤能查到新供应商", supplier_id in hits, f"实际 {hits}")

    status, payload = call("GET", suppliers_url + f"?keyword={stamp}")
    hits = [row["supplierId"] for row in (payload.get("data") or {}).get("items") or []]
    check("关键字过滤能查到新供应商", supplier_id in hits, f"实际 {hits}")

    status, payload = call("GET", suppliers_url + "?page=1&pageSize=1")
    after_total = (payload.get("data") or {}).get("total")
    check("列表 total 增加 1", after_total == base_total + 1, f"期望 {base_total + 1}，实际 {after_total}")

    # ---------- 4. 更新与冲突 ----------
    print("\n[4] S04 整体更新")
    update_body = {
        "requestId": f"{TAG}-UPDATE-{stamp}",
        "operatorName": operator,
        "expectedVersion": 1,
        "name": name + "（更名）",
        "contactName": "验收联系人2",
        "phone": None,
        "remark": None,
        "equipmentIds": [first] if second else [],
    }
    status, payload = call("PUT", f"{suppliers_url}/{supplier_id}", update_body)
    updated = payload.get("data") or {}
    check("更新返回 200", status == 200, f"实际 HTTP {status}：{payload}")
    check("版本递增到 2", updated.get("version") == 2, f"实际 {updated.get('version')}")
    check("关联被整体替换", updated.get("equipmentIds") == ([first] if second else []),
          f"实际 {updated.get('equipmentIds')}")
    check("可空字段可清空", updated.get("phone") is None and updated.get("remark") is None,
          f"实际 phone={updated.get('phone')} remark={updated.get('remark')}")

    status, payload = call("PUT", f"{suppliers_url}/{supplier_id}", {**update_body, "requestId": f"{TAG}-STALE-{stamp}"})
    check("过期版本返回 409", status == 409, f"实际 HTTP {status}")
    check("错误码为 VERSION_CONFLICT", payload.get("code") == "VERSION_CONFLICT", f"实际 {payload.get('code')}")

    status, payload = call("PUT", f"{suppliers_url}/{supplier_id}", {
        **update_body, "requestId": f"{TAG}-BAD-EQ-{stamp}", "expectedVersion": 2, "equipmentIds": ["999999"],
    })
    fields = (payload.get("data") or {}).get("fields") or {}
    check("关联不存在设备返回 400", status == 400, f"实际 HTTP {status}")
    check("错误定位到 equipmentIds", "equipmentIds" in fields, f"实际 {fields}")

    # ---------- 5. 空数组解绑 ----------
    print("\n[5] 空数组表示解除全部关联")
    status, payload = call("PUT", f"{suppliers_url}/{supplier_id}", {
        **update_body, "requestId": f"{TAG}-UNBIND-{stamp}", "expectedVersion": 2, "equipmentIds": [],
    })
    unbound = payload.get("data") or {}
    check("解绑返回 200", status == 200, f"实际 HTTP {status}")
    check("equipmentIds 变为空数组", unbound.get("equipmentIds") == [], f"实际 {unbound.get('equipmentIds')}")

    status, payload = call("GET", suppliers_url + f"?equipmentId={first}")
    hits = [row["supplierId"] for row in (payload.get("data") or {}).get("items") or []]
    check("解绑后按该设备已查不到", supplier_id not in hits, f"实际 {hits}")

    # ---------- 6. 幂等重放 ----------
    print("\n[6] 同一 requestId 重放")
    status, payload = call("POST", suppliers_url, create_body)
    replay = payload.get("data") or {}
    check("重放返回 200 而非重复创建", status == 200, f"实际 HTTP {status}：{payload}")
    check("重放返回首次结果（同一 supplierId）", str(replay.get("supplierId")) == str(supplier_id),
          f"期望 {supplier_id}，实际 {replay.get('supplierId')}")

    status, payload = call("GET", suppliers_url + "?page=1&pageSize=1")
    check("重放后 total 未再增加",
          (payload.get("data") or {}).get("total") == after_total,
          f"期望 {after_total}，实际 {(payload.get('data') or {}).get('total')}")

    # ---------- 7. 提醒设置 ----------
    print("\n[7] S1-T02 / S1-T03 提醒设置")
    status, payload = call("GET", reminder_url)
    current = payload.get("data") or {}
    check("T02 读取成功", status == 200, f"实际 HTTP {status}")
    check("返回两个提前量与版本", {"warrantyLeadDays", "maintenanceLeadDays", "version"} <= set(current),
          f"实际 {current}")
    warranty = current.get("warrantyLeadDays")
    maintenance = current.get("maintenanceLeadDays")
    version = current.get("version")

    status, payload = call("PUT", reminder_url, {
        "requestId": f"{TAG}-RM-{stamp}", "operatorName": operator, "expectedVersion": version,
        "warrantyLeadDays": 15, "maintenanceLeadDays": 45,
    })
    changed = payload.get("data") or {}
    check("T03 更新成功", status == 200, f"实际 HTTP {status}：{payload}")
    check("两个天数按提交保存",
          changed.get("warrantyLeadDays") == 15 and changed.get("maintenanceLeadDays") == 45,
          f"实际 {changed}")
    check("版本递增", changed.get("version") == version + 1, f"实际 {changed.get('version')}")

    status, payload = call("PUT", reminder_url, {
        "requestId": f"{TAG}-RM-STALE-{stamp}", "operatorName": operator,
        "expectedVersion": version, "warrantyLeadDays": 1, "maintenanceLeadDays": 1,
    })
    check("过期版本返回 409", status == 409, f"实际 HTTP {status}")

    status, payload = call("PUT", reminder_url, {
        "requestId": f"{TAG}-RM-NEG-{stamp}", "operatorName": operator,
        "expectedVersion": version + 1, "warrantyLeadDays": -1, "maintenanceLeadDays": 30,
    })
    check("负数天数返回 400", status == 400, f"实际 HTTP {status}")

    # 还原原值，避免验收脚本改变开发库的提醒配置
    status, _ = call("PUT", reminder_url, {
        "requestId": f"{TAG}-RM-RESTORE-{stamp}", "operatorName": operator,
        "expectedVersion": version + 1,
        "warrantyLeadDays": warranty, "maintenanceLeadDays": maintenance,
    })
    check("已还原提醒设置原值", status == 200, f"实际 HTTP {status}")

    # ---------- 汇总 ----------
    print("\n" + "=" * 54)
    print(f"通过 {len(passes)} 项，失败 {len(failures)} 项")
    if failures:
        print("\n失败明细：")
        for name, detail in failures:
            print(f"  - {name}" + (f"：{detail}" if detail else ""))
        return 1
    print("供应商与提醒设置接口全部通过。")
    print(f"提示：供应商 {supplier_id}（{name}）为验收记录，可在数据库 p2_supplier 中清理。")
    return 0


if __name__ == "__main__":
    sys.exit(main())
