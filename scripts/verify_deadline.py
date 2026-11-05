#!/usr/bin/env python3
"""S1-T01 期限查询 · 验收脚本。

对运行中的后端做端到端验证，覆盖：
  1. 三个期限来源各自出行，字段齐全，无档案的设备也出行（状态 MISSING_DATA）
  2. 质保到期边界：到期当天剩余 0 且进入 DUE_SOON、已过为 EXPIRED、远期 NORMAL、清空为 MISSING_DATA
  3. 寿命按"投用日 + 预计年限"推算，且不足一年按固定 365 天评估（不被质保/维保提前量替换）
  4. 四个筛选条件与组合筛选
  5. 分页不重不漏，total 与全量一致
  6. 非法枚举、非法 ID、越界分页返回 400 并指向字段

只用标准库：
    python scripts/verify_deadline.py
    python scripts/verify_deadline.py --base-url http://127.0.0.1:18280

退出码 0 表示全部通过。脚本会新建一台临时设备、反复改它的日期做边界验证，
最后删除该设备；既有设备与合同一律只读，不改动。
"""

import argparse
import datetime
import json
import sys
import time
import urllib.error
import urllib.request

TAG = "DL-VERIFY"
# 业务日期按东八区取。中国不实行夏令时，固定偏移即可，
# 不依赖 zoneinfo/tzdata（Windows 上的 Python 默认没有时区库）。
BUSINESS_ZONE = datetime.timezone(datetime.timedelta(hours=8))

passes = []
failures = []

REQUIRED_FIELDS = {
    "equipmentId", "equipmentCode", "equipmentName", "workshopId", "workshopName",
    "responsiblePerson", "reminderType", "contractId", "contractNumber",
    "purchasedOn", "commissionedOn", "expectedLifeYears", "dueDate",
    "remainingDays", "expiryState", "missingFields",
}


def call(method, url, body=None):
    """返回 (status, payload)。HTTP 4xx/5xx 也读出 body，不抛异常。"""
    data = None if body is None else json.dumps(body, ensure_ascii=False).encode("utf-8")
    request = urllib.request.Request(url, data=data, method=method)
    if data is not None:
        request.add_header("Content-Type", "application/json")
    try:
        with urllib.request.urlopen(request, timeout=10) as response:
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


def today():
    return datetime.datetime.now(BUSINESS_ZONE).date()


def profile_body(code, request_id, operator, warranty=None, commissioned=None, life_years=None):
    return {
        "requestId": request_id,
        "operatorName": operator,
        "code": code,
        "name": "期限验收临时设备",
        "workshopId": "1",
        "responsiblePerson": "验收脚本",
        "enabled": True,
        "profile": {
            "typeCode": "OTHER",
            "model": "VERIFY-MODEL",
            "processId": None,
            "installationLocation": "验收用临时位置",
            "manufacturer": "验收脚本",
            "factorySerialNo": None,
            "purchasedOn": None,
            "commissionedOn": commissioned,
            "expectedLifeYears": life_years,
            "warrantyExpiresOn": warranty,
            "managementStatus": "IN_USE",
            "technicalParameters": [],
        },
    }


def update_body(code, request_id, operator, version, warranty=None,
                commissioned=None, life_years=None):
    body = profile_body(code, request_id, operator, warranty, commissioned, life_years)
    body["expectedVersion"] = version
    body["reason"] = "验收脚本调整期限日期"
    return body


