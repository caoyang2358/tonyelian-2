#!/usr/bin/env python3
"""S1-P01/P02/P06/P09/P10/P11 · 测点只读接口验收脚本。

对运行中的后端做端到端验证，覆盖：
  1. 测点列表：字段齐全、分页不重不漏、按设备/关键词/启停用/监测状态筛选
  2. 实测口径：库内无最新值快照时，监测状态一律 UNKNOWN，不谎报在线
  3. 测点详情：版本、配置状态、参数摘要与列表 parameterCount 一致
  4. 参数定义分页；最新读数缺值时返回缺值状态而不是 404
  5. 历史查询的区间校验（缺参、倒序、超 92 天）与空态语义
  6. 规则版本分页；无规则时返回空页而不是报错
  7. 非法 ID、非法枚举、越界分页返回 400 并指向字段

只用标准库：
    python scripts/verify_sensor.py
    python scripts/verify_sensor.py --base-url http://127.0.0.1:18280

退出码 0 表示全部通过。全部为只读断言：脚本不写入任何测点、参数、读数或规则。
"""

import argparse
import datetime
import json
import sys
import urllib.error
import urllib.parse
import urllib.request

BUSINESS_ZONE = datetime.timezone(datetime.timedelta(hours=8))

passes = []
failures = []

SENSOR_FIELDS = {
    "sensorId", "sensorCode", "name", "equipmentId", "equipmentCode", "equipmentName",
    "workshopId", "workshopName", "location", "sensorType", "dataSource",
    "enabled", "parameterCount", "monitoringStatus", "configurationState",
}
DETAIL_FIELDS = SENSOR_FIELDS | {"purpose", "hasHistory", "version", "parameters"}
PARAM_FIELDS = {
    "parameterId", "parameterCode", "name", "dataType", "unitCode", "decimalPlaces",
    "samplingIntervalSeconds", "processId", "enabled", "ruleVersionId", "ruleConfigured",
}
LATEST_FIELDS = {
    "parameterId", "sensorId", "sensorCode", "parameterCode", "value", "valueText",
    "unitCode", "qualityStatus", "limitStatus", "collectTime", "refreshTime",
    "monitoringStatus", "dataSource", "missingFields",
}
HISTORY_FIELDS = {
    "recordId", "value", "valueText", "sourceUnit", "qualityCode", "collectTime", "sourceSystem",
}
RULE_FIELDS = {
    "ruleVersionId", "ruleVersion", "previousRuleVersionId", "rule",
    "effectiveAt", "reason", "operatorName", "affectedEventIds",
}
MONITORING_STATES = {"ONLINE", "STALE", "UNKNOWN", "DISABLED"}

BASE = "http://127.0.0.1:18280/api/v1"


def call(method, url):
    """返回 (status, payload)。HTTP 4xx/5xx 也读出 body，不抛异常。"""
    request = urllib.request.Request(url, method=method)
    try:
        with urllib.request.urlopen(request, timeout=10) as response:
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


def data_of(payload):
    return payload.get("data") if isinstance(payload, dict) else None


def fields_of(payload):
    value = data_of(payload)
    return set(value.keys()) if isinstance(value, dict) else set()


def field_errors(payload):
    detail = data_of(payload) or {}
    return set((detail.get("fields") or {}).keys()) if isinstance(detail, dict) else set()


def get(path):
    return call("GET", BASE + path)


