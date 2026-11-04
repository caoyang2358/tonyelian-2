#!/usr/bin/env python3
"""S1-P03/P04 · 测点写入接口验收脚本。

对运行中的后端做端到端验证，覆盖：
  1. 新建测点：返回详情结构、首存版本 1、配置状态 PENDING、采集来源为空
  2. 新建后详情与列表都能读到，字段与请求一致
  3. 幂等：同 requestId 同内容重放返回同一测点且不新增记录；同 requestId 改内容返回 409
  4. 校验：重复编码 409、编码格式 400、缺字段 400、非生产设备 400
  5. 更新：资料与启停用可改、版本推进、版本不符 409、无历史时编码可改
  6. 只读性：不存在的测点 404、非法 ID 400、未开放 DELETE

只用标准库：
    python scripts/verify_sensor_write.py
    python scripts/verify_sensor_write.py --base-url http://127.0.0.1:18280

脚本会真实写入一条测点（编码以 AUTOTEST- 开头），结束时打印清理 SQL。
退出码 0 表示全部通过。
"""

import argparse
import json
import sys
import time
import urllib.error
import urllib.request
import uuid

BASE = "http://127.0.0.1:18280/api/v1"

passes = []
failures = []

DETAIL_FIELDS = {
    "sensorId", "sensorCode", "name", "equipmentId", "equipmentCode", "equipmentName",
    "workshopId", "workshopName", "location", "sensorType", "dataSource", "purpose",
    "enabled", "parameterCount", "hasHistory", "monitoringStatus", "configurationState",
    "version", "parameters",
}


def call(method, path, body=None):
    """返回 (status, payload)。HTTP 4xx/5xx 也读出 body，不抛异常。"""
    data = None if body is None else json.dumps(body).encode("utf-8")
    request = urllib.request.Request(BASE + path, data=data, method=method)
    if data is not None:
        request.add_header("Content-Type", "application/json")
    try:
        with urllib.request.urlopen(request, timeout=15) as response:
            return response.status, json.loads(response.read().decode("utf-8"))
    except urllib.error.HTTPError as error:
        raw = error.read().decode("utf-8")
        try:
            return error.code, json.loads(raw)
        except json.JSONDecodeError:
            return error.code, {"raw": raw}


def check(name, condition, detail=""):
    if condition:
        passes.append(name)
        print("  [PASS] %s" % name)
    else:
        failures.append((name, detail))
        print("  [FAIL] %s  ->  %s" % (name, detail))


def fields_of(payload):
    data = (payload or {}).get("data")
    return set(data.keys()) if isinstance(data, dict) else set()


def field_errors(payload):
    """取出 400 响应里的字段级错误，便于断言"错在哪个字段"。"""
    data = (payload or {}).get("data") or {}
    return set((data.get("fields") or {}).keys())


def detail(sensor_id):
    status, payload = call("GET", "/sensors/%s" % sensor_id)
    return status, payload, (payload.get("data") or {})


