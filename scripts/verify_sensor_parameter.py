#!/usr/bin/env python3
"""S1-P07 · 新增参数接口验收脚本。

对运行中的后端做端到端验证，覆盖：
  1. 新建参数（带规则）：返回完整结构、规则回显、首版规则版本、配置版本 1
  2. 读回一致：P06 参数列表与测点详情的 parameterCount 同步，规则版本可查
  3. 无规则新建：rule 与 ruleVersionId 均为 null，不伪造空规则
  4. 幂等：同 requestId 同内容重放返回同一参数且不新增；同 requestId 改内容返回 409
  5. 校验：同测点重复编码 409、非法数据类型/边界颠倒/触发模式矛盾/单位不存在/缺字段 400
  6. 边界：不存在的测点 404、非法测点 ID 400

只用标准库：
    python scripts/verify_sensor_parameter.py
    python scripts/verify_sensor_parameter.py --base-url http://127.0.0.1:18280

脚本会真实写入一条测点及其参数（编码以 AUTOTEST- 开头），结束时打印清理 SQL。
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

PARAMETER_FIELDS = {
    "parameterId", "sensorId", "parameterCode", "name", "dataType", "unitCode",
    "decimalPlaces", "samplingIntervalSeconds", "processId", "enabled",
    "rule", "ruleVersionId", "version",
}

RULE_FIELDS = {
    "normalLower", "normalUpper", "alarmLower", "alarmUpper",
    "unitCode", "triggerMode", "durationSeconds", "enabled",
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


def field_errors(payload):
    """取出 400 响应里的字段级错误，便于断言"错在哪个字段"。"""
    data = (payload or {}).get("data") or {}
    return set((data.get("fields") or {}).keys())


def rule_body(**overrides):
    rule = {
        "normalLower": "0.5",
        "normalUpper": "1.5",
        "alarmLower": "0.1",
        "alarmUpper": "2.0",
        "triggerMode": "SUSTAINED",
        "durationSeconds": 30,
        "enabled": True,
    }
    rule.update(overrides)
    return rule


def main():
    global BASE
    parser = argparse.ArgumentParser()
    parser.add_argument("--base-url", default=BASE)
    args = parser.parse_args()
    BASE = args.base_url.rstrip("/")

    stamp = int(time.time())
    sensor_code = "AUTOTEST-P07-%d" % stamp
    parameter_code = "PRESS-%d" % stamp
    created_sensor = None
    created_parameter = None

    print("== 0. 准备一个测点 ==")
    status, payload = call("POST", "/sensors", {
        "requestId": str(uuid.uuid4()),
        "operatorName": "验收脚本",
        "sensorCode": sensor_code,
        "name": "验收用测点（P07）",
        "equipmentId": "1",
        "sensorType": "压力",
        "location": "验收位置",
        "purpose": "参数验收",
        "enabled": True,
    })
    check("准备测点成功", status == 200, "%s %s" % (status, payload))
    created_sensor = (payload.get("data") or {}).get("sensorId")
    if not created_sensor:
        print("无法创建父测点，后续断言无法进行。")
        return 1
    print("  测点 ID = %s" % created_sensor)

    parameter = {
        "requestId": str(uuid.uuid4()),
        "operatorName": "验收脚本",
        "reason": "验收脚本补录参数",
        "parameterCode": parameter_code,
        "name": "出口压力",
        "dataType": "DECIMAL",
        "unitCode": "Pa",
        "decimalPlaces": 2,
        "samplingIntervalSeconds": 60,
        "processId": None,
        "enabled": True,
        "rule": rule_body(),
    }

    print("== 1. 新建参数（带规则） ==")
    status, payload = call("POST", "/sensors/%s/parameters" % created_sensor, parameter)
    check("新建返回 200", status == 200, "%s %s" % (status, payload))
    data = payload.get("data") or {}
    created_parameter = data.get("parameterId")
    check("返回完整参数结构", PARAMETER_FIELDS <= set(data.keys()), sorted(set(data.keys()) - PARAMETER_FIELDS))
    check("返回新参数 ID", bool(created_parameter), data)
    check("参数归属正确", data.get("sensorId") == created_sensor, data.get("sensorId"))
    check("编码回显一致", data.get("parameterCode") == parameter_code, data.get("parameterCode"))
    check("数据类型回显一致", data.get("dataType") == "DECIMAL", data.get("dataType"))
    check("小数位回显一致", data.get("decimalPlaces") == 2, data.get("decimalPlaces"))
    check("采样周期回显一致", data.get("samplingIntervalSeconds") == 60, data.get("samplingIntervalSeconds"))
    check("配置版本为 1", data.get("version") == 1, data.get("version"))
    check("生成规则版本号", bool(data.get("ruleVersionId")), data.get("ruleVersionId"))
    rule = data.get("rule") or {}
    check("规则字段完整", RULE_FIELDS <= set(rule.keys()), sorted(rule.keys()))
    check("规则边界回显一致", rule.get("normalLower") == "0.5" and rule.get("alarmUpper") == "2", rule)
    check("规则单位继承参数单位", rule.get("unitCode") == "Pa", rule.get("unitCode"))
    check("触发模式回显一致", rule.get("triggerMode") == "SUSTAINED", rule.get("triggerMode"))
    check("持续秒数回显一致", rule.get("durationSeconds") == 30, rule.get("durationSeconds"))

    print("== 2. 读回与联动 ==")
    status, payload = call("GET", "/sensors/%s/parameters?pageSize=50" % created_sensor)
    items = (payload.get("data") or {}).get("items") or []
    check("参数列表返回 200", status == 200, status)
    check("列表能查到新参数", any(item.get("parameterId") == created_parameter for item in items),
          [item.get("parameterCode") for item in items][:5])
    mine = [item for item in items if item.get("parameterId") == created_parameter]
    if mine:
        check("列表带规则版本号", bool(mine[0].get("ruleVersionId")), mine[0].get("ruleVersionId"))
        check("列表标记规则已启用", mine[0].get("ruleConfigured") is True, mine[0].get("ruleConfigured"))

    status, payload = call("GET", "/sensors/%s" % created_sensor)
    detail = payload.get("data") or {}
    check("测点详情参数数同步", detail.get("parameterCount") == 1, detail.get("parameterCount"))
    check("测点详情带参数摘要",
          any(item.get("parameterId") == created_parameter for item in (detail.get("parameters") or [])),
          detail.get("parameters"))
    check("新增参数不改采集来源", detail.get("dataSource") is None, detail.get("dataSource"))
    check("配置状态仍为待配置", detail.get("configurationState") == "PENDING", detail.get("configurationState"))

    status, payload = call("GET", "/sensor-parameters/%s/rule-versions?pageSize=10" % created_parameter)
    versions = (payload.get("data") or {}).get("items") or []
    check("规则版本可查", status == 200 and len(versions) == 1, "%s %s" % (status, len(versions)))
    if versions:
        check("首版规则无前序版本", versions[0].get("previousRuleVersionId") is None,
              versions[0].get("previousRuleVersionId"))
        check("规则版本记录原因", versions[0].get("reason") == "验收脚本补录参数",
              versions[0].get("reason"))
        check("规则版本记录人员", versions[0].get("operatorName") == "验收脚本",
              versions[0].get("operatorName"))

    print("== 3. 幂等 ==")
    status, replay = call("POST", "/sensors/%s/parameters" % created_sensor, parameter)
    check("同内容重放返回 200", status == 200, status)
    check("重放返回同一参数 ID", (replay.get("data") or {}).get("parameterId") == created_parameter,
          (replay.get("data") or {}).get("parameterId"))
    status, payload = call("GET", "/sensors/%s/parameters?pageSize=50" % created_sensor)
    check("重放没有新增参数", (payload.get("data") or {}).get("total") == 1,
          (payload.get("data") or {}).get("total"))

    changed = dict(parameter)
    changed["name"] = "改过名字的出口压力"
    status, conflict = call("POST", "/sensors/%s/parameters" % created_sensor, changed)
    check("同 requestId 换内容返回 409", status == 409, status)
    check("冲突码为幂等冲突", "IDEMPOTENCY" in json.dumps(conflict), conflict.get("code"))

    print("== 4. 无规则新建 ==")
    # 契约对新增只要求 reason "可传"，这里故意不传，确认不会被当成缺字段拦下。
    bare = {
        "requestId": str(uuid.uuid4()),
        "operatorName": "验收脚本",
        "parameterCode": "TEMP-%d" % stamp,
        "name": "出口温度",
        "dataType": "DECIMAL",
        "unitCode": None,
        "decimalPlaces": None,
        "samplingIntervalSeconds": None,
        "processId": None,
        "enabled": True,
        "rule": None,
    }
    status, payload = call("POST", "/sensors/%s/parameters" % created_sensor, bare)
    check("无规则新建返回 200", status == 200, "%s %s" % (status, payload))
    plain = payload.get("data") or {}
    check("无规则时 rule 为 null", plain.get("rule") is None, plain.get("rule"))
    check("无规则时没有规则版本号", plain.get("ruleVersionId") is None, plain.get("ruleVersionId"))
    check("小数位缺省为 2", plain.get("decimalPlaces") == 2, plain.get("decimalPlaces"))

    print("== 5. 校验 ==")
    duplicate = dict(parameter, requestId=str(uuid.uuid4()))
    status, dup = call("POST", "/sensors/%s/parameters" % created_sensor, duplicate)
    check("同测点重复编码返回 409", status == 409, "%s %s" % (status, dup))
    check("重复编码错误码正确", (dup.get("code") or "").endswith("DUPLICATE_CODE"), dup.get("code"))
    check("错误指向 parameterCode", "parameterCode" in field_errors(dup), field_errors(dup))

    bad_type = dict(parameter, requestId=str(uuid.uuid4()), parameterCode="BAD-TYPE-%d" % stamp,
                    dataType="FLOAT")
    status, invalid = call("POST", "/sensors/%s/parameters" % created_sensor, bad_type)
    check("非法数据类型返回 400", status == 400, status)
    check("错误指向 dataType", "dataType" in field_errors(invalid), field_errors(invalid))

    flipped = dict(parameter, requestId=str(uuid.uuid4()), parameterCode="FLIP-%d" % stamp,
                   rule=rule_body(normalLower="9", normalUpper="1"))
    status, invalid = call("POST", "/sensors/%s/parameters" % created_sensor, flipped)
    check("上下界颠倒返回 400", status == 400, status)
    check("错误指向规则下界", "rule.normalLower" in field_errors(invalid), field_errors(invalid))

    immediate = dict(parameter, requestId=str(uuid.uuid4()), parameterCode="IMM-%d" % stamp,
                     rule=rule_body(triggerMode="IMMEDIATE", durationSeconds=30))
    status, invalid = call("POST", "/sensors/%s/parameters" % created_sensor, immediate)
    check("立即触发带持续秒数返回 400", status == 400, status)
    check("错误指向持续秒数", "rule.durationSeconds" in field_errors(invalid), field_errors(invalid))

    sustained = dict(parameter, requestId=str(uuid.uuid4()), parameterCode="SUS-%d" % stamp,
                     rule=rule_body(durationSeconds=None))
    status, invalid = call("POST", "/sensors/%s/parameters" % created_sensor, sustained)
    check("持续触发缺秒数返回 400", status == 400, status)
    check("错误指向持续秒数", "rule.durationSeconds" in field_errors(invalid), field_errors(invalid))

    bad_unit = dict(parameter, requestId=str(uuid.uuid4()), parameterCode="UNIT-%d" % stamp,
                    unitCode="NOT_A_UNIT")
    status, invalid = call("POST", "/sensors/%s/parameters" % created_sensor, bad_unit)
    check("单位不存在返回 400", status == 400, status)
    check("错误指向 unitCode", "unitCode" in field_errors(invalid), field_errors(invalid))

    missing = {"requestId": str(uuid.uuid4()), "operatorName": "验收脚本",
               "parameterCode": "MISS-%d" % stamp, "name": "缺字段", "dataType": "DECIMAL",
               "enabled": True}
    status, invalid = call("POST", "/sensors/%s/parameters" % created_sensor, missing)
    check("缺少可空字段返回 400", status == 400, status)
    check("缺字段被逐条列出",
          {"unitCode", "rule", "decimalPlaces", "processId"} <= field_errors(invalid),
          field_errors(invalid))

    zero_interval = dict(parameter, requestId=str(uuid.uuid4()), parameterCode="INT-%d" % stamp,
                         samplingIntervalSeconds=0)
    status, invalid = call("POST", "/sensors/%s/parameters" % created_sensor, zero_interval)
    check("采样周期为 0 返回 400", status == 400, status)
    check("错误指向采样周期", "samplingIntervalSeconds" in field_errors(invalid), field_errors(invalid))

    print("== 6. 边界 ==")
    status, notfound = call("POST", "/sensors/999999/parameters",
                            dict(parameter, requestId=str(uuid.uuid4())))
    check("测点不存在返回 404", status == 404, "%s %s" % (status, notfound))

    status, invalid = call("POST", "/sensors/abc/parameters",
                           dict(parameter, requestId=str(uuid.uuid4())))
    check("非法测点 ID 返回 400", status == 400, "%s %s" % (status, invalid))

    print()
    print("通过 %d 项，失败 %d 项" % (len(passes), len(failures)))
    if failures:
        for name, detail in failures:
            print("  - %s -> %s" % (name, detail))
        return 1

    print()
    print("清理（按需执行）：")
    print("  DELETE FROM p2_change_log WHERE object_type='SENSOR_PARAMETER' AND object_id IN "
          "(SELECT parameter_id FROM dim_sensor_parameter WHERE sensor_id=%s);" % created_sensor)
    print("  DELETE FROM p2_alert_rule_version WHERE parameter_id IN "
          "(SELECT parameter_id FROM dim_sensor_parameter WHERE sensor_id=%s);" % created_sensor)
    print("  DELETE FROM p2_sensor_parameter_profile WHERE parameter_id IN "
          "(SELECT parameter_id FROM dim_sensor_parameter WHERE sensor_id=%s);" % created_sensor)
    print("  DELETE FROM dim_sensor_parameter WHERE sensor_id=%s;" % created_sensor)
    print("  DELETE FROM p2_change_log WHERE object_type='SENSOR' AND object_id='%s';" % created_sensor)
    print("  DELETE FROM p2_sensor_profile WHERE sensor_id=%s;" % created_sensor)
    print("  DELETE FROM dim_sensor WHERE sensor_id=%s;" % created_sensor)
    return 0


if __name__ == "__main__":
    sys.exit(main())