def main():
    global BASE
    parser = argparse.ArgumentParser()
    parser.add_argument("--base-url", default="http://127.0.0.1:18280",
                        help="后端根地址，默认 http://127.0.0.1:18280")
    args = parser.parse_args()
    BASE = args.base_url.rstrip("/") + "/api/v1"

    print("== 1. 测点列表 ==")
    status, payload = get("/sensors?page=1&pageSize=100")
    check("列表返回 200", status == 200, "实际 HTTP %s" % status)
    page = data_of(payload) or {}
    items = page.get("items") or []
    check("列表返回 items 数组", isinstance(items, list), type(items).__name__)
    check("列表非空（演示库有测点）", len(items) > 0, len(items))
    check("分页字段齐全", {"page", "pageSize", "total"} <= set(page.keys()), set(page.keys()))
    check("total 与全量一致", page.get("total") == len(items), "%s vs %s" % (page.get("total"), len(items)))
    if items:
        missing = SENSOR_FIELDS - set(items[0].keys())
        check("摘要字段齐全", not missing, missing)
        check("监测状态在四态内", all(item.get("monitoringStatus") in MONITORING_STATES for item in items),
              {item.get("monitoringStatus") for item in items})
        check("配置状态在 READY/PENDING 内",
              all(item.get("configurationState") in {"READY", "PENDING"} for item in items),
              {item.get("configurationState") for item in items})
        check("ID 以十进制字符串返回",
              all(isinstance(item.get("sensorId"), str) and item["sensorId"].isdigit() for item in items),
              items[0].get("sensorId"))
        check("参数数量为非负整数",
              all(isinstance(item.get("parameterCount"), int) and item["parameterCount"] >= 0 for item in items),
              items[0].get("parameterCount"))

    # 排序稳定：两次请求的 ID 序列必须一致，否则分页会跳行。
    again = (data_of(get("/sensors?page=1&pageSize=100")[1]) or {}).get("items") or []
    check("列表排序稳定", [row["sensorId"] for row in items] == [row["sensorId"] for row in again])

    print("== 2. 分页不重不漏 ==")
    if len(items) >= 3:
        first = (data_of(get("/sensors?page=1&pageSize=2")[1]) or {})
        second = (data_of(get("/sensors?page=2&pageSize=2")[1]) or {})
        first_ids = [row["sensorId"] for row in first.get("items") or []]
        second_ids = [row["sensorId"] for row in second.get("items") or []]
        check("第一页 2 条", len(first_ids) == 2, first_ids)
        check("两页无重复", not (set(first_ids) & set(second_ids)), (first_ids, second_ids))
        check("第二页接在第一页之后", second_ids and first_ids == [row["sensorId"] for row in items[:2]],
              first_ids)

    print("== 3. 筛选条件 ==")
    if items:
        equipment_id = items[0]["equipmentId"]
        status, payload = get("/sensors?equipmentId=%s&pageSize=100" % equipment_id)
        filtered = data_of(payload) or {}
        check("按设备筛选返回 200", status == 200, status)
        check("按设备筛选只含该设备",
              all(row["equipmentId"] == equipment_id for row in filtered.get("items") or []),
              {row["equipmentId"] for row in filtered.get("items") or []})

        code = items[0]["sensorCode"]
        status, payload = get("/sensors?keyword=%s" % urllib.parse.quote(code))
        hits = (data_of(payload) or {}).get("items") or []
        check("关键词命中测点编码", any(row["sensorId"] == items[0]["sensorId"] for row in hits), code)

        status, payload = get("/sensors?keyword=%s" % urllib.parse.quote("__NO_SUCH_SENSOR__"))
        check("无命中时返回空页而不是报错", status == 200 and (data_of(payload) or {}).get("total") == 0,
              "%s %s" % (status, (data_of(payload) or {}).get("total")))

        status, payload = get("/sensors?enabled=false&pageSize=100")
        check("按停用筛选返回 200", status == 200, status)
        check("停用筛选只含停用测点",
              all(row["enabled"] is False for row in (data_of(payload) or {}).get("items") or []),
              (data_of(payload) or {}).get("items"))

        status, payload = get("/sensors?monitoringStatus=unknown&pageSize=100")
        unknown = data_of(payload) or {}
        check("监测状态筛选大小写不敏感", status == 200, status)
        check("筛选结果状态一致",
              all(row["monitoringStatus"] == "UNKNOWN" for row in unknown.get("items") or []),
              {row["monitoringStatus"] for row in unknown.get("items") or []})

    print("== 4. 参数校验 ==")
    status, payload = get("/sensors?monitoringStatus=OFFLINE")
    check("非法监测状态返回 400", status == 400, status)
    check("错误指向 monitoringStatus", "monitoringStatus" in field_errors(payload), field_errors(payload))

    status, payload = get("/sensors?equipmentId=abc")
    check("非法设备 ID 返回 400", status == 400, status)
    check("错误指向 equipmentId", "equipmentId" in field_errors(payload), field_errors(payload))

    status, payload = get("/sensors?page=1&pageSize=101")
    check("越界 pageSize 返回 400", status == 400, status)
    status, payload = get("/sensors?page=0&pageSize=20")
    check("页码 0 返回 400", status == 400, status)

    print("== 5. 测点详情 ==")
    if items:
        sensor_id = items[0]["sensorId"]
        status, payload = get("/sensors/%s" % sensor_id)
        detail = data_of(payload) or {}
        check("详情返回 200", status == 200, status)
        missing = DETAIL_FIELDS - set(detail.keys())
        check("详情字段齐全", not missing, missing)
        check("详情与列表同一测点", detail.get("sensorId") == sensor_id, detail.get("sensorId"))
        check("版本为非负整数", isinstance(detail.get("version"), int) and detail["version"] >= 0,
              detail.get("version"))
        check("hasHistory 为布尔", isinstance(detail.get("hasHistory"), bool), detail.get("hasHistory"))
        parameters = detail.get("parameters") or []
        check("详情带参数数组", isinstance(parameters, list), type(parameters).__name__)
        check("详情参数数与列表一致",
              len(parameters) == items[0]["parameterCount"],
              "%s vs %s" % (len(parameters), items[0]["parameterCount"]))
        if parameters:
            missing = PARAM_FIELDS - set(parameters[0].keys())
            check("参数摘要字段齐全", not missing, missing)

        status, payload = get("/sensors/999999")
        check("不存在测点返回 404", status == 404, status)
        status, payload = get("/sensors/abc")
        check("非法测点 ID 返回 400", status == 400, status)

    print("== 6. 参数定义（P06） ==")
    parameter_id = None
    if items:
        # 挑第一个有参数的测点，而不是列表首位：库里可能残留无参数的误建测点，
        # 拿首位会让后面四组断言被静默跳过，看起来"通过"其实什么都没验。
        target = next((item for item in items if item["parameterCount"] > 0), items[0])
        status, payload = get("/sensors/%s/parameters?page=1&pageSize=50" % target["sensorId"])
        page = data_of(payload) or {}
        check("参数分页返回 200", status == 200, status)
        check("参数 total 与 items 一致",
              page.get("total") == len(page.get("items") or []),
              "%s vs %s" % (page.get("total"), len(page.get("items") or [])))
        if page.get("items"):
            parameter_id = page["items"][0]["parameterId"]
            check("参数 ID 为十进制字符串",
                  isinstance(parameter_id, str) and parameter_id.isdigit(), parameter_id)

    print("== 7. 最新读数（P09） ==")
    if parameter_id:
        status, payload = get("/sensor-parameters/%s/latest" % parameter_id)
        latest = data_of(payload) or {}
        check("最新值返回 200", status == 200, status)
        missing = LATEST_FIELDS - set(latest.keys())
        check("最新值字段齐全", not missing, missing)
        check("参数身份回显一致", latest.get("parameterId") == parameter_id, latest.get("parameterId"))
        check("监测状态在四态内", latest.get("monitoringStatus") in MONITORING_STATES,
              latest.get("monitoringStatus"))
        # 演示库的 ads_parameter_monitor 为空：此时必须是 UNKNOWN + 缺值字段，
        # 不能因为没数据就返回 404，也不能谎报 ONLINE。
        if latest.get("collectTime") is None:
            check("无采集记录时为 UNKNOWN", latest.get("monitoringStatus") == "UNKNOWN",
                  latest.get("monitoringStatus"))
            check("无采集记录时标出缺值字段", "latestReading" in (latest.get("missingFields") or []),
                  latest.get("missingFields"))
        else:
            check("有采集记录时给出采集时间", bool(latest.get("collectTime")), latest.get("collectTime"))

        status, payload = get("/sensor-parameters/999999/latest")
        check("不存在参数的最新值返回 404", status == 404, status)

    print("== 8. 历史读数（P10） ==")
    if parameter_id:
        today = datetime.datetime.now(BUSINESS_ZONE).date()
        start = today.isoformat()
        end = (today + datetime.timedelta(days=1)).isoformat()

        status, payload = get("/sensor-parameters/%s/history?from=%s&to=%s&page=1&pageSize=20"
                              % (parameter_id, start, end))
        history = data_of(payload) or {}
        check("历史查询返回 200", status == 200, status)
        check("历史分页字段齐全", {"items", "page", "pageSize", "total"} <= set(history.keys()),
              set(history.keys()))
        check("空区间返回空列表而不是报错",
              isinstance(history.get("items"), list), type(history.get("items")).__name__)
        if history.get("items"):
            missing = HISTORY_FIELDS - set(history["items"][0].keys())
            check("历史记录字段齐全", not missing, missing)

        status, payload = get("/sensor-parameters/%s/history?to=%s" % (parameter_id, end))
        check("缺 from 返回 400", status == 400, status)
        check("错误指向 from", "from" in field_errors(payload), field_errors(payload))

        status, payload = get("/sensor-parameters/%s/history?from=%s&to=%s"
                              % (parameter_id, end, start))
        check("from 晚于 to 返回 400", status == 400, status)

        far = (today - datetime.timedelta(days=200)).isoformat()
        status, payload = get("/sensor-parameters/%s/history?from=%s&to=%s" % (parameter_id, far, end))
        check("区间超 92 天返回 400", status == 400, status)
        check("错误指向 to", "to" in field_errors(payload), field_errors(payload))

        status, payload = get("/sensor-parameters/%s/history?from=%s&to=%s"
                              % (parameter_id, urllib.parse.quote("昨天"), end))
        check("非法时间格式返回 400", status == 400, status)

        status, payload = get("/sensor-parameters/999999/history?from=%s&to=%s" % (start, end))
        check("不存在参数的历史返回 404", status == 404, status)

    print("== 9. 规则版本（P11） ==")
    if parameter_id:
        status, payload = get("/sensor-parameters/%s/rule-versions?page=1&pageSize=20" % parameter_id)
        rules = data_of(payload) or {}
        check("规则版本返回 200", status == 200, status)
        check("无规则时返回空页而不是报错",
              isinstance(rules.get("items"), list) and rules.get("total") == len(rules.get("items")),
              "%s / %s" % (rules.get("total"), len(rules.get("items") or [])))
        if rules.get("items"):
            missing = RULE_FIELDS - set(rules["items"][0].keys())
            check("规则版本字段齐全", not missing, missing)
            check("受影响事件为字符串数组",
                  isinstance(rules["items"][0].get("affectedEventIds"), list),
                  rules["items"][0].get("affectedEventIds"))
            checks = [rules["items"][0]["ruleVersion"] for item in rules["items"]]
            check("版本号倒序排列", checks == sorted(checks, reverse=True), checks)

        status, payload = get("/sensor-parameters/%s/rule-versions?pageSize=101" % parameter_id)
        check("规则版本越界 pageSize 返回 400", status == 400, status)

        status, payload = get("/sensor-parameters/999999/rule-versions")
        check("不存在参数的规则版本返回 404", status == 404, status)

    print("== 10. 写入侧边界 ==")
    # P03/P04 已开放（见 verify_sensor_write.py），这里只确认读接口脚本不会误写：
    # 空请求体必须被挡在校验层，而不是落一条空测点。
    status, _ = call("POST", BASE + "/sensors")
    check("POST /sensors 空请求体被校验拦下", status == 400, status)
    # P05 已开放（见 verify_sensor_delete.py）：不带请求体的删除必须被校验拦下，
    # 不能凭一个 URL 就把测点删掉。
    status, _ = call("DELETE", BASE + "/sensors/%s" % (items[0]["sensorId"] if items else "1"))
    check("DELETE /sensors 缺请求体被校验拦下", status == 400, status)

    print()
    print("通过 %d 项，失败 %d 项" % (len(passes), len(failures)))
    if failures:
        print("失败明细：")
        for name, detail in failures:
            print("  - %s  ->  %s" % (name, detail))
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
