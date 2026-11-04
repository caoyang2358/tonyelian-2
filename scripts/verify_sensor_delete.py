#!/usr/bin/env python3
"""S1-P05 · 删除测点接口验收脚本。

对运行中的后端做端到端验证，覆盖：
  1. 详情里的删除可行性：无关联时 allowed=true、blockers 为空
  2. 请求校验：缺 confirmed / 缺 reason / 版本号过期 / 非法 ID / 不存在的测点
  3. 正常删除：返回 {sensorId,deleted:true}，测点与参数一并消失，列表不再返回
  4. 幂等：同 requestId 同内容重放仍返回成功；同 requestId 改内容返回 409
  5. 采集记录阻塞：ods_sensor_data 有行时 409 COLLECTION_DATA，清理后可以删
  6. 规则历史阻塞：参数规则改过（多于 1 版）时 409 PARAMETER_HISTORY

1—4 只用标准库；5、6 需要本机 MySQL 客户端造数据，找不到时自动跳过并明确提示。

    python scripts/verify_sensor_delete.py
    DB_PORT=3308 python scripts/verify_sensor_delete.py

脚本会真实创建并删除以 AUTOTEST- 开头的测点，结束时打印清理 SQL。退出码 0 表示全部通过。
"""

import argparse
import json
import pathlib
import subprocess
import sys
import urllib.error
import urllib.request
import uuid

from db_env import load_config, mysql_command, preflight

BASE = "http://127.0.0.1:18280/api/v1"

passes = []
failures = []
skipped = []


CONFIG = load_config()


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


def skip(name, reason):
    skipped.append(name)
    print("  [SKIP] %s  (%s)" % (name, reason))


def field_errors(payload):
    data = (payload or {}).get("data") or {}
    return set((data.get("fields") or {}).keys())


def blockers_of(payload):
    data = (payload or {}).get("data") or {}
    return data.get("blockers") or []


def sql_available():
    return bool(CONFIG.get("name")) and (
        CONFIG["bin"] == "mysql" or pathlib.Path(CONFIG["bin"]).exists())


def sql(statement):
    """执行一条 SQL；成功返回标准输出，失败返回 None。"""
    result = subprocess.run(mysql_command(CONFIG, statement),
                            capture_output=True, text=True, encoding="utf-8", errors="replace")
    if result.returncode != 0:
        print("    SQL 失败：%s" % (result.stderr or "").strip().splitlines()[-1:])
        return None
    return result.stdout.strip()


def equipment_id():
    status, payload = call("GET", "/equipment?pageSize=1")
    if status != 200:
        raise SystemExit("无法读取设备列表，请确认后端已启动：%s %s" % (status, payload))
    items = ((payload.get("data") or {}).get("items") or [])
    if not items:
        raise SystemExit("库里没有设备，无法造测点。")
    return str(items[0]["id"])


# 本轮造出来的测点编码：改过规则的测点按 P05 约定不允许删除，清理只能按编码回落 SQL。
CREATED_CODES = []


def create_sensor(equipment, code):
    status, payload = call("POST", "/sensors", {
        "requestId": str(uuid.uuid4()), "operatorName": "验收脚本", "reason": "删除接口验收",
        "sensorCode": code, "name": "删除验收测点", "equipmentId": equipment,
        "sensorType": "温度", "location": "验收位置", "purpose": None, "enabled": True,
    })
    if status != 200:
        raise SystemExit("造测点失败：%s %s" % (status, payload))
    CREATED_CODES.append(code)
    return payload["data"]


def quote_codes(codes):
    return ", ".join("'%s'" % code for code in codes) or "''"