def main():
    global BASE
    parser = argparse.ArgumentParser()
    parser.add_argument("--base-url", default=BASE)
    args = parser.parse_args()
    BASE = args.base_url.rstrip("/")

    stamp = int(time.time())
    code = "AUTOTEST-%d" % stamp
    request_id = str(uuid.uuid4())
    created_id = None

    print("== 1. 新建测点（P03） ==")
    body = {
        "requestId": request_id,
        "operatorName": "验收脚本",
        "sensorCode": code,
        "name": "验收用测点",
        "equipmentId": "1",
        "sensorType": "温度",
        "location": "验收位置 A",
        "purpose": "验收用途",
        "enabled": True,
    }
    status, payload = call("POST", "/sensors", body)
    check("新建返回 200", status == 200, "%s %s" % (status, payload))
    data = payload.get("data") or {}
    created_id = data.get("sensorId")
    check("返回完整详情结构", DETAIL_FIELDS <= fields_of(payload), fields_of(payload))
    check("返回新测点 ID", bool(created_id), data)
    check("编码回显一致", data.get("sensorCode") == code, data.get("sensorCode"))
    check("首存版本为 1", data.get("version") == 1, data.get("version"))
    check("配置状态为待配置", data.get("configurationState") == "PENDING", data.get("configurationState"))
    check("采集来源保持为空", data.get("dataSource") is None, data.get("dataSource"))
    check("监测状态为暂无数据", data.get("monitoringStatus") == "UNKNOWN", data.get("monitoringStatus"))
    check("扩展资料已写入", data.get("location") == "验收位置 A" and data.get("purpose") == "验收用途", data)
    check("新测点没有参数", data.get("parameters") == [] and data.get("parameterCount") == 0, data.get("parameterCount"))

    print("== 2. 详情与列表可读 ==")
    status, payload, one = detail(created_id)
    check("详情返回 200", status == 200, status)
    check("详情与创建结果一致", one.get("sensorCode") == code and one.get("version") == 1, one)
    status, payload = call("GET", "/sensors?equipmentId=1&pageSize=100")
    items = (payload.get("data") or {}).get("items") or []
    check("列表能按设备检索到新测点", any(item.get("sensorId") == created_id for item in items),
          [item.get("sensorCode") for item in items][:8])

    print("== 3. 幂等 ==")
    total_before = (call("GET", "/sensors?pageSize=1")[1].get("data") or {}).get("total")
    status, replay = call("POST", "/sensors", body)
    check("同内容重放返回 200", status == 200, status)
    check("重放返回同一测点 ID", (replay.get("data") or {}).get("sensorId") == created_id,
          (replay.get("data") or {}).get("sensorId"))
    total_after = (call("GET", "/sensors?pageSize=1")[1].get("data") or {}).get("total")
    check("重放没有新增记录", total_before == total_after, "%s -> %s" % (total_before, total_after))

    changed = dict(body)
    changed["name"] = "换了名字的验收测点"
    status, conflict = call("POST", "/sensors", changed)
    check("同 requestId 换内容返回 409", status == 409, status)
    check("冲突码为 IDEMPOTENCY_CONFLICT", (conflict.get("code") or "").endswith("IDEMPOTENCY_CONFLICT")
          or "IDEMPOTENCY" in json.dumps(conflict), conflict.get("code"))

    print("== 4. 新建校验 ==")
    status, dup = call("POST", "/sensors", dict(body, requestId=str(uuid.uuid4())))
    check("重复编码返回 409", status == 409, status)
    check("重复编码错误码正确", (dup.get("code") or "").endswith("DUPLICATE_CODE"), dup.get("code"))

    bad_code = dict(body, requestId=str(uuid.uuid4()), sensorCode="含 空格 的编码")
    status, invalid = call("POST", "/sensors", bad_code)
    check("非法编码返回 400", status == 400, status)
    check("错误指向 sensorCode", "sensorCode" in field_errors(invalid), field_errors(invalid))

    missing = {"requestId": str(uuid.uuid4()), "operatorName": "验收脚本",
               "sensorCode": "AUTOTEST-MISSING", "name": "缺字段", "equipmentId": "1", "enabled": True}
    status, invalid = call("POST", "/sensors", missing)
    check("缺少可空字段返回 400", status == 400, status)
    check("缺字段被逐条列出",
          {"sensorType", "location", "purpose"} <= field_errors(invalid), field_errors(invalid))

    bad_equipment = dict(body, requestId=str(uuid.uuid4()), sensorCode="AUTOTEST-EQ-%d" % stamp,
                         equipmentId="99999")
    status, invalid = call("POST", "/sensors", bad_equipment)
    check("设备不存在返回 400", status == 400, status)
    check("错误指向 equipmentId", "equipmentId" in field_errors(invalid), field_errors(invalid))

    print("== 5. 更新（P04） ==")
    status, payload, current = detail(created_id)
    version = current.get("version")
    update = {
        "requestId": str(uuid.uuid4()),
        "operatorName": "验收脚本",
        "expectedVersion": version,
        "sensorCode": code,
        "name": "验收用测点（已改名）",
        "equipmentId": "1",
        "sensorType": "温度",
        "location": "验收位置 B",
        "purpose": "验收用途（复核）",
        "enabled": True,
        "reason": "验收脚本复核归属",
    }
    status, payload = call("PUT", "/sensors/%s" % created_id, update)
    check("更新返回 200", status == 200, "%s %s" % (status, payload))
    updated = payload.get("data") or {}
    check("名称已更新", updated.get("name") == "验收用测点（已改名）", updated.get("name"))
    check("位置已更新", updated.get("location") == "验收位置 B", updated.get("location"))
    check("版本推进到 2", updated.get("version") == 2, updated.get("version"))

    stale = dict(update, requestId=str(uuid.uuid4()), expectedVersion=version)
    status, conflict = call("PUT", "/sensors/%s" % created_id, stale)
    check("版本落后返回 409", status == 409, status)
    conflict_data = conflict.get("data") or {}
    check("冲突响应给出两侧版本",
          conflict_data.get("expectedVersion") == version
          and conflict_data.get("currentVersion") == 2, conflict_data)

    new_code = "%s-B" % code
    rename = dict(update, requestId=str(uuid.uuid4()), expectedVersion=2, sensorCode=new_code)
    status, payload = call("PUT", "/sensors/%s" % created_id, rename)
    check("无采集记录时编码可改", status == 200 and (payload.get("data") or {}).get("sensorCode") == new_code,
          "%s %s" % (status, payload.get("code")))

    disable = dict(rename, requestId=str(uuid.uuid4()), expectedVersion=3, enabled=False,
                   reason="验收脚本停用")
    status, payload = call("PUT", "/sensors/%s" % created_id, disable)
    disabled = payload.get("data") or {}
    check("停用返回 200", status == 200, status)
    check("停用后 enabled=false", disabled.get("enabled") is False, disabled.get("enabled"))
    check("停用后监测状态为已停用", disabled.get("monitoringStatus") == "DISABLED",
          disabled.get("monitoringStatus"))
    status, payload, after = detail(created_id)
    check("停用不清除扩展资料", after.get("location") == "验收位置 B" and after.get("purpose") == "验收用途（复核）", after)

    print("== 6. 边界与只读性 ==")
    # 同一个 requestId 打到另一个目标：内容一字不差，但目标不同，必须报冲突而不是回放旧结果。
    other_target = dict(update, requestId=update["requestId"])
    status, conflict = call("PUT", "/sensors/1", other_target)
    check("同一 requestId 换目标返回 409", status == 409, status)

    probe = dict(update, requestId=str(uuid.uuid4()))
    status, payload = call("PUT", "/sensors/99999", probe)
    check("更新不存在的测点返回 404", status == 404, status)
    status, payload = call("PUT", "/sensors/abc", probe)
    check("更新非法 ID 返回 400", status == 400, status)
    status, payload = call("DELETE", "/sensors/%s" % created_id)
    check("未开放 DELETE（405/404）", status in (404, 405), status)

    print()
    print("通过 %d 项，失败 %d 项" % (len(passes), len(failures)))
    if failures:
        for name, detail_text in failures:
            print("  - %s：%s" % (name, detail_text))
    print()
    print("清理 SQL（脚本不自动删除，避免误删手工数据）：")
    print("  DELETE FROM p2_sensor_profile WHERE sensor_id = %s;" % created_id)
    print("  DELETE FROM dim_sensor WHERE sensor_id = %s;" % created_id)
    print("  DELETE FROM p2_change_log WHERE object_type = 'SENSOR' AND object_id = '%s';" % created_id)
    print("  DELETE FROM p2_request_result WHERE operation LIKE 'SENSOR_%';")
    return 0 if not failures else 1


if __name__ == "__main__":
    sys.exit(main())
