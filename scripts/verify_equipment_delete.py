#!/usr/bin/env python3
"""S1-E05 误建删除 · 关联检查验收脚本。

对运行中的后端做一遍端到端验证，覆盖：
  1. 有关联的设备：详情 allowed=false、blockers 有条数、DELETE 返回 409
  2. 无关联的设备：详情 allowed=true，DELETE 成功
  3. 二次确认未勾选 → 400，且 errors 指向 confirmed
  4. 版本号过期 → 409，不产生删除
  5. 删除成功后再查详情 → 404
  6. 删除留痕保留，E06 仍能按历史身份查到 DELETE 记录
  7. 同一 requestId 重放 → 幂等返回首次结果，不重复删除

只用标准库，无需安装依赖：
    python scripts/verify_equipment_delete.py
    python scripts/verify_equipment_delete.py --base-url http://127.0.0.1:18280

退出码 0 表示全部通过；1 表示有失败项。脚本会新建一台临时设备并在最后删除，
不改动既有设备（第 1 步只会对"已确认有关联"的设备发删除请求，预期被拒绝）。
"""

import argparse
import json
import sys
import time
import urllib.error
import urllib.request

TAG = "DEL-VERIFY"

passes = []
failures = []


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


def profile_body(code, request_id, operator):
    return {
        "requestId": request_id,
        "operatorName": operator,
        "code": code,
        "name": "删除验收临时设备",
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
            "commissionedOn": None,
            "expectedLifeYears": None,
            "warrantyExpiresOn": None,
            "managementStatus": "IN_USE",
            "technicalParameters": [],
        },
    }


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--base-url", default="http://127.0.0.1:18280")
    args = parser.parse_args()
    base = args.base_url.rstrip("/") + "/api/v1/equipment"
    stamp = str(int(time.time()))
    operator = "验收脚本"

    print(f"目标服务：{base}\n")

    # ---------- 0. 服务可达 ----------
    print("[0] 服务连通性")
    status, payload = call("GET", f"{base}?page=1&pageSize=50")
    if status != 200:
        print(f"  服务不可用（HTTP {status}），请先启动后端：.\\scripts\\Start-Dev.ps1")
        return 1
    items = payload["data"]["items"]
    check("GET /equipment 返回列表", isinstance(items, list), f"实际：{payload}")

    # ---------- 1. 阻塞路径：有关联的设备 ----------
    print("\n[1] 有关联的设备（只读检查 + 一次预期被拒的删除）")
    blocked = None
    for item in items:
        code, detail = call("GET", f"{base}/{item['id']}")
        if code != 200:
            continue
        deletion = detail["data"]["deletion"]
        if not deletion["allowed"] and deletion["blockers"]:
            blocked = (item, detail["data"])
            break

    if blocked is None:
        print("  SKIP  当前库中没有带关联的设备，无法验证阻塞路径（可先给某台设备建测点）")
    else:
        item, detail = blocked
        deletion = detail["deletion"]
        print(f"  选中设备：{item['id']} {item['code']} {item['name']}")
        print(f"  预期 blockers：{[(b['type'], b['count']) for b in deletion['blockers']]}")
        check("详情 deletion.allowed = false", deletion["allowed"] is False)
        check(
            "blockers 带具体条数与可读说明",
            all(b.get("count", 0) > 0 and b.get("message") for b in deletion["blockers"]),
        )
        code, payload = call(
            "DELETE",
            f"{base}/{item['id']}",
            {
                "requestId": f"{TAG}-BLOCKED-{stamp}",
                "operatorName": operator,
                "expectedVersion": detail["version"],
                "reason": "验证关联检查会拒绝删除",
                "confirmed": True,
            },
        )
        body = payload.get("data") or {}
        check("DELETE 返回 409", code == 409, f"实际 HTTP {code}")
        check(
            "错误码为 RELATED_DATA_EXISTS",
            payload.get("code") == "RELATED_DATA_EXISTS",
            f"实际 code={payload.get('code')}",
        )
        check(
            "409 响应回传 blockers 供页面展示",
            isinstance(body.get("blockers"), list) and len(body["blockers"]) > 0,
            f"实际 data={body}",
        )
        still_there, _ = call("GET", f"{base}/{item['id']}")
        check("设备未被删除（详情仍 200）", still_there == 200, f"实际 HTTP {still_there}")

    # ---------- 2. 新建一台无关联设备 ----------
    print("\n[2] 新建无关联设备")
    temp_code = f"{TAG}-{stamp}"
    status, payload = call("POST", base, profile_body(temp_code, f"{TAG}-CREATE-{stamp}", operator))
    if status != 200:
        print(f"  新建失败（HTTP {status}）：{payload}")
        return 1
    created = payload["data"]
    equipment_id = created["id"]
    print(f"  新设备 id={equipment_id} code={temp_code} version={created['version']}")
    check("新设备 version = 1", created["version"] == 1, f"实际 {created['version']}")
    check("新设备 deletion.allowed = true", created["deletion"]["allowed"] is True)
    check("新设备 blockers 为空", created["deletion"]["blockers"] == [])

    status, payload = call("GET", f"{base}/{equipment_id}")
    detail = payload["data"]
    check("详情二次确认 allowed = true", status == 200 and detail["deletion"]["allowed"] is True)

    # ---------- 3. 二次确认缺失 → 400 ----------
    print("\n[3] 未二次确认即删除")
    status, payload = call(
        "DELETE",
        f"{base}/{equipment_id}",
        {
            "requestId": f"{TAG}-NOCONFIRM-{stamp}",
            "operatorName": operator,
            "expectedVersion": detail["version"],
            "reason": "验证二次确认",
            "confirmed": False,
        },
    )
    errors = (payload.get("data") or {}).get("fields") or {}
    check("返回 400", status == 400, f"实际 HTTP {status}")
    check("错误定位到 confirmed 字段", "confirmed" in errors, f"实际 data={payload.get('data')}")
    still_there, _ = call("GET", f"{base}/{equipment_id}")
    check("设备仍存在", still_there == 200, f"实际 HTTP {still_there}")

    # ---------- 4. 版本过期 → 409 ----------
    print("\n[4] 版本号过期")
    status, payload = call(
        "DELETE",
        f"{base}/{equipment_id}",
        {
            "requestId": f"{TAG}-STALE-{stamp}",
            "operatorName": operator,
            "expectedVersion": 99,
            "reason": "验证乐观锁",
            "confirmed": True,
        },
    )
    check("返回 409", status == 409, f"实际 HTTP {status}")
    check(
        "错误码为 VERSION_CONFLICT",
        payload.get("code") == "VERSION_CONFLICT",
        f"实际 code={payload.get('code')}",
    )
    still_there, _ = call("GET", f"{base}/{equipment_id}")
    check("设备仍存在", still_there == 200, f"实际 HTTP {still_there}")

    # ---------- 5. 正常删除 ----------
    print("\n[5] 正常删除")
    delete_request_id = f"{TAG}-OK-{stamp}"
    delete_body = {
        "requestId": delete_request_id,
        "operatorName": operator,
        "expectedVersion": detail["version"],
        "reason": "验收脚本创建的临时设备，删除清理",
        "confirmed": True,
    }
    status, payload = call("DELETE", f"{base}/{equipment_id}", delete_body)
    check("返回 200", status == 200, f"实际 HTTP {status}：{payload}")
    result = payload.get("data") or {}
    check("deleted = true", result.get("deleted") is True, f"实际 {result}")
    check("返回的 id 与被删设备一致", str(result.get("id")) == str(equipment_id))

    gone, _ = call("GET", f"{base}/{equipment_id}")
    check("删除后详情返回 404", gone == 404, f"实际 HTTP {gone}")

    # ---------- 6. 留痕保留 ----------
    print("\n[6] 删除留痕（E06）")
    status, payload = call("GET", f"{base}/{equipment_id}/changes?page=1&pageSize=50")
    changes = (payload.get("data") or {}).get("items") or []
    operations = [row.get("operation") for row in changes]
    deletes = [row for row in changes if row.get("operation") == "DELETE"]
    check("删除后仍能查询变更记录", status == 200, f"实际 HTTP {status}")
    check("留痕中含 DELETE 记录", bool(deletes), f"实际 operations={operations}")
    check(
        "DELETE 留痕记录了原因与操作人",
        bool(deletes) and deletes[0].get("reason") and deletes[0].get("operatorName"),
        f"实际 {deletes[:1]}",
    )
    check(
        "CREATE 历史也保留",
        "CREATE" in operations,
        f"实际 operations={operations}",
    )

    # ---------- 7. 幂等重放 ----------
    print("\n[7] 同一 requestId 重放")
    status, payload = call("DELETE", f"{base}/{equipment_id}", delete_body)
    replay = payload.get("data") or {}
    check("重放返回 200 而非 404", status == 200, f"实际 HTTP {status}：{payload}")
    check(
        "重放返回首次结果 deleted = true",
        replay.get("deleted") is True,
        f"实际 {replay}",
    )

    # ---------- 汇总 ----------
    print("\n" + "=" * 52)
    print(f"通过 {len(passes)} 项，失败 {len(failures)} 项")
    if failures:
        print("\n失败明细：")
        for name, detail in failures:
            print(f"  - {name}" + (f"：{detail}" if detail else ""))
        return 1
    print("S1-E05 关联检查与删除契约全部通过。")
    return 0


if __name__ == "__main__":
    sys.exit(main())