def purge_test_sensors(codes):
    """按编码直接清表。

    改过规则的测点带 PARAMETER_HISTORY 阻塞，接口不给删；收尾只能绕过接口，
    顺序与 ui-check-parameter-edit.mjs 保持一致：事件 → 规则版本 → 画像 → 变更日志 → 参数 → 测点。
    """
    quoted = quote_codes(codes)
    parameters = (sql("SELECT parameter_id FROM dim_sensor_parameter"
                      " WHERE sensor_id IN (SELECT sensor_id FROM dim_sensor"
                      " WHERE sensor_code IN (%s));" % quoted) or "").split()
    for parameter_id in parameters:
        events = (sql("SELECT event_id FROM p2_alert_event WHERE parameter_id = %s;"
                      % parameter_id) or "").split()
        if events:
            sql("DELETE FROM p2_alert_handling_record WHERE event_id IN (%s);"
                % ", ".join(events))
            sql("DELETE FROM p2_alert_event WHERE parameter_id = %s;" % parameter_id)
        sql("UPDATE p2_alert_rule_version SET previous_rule_version_id = NULL"
            " WHERE parameter_id = %s;" % parameter_id)
        sql("DELETE FROM p2_alert_rule_version WHERE parameter_id = %s;" % parameter_id)
        sql("DELETE FROM p2_sensor_parameter_profile WHERE parameter_id = %s;" % parameter_id)
        sql("DELETE FROM p2_change_log WHERE object_type = 'SENSOR_PARAMETER'"
            " AND object_id = '%s';" % parameter_id)
        sql("DELETE FROM dim_sensor_parameter WHERE parameter_id = %s;" % parameter_id)
    sql("DELETE FROM p2_change_log WHERE object_type = 'SENSOR'"
        " AND object_id IN (SELECT CAST(sensor_id AS CHAR) FROM dim_sensor"
        " WHERE sensor_code IN (%s));" % quoted)
    sql("DELETE FROM p2_sensor_profile WHERE sensor_id IN"
        " (SELECT sensor_id FROM dim_sensor WHERE sensor_code IN (%s));" % quoted)
    sql("DELETE FROM dim_sensor WHERE sensor_code IN (%s);" % quoted)


def create_parameter(sensor_id, code, with_rule=False):
    body = {
        "requestId": str(uuid.uuid4()), "operatorName": "验收脚本", "reason": None,
        "parameterCode": code, "name": "验收参数", "dataType": "DECIMAL",
        "unitCode": None, "decimalPlaces": 2, "samplingIntervalSeconds": 60,
        "processId": None, "enabled": True, "rule": None,
    }
    if with_rule:
        # 带首版规则：单版规则不阻塞删除，要靠再改一次才会变成历史。
        body["unitCode"] = "Pa"
        body["rule"] = {
            "normalLower": "0.5", "normalUpper": "1.5", "alarmLower": "0.1", "alarmUpper": "2.0",
            "unitCode": "Pa", "triggerMode": "IMMEDIATE", "durationSeconds": None, "enabled": True,
        }
    status, payload = call("POST", "/sensors/%s/parameters" % sensor_id, body)
    if status != 200:
        raise SystemExit("造参数失败：%s %s" % (status, payload))
    return payload["data"]


def delete_body(request_id, version, reason="误建测点，验收后删除", confirmed=True):
    return {"requestId": request_id, "operatorName": "验收脚本",
            "expectedVersion": version, "reason": reason, "confirmed": confirmed}