def rows_for(base, equipment_id):
    """取某台设备的全部期限行，按类型归位。"""
    status, payload = call("GET", f"{base}?equipmentId={equipment_id}&pageSize=50")
    items = (payload.get("data") or {}).get("items") or []
    return status, {row["reminderType"]: row for row in items}, items


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--base-url", default="http://127.0.0.1:18280")
    args = parser.parse_args()
    root = args.base_url.rstrip("/") + "/api/v1"
    base = root + "/equipment-deadlines"
    equipment_base = root + "/equipment"
    stamp = str(int(time.time()))
    operator = "验收脚本"

    print("== 0. 服务连通与提醒设置 ==")
    status, payload = call("GET", f"{base}?page=1&pageSize=1")
    if status != 200:
        print(f"  期限接口不可用（HTTP {status}）：{payload}")
        return 1
    status, setting_payload = call("GET", f"{root}/reminder-settings")
    setting = (setting_payload.get("data") or {})
    warranty_lead = setting.get("warrantyLeadDays")
    maintenance_lead = setting.get("maintenanceLeadDays")
    check("读取提醒设置（T02）", status == 200 and warranty_lead is not None,
          f"HTTP {status} {setting_payload}")

    print("== 1. 新建一台无关联临时设备 ==")
    temp_code = f"{TAG}-{stamp}"
    status, payload = call("POST", equipment_base,
                           profile_body(temp_code, f"dl-create-{stamp}", operator))
    if status not in (200, 201):
        print(f"  新建失败（HTTP {status}）：{payload}")
        return 1
    equipment_id = payload["data"]["id"]
    version = payload["data"]["version"]
    print(f"  临时设备 id={equipment_id} code={temp_code} version={version}")

    status, by_type, items = rows_for(base, equipment_id)
    check("新设备出现在期限列表且恰好三行（质保/寿命/维保）",
          status == 200 and len(items) == 3, f"HTTP {status} 实际 {len(items)} 行")
    check("三行覆盖 WARRANTY / EXPECTED_LIFE / MAINTENANCE",
          set(by_type) == {"WARRANTY", "EXPECTED_LIFE", "MAINTENANCE"}, f"实际 {sorted(by_type)}")

    missing_fields_ok = all(REQUIRED_FIELDS <= set(row) for row in items)
    check("每行字段齐全", missing_fields_ok, f"实际字段 {sorted(set(items[0]) if items else [])}")

    if items:
        check("无档案时三行都是 MISSING_DATA 且日期为空",
              all(row["expiryState"] == "MISSING_DATA" and row["dueDate"] is None
                  and row["remainingDays"] is None for row in items),
              f"实际 {[(r['reminderType'], r['expiryState'], r['dueDate']) for r in items]}")
        check("缺资料原因指向具体字段，不是笼统占位",
              by_type["WARRANTY"]["missingFields"] == ["warrantyExpiresOn"]
              and by_type["EXPECTED_LIFE"]["missingFields"] == ["commissionedOn", "expectedLifeYears"]
              and by_type["MAINTENANCE"]["missingFields"] == ["contractId"],
              f"实际 {[(r['reminderType'], r['missingFields']) for r in items]}")

    print("== 2. 质保到期边界（到期当天 / 已超期 / 远期 / 清空） ==")
    today_date = today()

    def set_warranty(target, tag, commissioned=None, life_years=None):
        nonlocal version
        value = None if target is None else target.isoformat()
        commissioned_value = None if commissioned is None else commissioned.isoformat()
        status, payload = call("PUT", f"{equipment_base}/{equipment_id}",
                               update_body(temp_code, f"dl-{tag}-{stamp}", operator, version,
                                           value, commissioned_value, life_years))
        if status == 200:
            version = payload["data"]["version"]
        return status, payload

    status, _ = set_warranty(today_date, "due-today")
    _, by_type, _ = rows_for(base, equipment_id)
    row = by_type.get("WARRANTY", {})
    check("到期当天剩余天数为 0",
          status == 200 and row.get("remainingDays") == 0, f"HTTP {status} 实际 {row.get('remainingDays')}")
    check("到期当天即进入 DUE_SOON（不等到过期才提醒）",
          row.get("expiryState") == "DUE_SOON", f"实际 {row.get('expiryState')}")

    status, _ = set_warranty(today_date - datetime.timedelta(days=5), "expired")
    _, by_type, _ = rows_for(base, equipment_id)
    row = by_type.get("WARRANTY", {})
    check("日期已过为 EXPIRED 且剩余天数为负",
          row.get("expiryState") == "EXPIRED" and row.get("remainingDays") == -5,
          f"实际 {row.get('expiryState')} / {row.get('remainingDays')}")

    far = today_date + datetime.timedelta(days=(warranty_lead or 30) + 60)
    status, _ = set_warranty(far, "normal")
    _, by_type, _ = rows_for(base, equipment_id)
    row = by_type.get("WARRANTY", {})
    check("超过提前量的远期日期为 NORMAL",
          row.get("expiryState") == "NORMAL", f"实际 {row.get('expiryState')}")

    soon = today_date + datetime.timedelta(days=max(0, (warranty_lead or 30) - 1))
    status, _ = set_warranty(soon, "soon")
    _, by_type, _ = rows_for(base, equipment_id)
    row = by_type.get("WARRANTY", {})
    check("剩余天数小于提前量为 DUE_SOON",
          row.get("expiryState") == "DUE_SOON", f"实际 {row.get('expiryState')}")

    status, _ = set_warranty(None, "cleared")
    _, by_type, _ = rows_for(base, equipment_id)
    row = by_type.get("WARRANTY", {})
    check("清空质保日期后回到 MISSING_DATA，不保留旧剩余天数",
          row.get("expiryState") == "MISSING_DATA" and row.get("remainingDays") is None,
          f"实际 {row.get('expiryState')} / {row.get('remainingDays')}")

    print("== 3. 寿命：投用日 + 预计年限，不足一年按固定 365 天评估 ==")
    # 让到期日恰好在 300 天后：投用日 = 今天 - (12 年 - 300 天)
    due_target = today_date + datetime.timedelta(days=300)
    commissioned = datetime.date(due_target.year - 12, due_target.month, 1)
    # 用 1 号对齐避免跨月日不存在的情况；再按实际推算的到期日断言
    status, _ = set_warranty(None, "life-soon", commissioned, 12)
    _, by_type, _ = rows_for(base, equipment_id)
    row = by_type.get("EXPECTED_LIFE", {})
    expected_due = datetime.date(commissioned.year + 12, commissioned.month, commissioned.day)
    check("寿命到期日 = 投用日 + 预计年限",
          row.get("dueDate") == expected_due.isoformat(),
          f"预期 {expected_due} 实际 {row.get('dueDate')}")
    remaining = (expected_due - today_date).days
    check("寿命剩余天数按真实日历计算",
          row.get("remainingDays") == remaining, f"预期 {remaining} 实际 {row.get('remainingDays')}")
    check("寿命不足一年提示评估（阈值 365，不是质保/维保提前量）",
          row.get("expiryState") == ("DUE_SOON" if remaining <= 365 else "NORMAL"),
          f"实际 {row.get('expiryState')}，剩余 {remaining} 天，提前量 {warranty_lead}/{maintenance_lead}")

    status, _ = set_warranty(None, "life-missing", None, 12)
    _, by_type, _ = rows_for(base, equipment_id)
    row = by_type.get("EXPECTED_LIFE", {})
    check("只填年限不填投用日仍为 MISSING_DATA 并指出缺哪个字段",
          row.get("expiryState") == "MISSING_DATA" and row.get("missingFields") == ["commissionedOn"],
          f"实际 {row.get('expiryState')} / {row.get('missingFields')}")

    print("== 4. 筛选 ==")
    # 前面几步把质保日期清空了，先造一条 EXPIRED 样本，否则超期筛选没有数据可验
    set_warranty(today_date - datetime.timedelta(days=5), "expired-sample")
    status, payload = call("GET", f"{base}?reminderType=warranty&pageSize=100")
    items_w = (payload.get("data") or {}).get("items") or []
    check("reminderType 大小写不敏感且只返回该类型",
          status == 200 and items_w and all(r["reminderType"] == "WARRANTY" for r in items_w),
          f"HTTP {status} 实际 {sorted({r['reminderType'] for r in items_w})}")

    status, payload = call("GET", f"{base}?expiryState=expired&pageSize=100")
    items_e = (payload.get("data") or {}).get("items") or []
    check("expiryState 过滤只返回 EXPIRED 且剩余天数为负",
          status == 200 and items_e and all(
              r["expiryState"] == "EXPIRED" and r["remainingDays"] < 0 for r in items_e),
          f"HTTP {status} 实际 {[(r['expiryState'], r['remainingDays']) for r in items_e][:5]}")

    status, payload = call("GET", f"{base}?workshopId=1&pageSize=100")
    items_ws = (payload.get("data") or {}).get("items") or []
    check("workshopId 过滤只返回该车间",
          status == 200 and items_ws and all(r["workshopId"] == "1" for r in items_ws),
          f"HTTP {status} 实际 {sorted({r['workshopId'] for r in items_ws})}")

    status, payload = call(
        "GET", f"{base}?equipmentId={equipment_id}&reminderType=EXPECTED_LIFE&expiryState=MISSING_DATA")
    combo = (payload.get("data") or {}).get("items") or []
    check("组合筛选取交集",
          status == 200 and len(combo) == 1
          and combo[0]["equipmentId"] == str(equipment_id)
          and combo[0]["reminderType"] == "EXPECTED_LIFE"
          and combo[0]["expiryState"] == "MISSING_DATA",
          f"HTTP {status} 实际 {combo}")

    print("== 5. 分页不重不漏 ==")
    status, payload = call("GET", f"{base}?pageSize=100")
    all_items = (payload.get("data") or {}).get("items") or []
    total = (payload.get("data") or {}).get("total")
    check("total 与一次取全的行数一致", total == len(all_items), f"total={total} 实际 {len(all_items)}")

    first = call("GET", f"{base}?page=1&pageSize=3")[1]["data"]["items"]
    second = call("GET", f"{base}?page=2&pageSize=3")[1]["data"]["items"]
    def key(row):
        return (row["equipmentId"], row["reminderType"], row["contractId"])
    check("前两页条数正确且无重复",
          len(first) == 3 and len(second) == 3
          and not ({key(r) for r in first} & {key(r) for r in second}),
          f"第一页 {[key(r) for r in first]} 第二页 {[key(r) for r in second]}")

    status, payload = call("GET", f"{base}?page=1&pageSize=3&equipmentId={equipment_id}")
    check("筛选后的 total 只统计命中行",
          status == 200 and (payload["data"]["total"]) == 3,
          f"实际 total={(payload.get('data') or {}).get('total')}")

    print("== 6. 参数校验 ==")
    for query, field, label in [
        ("reminderType=FOO", "reminderType", "非法期限类型"),
        ("expiryState=SOON", "expiryState", "非法计算状态"),
        ("equipmentId=abc", "equipmentId", "非数字设备 ID"),
        ("pageSize=0", "pageSize", "每页条数下界"),
        ("pageSize=101", "pageSize", "每页条数上界"),
        ("page=0", "page", "页码下界"),
    ]:
        status, payload = call("GET", f"{base}?{query}")
        fields = str((payload.get("data") or {}).get("fields") or payload.get("data") or "")
        check(f"{label} → 400 且定位到 {field}",
              status == 400 and payload.get("code") == "VALIDATION_ERROR" and field in fields,
              f"HTTP {status} code={payload.get('code')} data={payload.get('data')}")

    print("== 7. 清理临时设备 ==")
    current_version = (call("GET", f"{equipment_base}/{equipment_id}")[1]["data"])["version"]
    status, payload = call("DELETE", f"{equipment_base}/{equipment_id}", {
        "requestId": f"dl-delete-{stamp}",
        "operatorName": operator,
        "expectedVersion": current_version,
        "reason": "验收脚本清理临时设备",
        "confirmed": True,
    })
    check("临时设备已删除", status == 200 and (payload.get("data") or {}).get("deleted") is True,
          f"HTTP {status} {payload}")
    status, payload = call("GET", f"{base}?equipmentId={equipment_id}")
    check("删除后期限列表不再有该设备",
          status == 200 and (payload["data"]["total"]) == 0,
          f"实际 total={(payload.get('data') or {}).get('total')}")

    print()
    print(f"通过 {len(passes)} 项，失败 {len(failures)} 项")
    if failures:
        print("失败明细：")
        for name, detail in failures:
            print(f"  - {name}：{detail}")
        return 1
    print("S1-T01 期限查询契约全部通过。")
    return 0


if __name__ == "__main__":
    sys.exit(main())