def main():
    global BASE
    parser = argparse.ArgumentParser()
    parser.add_argument("--base-url", default=BASE)
    args = parser.parse_args()
    BASE = args.base_url.rstrip("/")

    # 先探库再动数据：端口不对时立刻退出，不留 AUTOTEST- 测点。
    preflight(CONFIG, require_mysql=False)

    stamp = int(uuid.uuid4().int % 100000)
    equipment = equipment_id()
    created = []

    print("== 0. 准备 ==")
    sensor = create_sensor(equipment, "AUTOTEST-DEL-%d" % stamp)
    created.append(str(sensor["sensorId"]))
    parameter = create_parameter(sensor["sensorId"], "PRESSURE")
    check("造出待删测点与参数", sensor["version"] == 1 and bool(parameter.get("parameterId")),
          "version=%s" % sensor["version"])

    print("== 1. 详情里的删除可行性 ==")
    status, detail = call("GET", "/sensors/%s" % sensor["sensorId"])
    assert status == 200, detail
    deletion = detail["data"].get("deletion") or {}
    check("详情带 deletion 字段", "deletion" in detail["data"], sorted(detail["data"].keys()))
    check("无关联时允许删除", deletion.get("allowed") is True, deletion)
    check("无关联时 blockers 为空数组", deletion.get("blockers") == [], deletion)

    print("== 2. 请求校验 ==")
    status, payload = call("DELETE", "/sensors/%s" % sensor["sensorId"],
                           delete_body(str(uuid.uuid4()), 1, confirmed=False))
    check("缺二次确认返回 400", status == 400, "%s %s" % (status, payload))
    check("错误落在 confirmed 字段", "confirmed" in field_errors(payload), field_errors(payload))

    body = delete_body(str(uuid.uuid4()), 1, reason="   ")
    status, payload = call("DELETE", "/sensors/%s" % sensor["sensorId"], body)
    check("空删除原因返回 400", status == 400, "%s %s" % (status, payload))
    check("错误落在 reason 字段", "reason" in field_errors(payload), field_errors(payload))

    status, payload = call("DELETE", "/sensors/%s" % sensor["sensorId"],
                           delete_body(str(uuid.uuid4()), 0))
    check("版本号过期返回 409", status == 409, "%s %s" % (status, payload))
    check("409 带当前版本号", (payload.get("data") or {}).get("currentVersion") == 1, payload)

    status, payload = call("DELETE", "/sensors/abc", delete_body(str(uuid.uuid4()), 1))
    check("非法测点 ID 返回 400", status == 400, "%s %s" % (status, payload))

    status, payload = call("DELETE", "/sensors/999999", delete_body(str(uuid.uuid4()), 0))
    check("不存在的测点返回 404", status == 404, "%s %s" % (status, payload))

    print("== 3. 正常删除与级联 ==")
    request_id = str(uuid.uuid4())
    body = delete_body(request_id, 1)
    status, payload = call("DELETE", "/sensors/%s" % sensor["sensorId"], body)
    check("删除成功返回 200", status == 200, "%s %s" % (status, payload))
    result = (payload or {}).get("data") or {}
    check("回执字段为 sensorId", str(result.get("sensorId")) == str(sensor["sensorId"]), result)
    check("回执 deleted=true", result.get("deleted") is True, result)

    status, payload = call("GET", "/sensors/%s" % sensor["sensorId"])
    check("删后详情返回 404", status == 404, "%s %s" % (status, payload))

    status, payload = call("GET", "/sensor-parameters/%s/latest" % parameter["parameterId"])
    check("参数随测点一并删除", status == 404, "%s %s" % (status, payload))

    status, payload = call("GET", "/sensors?keyword=AUTOTEST-DEL-%d" % stamp)
    check("列表不再返回该测点", status == 200 and payload["data"]["total"] == 0, payload.get("data"))

    print("== 4. 幂等 ==")
    status, payload = call("DELETE", "/sensors/%s" % sensor["sensorId"], body)
    check("同 requestId 同内容重放返回 200", status == 200, "%s %s" % (status, payload))
    check("重放结果与首次一致", (payload.get("data") or {}).get("deleted") is True, payload)

    status, payload = call("DELETE", "/sensors/%s" % sensor["sensorId"],
                           delete_body(request_id, 1, reason="换一个理由"))
    check("同 requestId 换内容返回 409", status == 409, "%s %s" % (status, payload))
    check("冲突码为 IDEMPOTENCY_CONFLICT", payload.get("code") == "IDEMPOTENCY_CONFLICT", payload)

    print("== 5. 采集记录阻塞 ==")
    if not sql_available():
        skip("采集记录阻塞", "找不到 MySQL 客户端")
    else:
        blocked = create_sensor(equipment, "AUTOTEST-ODS-%d" % stamp)
        created.append(str(blocked["sensorId"]))
        create_parameter(blocked["sensorId"], "TEMP")
        inserted = sql(
            "INSERT INTO ods_sensor_data (source_system, sensor_code, parameter_code, collect_time, value_num)"
            " VALUES ('AUTOTEST', '%s', 'TEMP', NOW(), 1.5);" % blocked["sensorCode"])
        if inserted is None:
            skip("采集记录阻塞", "插入原始采集行失败")
        else:
            status, payload = call("GET", "/sensors/%s" % blocked["sensorId"])
            check("有采集记录时详情标记不可删",
                  (payload["data"].get("deletion") or {}).get("allowed") is False, payload["data"].get("deletion"))

            status, payload = call("DELETE", "/sensors/%s" % blocked["sensorId"],
                                   delete_body(str(uuid.uuid4()), 1))
            check("有采集记录时删除返回 409", status == 409, "%s %s" % (status, payload))
            types = [item.get("type") for item in blockers_of(payload)]
            check("阻塞类型为 COLLECTION_DATA", "COLLECTION_DATA" in types, types)
            message = (blockers_of(payload) or [{}])[0].get("message") or ""
            check("阻塞说明里带上测点编码", blocked["sensorCode"] in message, message)

            sql("DELETE FROM ods_sensor_data WHERE source_system = 'AUTOTEST'"
                " AND sensor_code = '%s';" % blocked["sensorCode"])
            status, payload = call("DELETE", "/sensors/%s" % blocked["sensorId"],
                                   delete_body(str(uuid.uuid4()), 1))
            check("清掉采集记录后可以删除", status == 200, "%s %s" % (status, payload))
            created.remove(str(blocked["sensorId"]))

    print("== 6. 规则历史阻塞 ==")
    if not sql_available():
        skip("规则历史阻塞", "找不到 MySQL 客户端")
    else:
        edited = create_sensor(equipment, "AUTOTEST-RULE-%d" % stamp)
        created.append(str(edited["sensorId"]))
        parameter = create_parameter(edited["sensorId"], "TEMP", with_rule=True)
        # 补第二版规则：P08 未开放前这是唯一能造出"改过规则"的手段。
        first = sql("SELECT MAX(rule_version_id) FROM p2_alert_rule_version"
                    " WHERE parameter_id = %s;" % parameter["parameterId"])
        second = sql(
            "INSERT INTO p2_alert_rule_version"
            " (parameter_id, rule_version, previous_rule_version_id, normal_lower, normal_upper,"
            "  alarm_lower, alarm_upper, unit_code, trigger_mode, duration_seconds, enabled,"
            "  effective_at, operator_name, reason)"
            " VALUES (%s, 2, %s, 0.6, 1.6, 0.2, 2.0, 'Pa', 'IMMEDIATE', NULL, 1, NOW(),"
            "         '验收脚本', '现场调整阈值');"
            % (parameter["parameterId"], first or "NULL"))
        if second is None:
            skip("规则历史阻塞", "插入第二版规则失败")
        else:
            status, payload = call("DELETE", "/sensors/%s" % edited["sensorId"],
                                   delete_body(str(uuid.uuid4()), 1))
            check("规则改过时删除返回 409", status == 409, "%s %s" % (status, payload))
            types = [item.get("type") for item in blockers_of(payload)]
            check("阻塞类型为 PARAMETER_HISTORY", "PARAMETER_HISTORY" in types, types)

    print("== 7. 清理 ==")
    for sensor_id in created:
        call("DELETE", "/sensors/%s" % sensor_id, delete_body(str(uuid.uuid4()), 0))
    if sql_available():
        purge_test_sensors(CREATED_CODES)
        leftover = sql("SELECT COUNT(*) FROM dim_sensor WHERE sensor_code IN (%s);"
                       % quote_codes(CREATED_CODES))
        check("验收测点已清理干净", (leftover or "").strip() == "0", "仍剩 %s 个" % leftover)
        rules = sql("SELECT COUNT(*) FROM p2_alert_rule_version WHERE parameter_id NOT IN"
                    " (SELECT parameter_id FROM dim_sensor_parameter);")
        check("规则版本随测点清理干净", (rules or "").strip() == "0", "仍剩 %s 条" % rules)
    else:
        print("  提示：请手工清理这些测点：%s。" % "、".join(CREATED_CODES))

    print()
    print("通过 %d 项，失败 %d 项，跳过 %d 项" % (len(passes), len(failures), len(skipped)))
    if failures:
        for name, detail in failures:
            print("  - %s：%s" % (name, detail))
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
