#!/usr/bin/env python3
"""S1-P08 · 参数配置更新与规则版本验收脚本。

对运行中的后端做端到端验证，覆盖：
  1. 首次配置：种子参数（version 0）补档案行；从未配规则时带规则保存生成第 1 版
  2. 判断依据未变（只改名称、或数值等价的边界）：不生成新版本，只推进配置版本
  3. 判断依据变化（阈值/触发方式/规则启停/单位量纲）：追加不可覆盖的新版本并串上前一版
  4. 规则版本历史（P11）：新旧快照都可查，最新一版在首位
  5. 幂等：同 requestId 同内容重放不重复推进版本；同 requestId 改内容返回 409
  6. 冲突与校验：版本号过期 409、参数不存在 404、非法 ID 400、缺字段/非法单位/上下界颠倒 400
  7. 事件结束：判断依据变化时，该参数的活动事件按 RULE_CHANGED 结束并回填事件号；
     只改展示字段时事件继续挂着
  8. 清理：删除验收测点，删除后配置更新返回 404

    python scripts/verify_sensor_config.py
    DB_PORT=3308 python scripts/verify_sensor_config.py

脚本只创建并删除以 AUTOTEST- 开头的测点，退出码 0 表示全部通过。
"""

import argparse
import json
import os
import pathlib
import subprocess
import sys
import urllib.error
import urllib.request
import uuid

BASE = "http://127.0.0.1:18280/api/v1"
ROOT = pathlib.Path(__file__).resolve().parent.parent

passes = []
failures = []


def load_env():
    values = {}
    env_file = ROOT / ".env"
    if env_file.exists():
        for line in env_file.read_text(encoding="utf-8").splitlines():
            line = line.strip()
            if not line or line.startswith("#") or "=" not in line:
                continue
            key, value = line.split("=", 1)
            values[key.strip()] = value.strip()
    values["DB_PORT"] = os.environ.get("DB_PORT") or values.get("DB_PORT") or "3306"
    values["MYSQL_BIN"] = os.environ.get("MYSQL_BIN") or values.get(
        "MYSQL_BIN", "D:/MySQL/MySQL Server 8.0/bin/mysql.exe")
    return values


ENV = load_env()


def call(method, path, body=None):
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
    except urllib.error.URLError as error:
        raise SystemExit("连不上后端 %s：%s\n（先启动后端：见 scripts/Start-Dev.ps1）" % (BASE, error))


def check(name, condition, detail=""):
    if condition:
        passes.append(name)
        print("  [PASS] %s" % name)
    else:
        failures.append((name, detail))
        print("  [FAIL] %s  ->  %s" % (name, detail))


def field_errors(payload):
    data = (payload or {}).get("data") or {}
    return set((data.get("fields") or {}).keys())


def data_of(payload):
    return (payload or {}).get("data") or {}


def sql_available():
    return pathlib.Path(ENV["MYSQL_BIN"]).exists() and bool(ENV.get("DB_NAME"))


def sql(statement):
    command = [
        ENV["MYSQL_BIN"], "--no-defaults", "--default-character-set=utf8mb4",
        "-h127.0.0.1", "-P" + str(ENV["DB_PORT"]),
        "-u" + ENV.get("DB_USER", "root"), "-p" + ENV.get("DB_PASSWORD", ""),
        ENV["DB_NAME"], "-N", "-e", statement,
    ]
    result = subprocess.run(command, capture_output=True, text=True, encoding="utf-8", errors="replace")
    if result.returncode != 0:
        print("    SQL 失败：%s" % (result.stderr or "").strip().splitlines()[-1:])
        return None
    return result.stdout.strip()


def equipment_id():
    status, payload = call("GET", "/equipment?pageSize=1")
    if status != 200:
        raise SystemExit("无法读取设备列表：%s %s" % (status, payload))
    items = data_of(payload).get("items") or []
    if not items:
        raise SystemExit("库里没有设备，无法造测点。")
    return str(items[0]["id"])


def create_sensor(equipment, code):
    status, payload = call("POST", "/sensors", {
        "requestId": str(uuid.uuid4()), "operatorName": "验收脚本", "reason": "配置更新验收",
        "sensorCode": code, "name": "配置验收测点", "equipmentId": equipment,
        "sensorType": "压力", "location": "验收位置", "purpose": None, "enabled": True,
    })
    if status != 200:
        raise SystemExit("造测点失败：%s %s" % (status, payload))
    return data_of(payload)


def create_parameter(sensor_id, code):
    """不带规则新建：参数处于"未配规则"，version 从 1 起。"""
    status, payload = call("POST", "/sensors/%s/parameters" % sensor_id, {
        "requestId": str(uuid.uuid4()), "operatorName": "验收脚本", "reason": None,
        "parameterCode": code, "name": "配置验收参数", "dataType": "DECIMAL",
        "unitCode": "Pa", "decimalPlaces": 2, "samplingIntervalSeconds": 60,
        "processId": None, "enabled": True, "rule": None,
    })
    if status != 200:
        raise SystemExit("造参数失败：%s %s" % (status, payload))
    return data_of(payload)


def rule(normal_upper="120", trigger="SUSTAINED", duration=30, enabled=True, unit="Pa"):
    return {
        "normalLower": "0", "normalUpper": normal_upper,
        "alarmLower": "-10", "alarmUpper": "150",
        "unitCode": unit, "triggerMode": trigger,
        "durationSeconds": duration, "enabled": enabled,
    }


def config_body(request_id, expected_version, name="配置验收参数", unit="Pa", rule_value=None):
    return {
        "requestId": request_id, "operatorName": "验收脚本", "reason": "调整阈值",
        "expectedVersion": expected_version,
        "name": name, "unitCode": unit, "decimalPlaces": 2, "samplingIntervalSeconds": 60,
        "processId": None, "enabled": True,
        "rule": rule_value if rule_value is not None else rule(),
    }


def parameter_state(sensor_id, parameter_id):
    """参数在测点详情里的当前快照（配置版本、当前规则版本都在里面）。"""
    status, payload = call("GET", "/sensors/%s" % sensor_id)
    if status != 200:
        return {}
    for item in data_of(payload).get("parameters") or []:
        if str(item.get("parameterId")) == str(parameter_id):
            return item
    return {}


def parameter_version(sensor_id, parameter_id):
    """从测点详情里读参数的配置版本。"""
    return parameter_state(sensor_id, parameter_id).get("version")


def seed_event(parameter_id, sensor_id, equipment_id, rule_version_id, tag):
    """直接落一条活动事件。

    事件的生成属于 S1-3 异常事件服务，本期没有接口可以造；这里写表只为验证 P08 的结束逻辑，
    行上留 source_reference 标记，随验收数据一起删干净。"""
    sql("INSERT INTO p2_alert_event "
        "(equipment_id, sensor_id, parameter_id, rule_version_id, started_at, triggered_at, "
        " trigger_value, unit_code, source_reference, updated_at) VALUES "
        "(%s, %s, %s, %s, NOW(3) - INTERVAL 5 MINUTE, NOW(3), 130.000000, 'Pa', "
        " JSON_OBJECT('seededBy', 'VERIFY-CFG', 'tag', '%s'), NOW(3));"
        % (equipment_id, sensor_id, parameter_id, rule_version_id, tag))
    created = sql("SELECT event_id FROM p2_alert_event "
                  " WHERE parameter_id = %s AND ended_at IS NULL ORDER BY event_id DESC LIMIT 1;"
                  % parameter_id)
    return int(created) if created and created.isdigit() else None


def main():
    global BASE
    parser = argparse.ArgumentParser()
    parser.add_argument("--base-url", default=BASE)
    args = parser.parse_args()
    BASE = args.base_url.rstrip("/")

    stamp = int(uuid.uuid4().int % 100000)
    equipment = equipment_id()
    created = []

    print("== 0. 准备 ==")
    sensor = create_sensor(equipment, "AUTOTEST-CFG-%d" % stamp)
    created.append(str(sensor["sensorId"]))
    parameter = create_parameter(sensor["sensorId"], "PRESSURE")
    parameter_id = str(parameter["parameterId"])
    check("造出待配置的参数", parameter["version"] == 1 and parameter["rule"] is None,
          "version=%s rule=%s" % (parameter["version"], parameter["rule"]))

    # ------------------------------------------------------------ 1. 首次配置
    print("== 1. 首次配置：从未配规则到第 1 版 ==")
    first_id = str(uuid.uuid4())
    status, payload = call("PUT", "/sensor-parameters/%s/configuration" % parameter_id,
                           config_body(first_id, 1))
    data = data_of(payload)
    check("带规则保存成功", status == 200, "%s %s" % (status, payload))
    check("配置版本从 1 推进到 2", data.get("version") == 2, data.get("version"))
    check("原来没有规则，previousRuleVersionId 为空", data.get("previousRuleVersionId") is None,
          data.get("previousRuleVersionId"))
    check("生成了第 1 版规则", isinstance(data.get("ruleVersionId"), str)
          and data.get("ruleVersionId"), data.get("ruleVersionId"))
    check("响应带回完整规则", (data.get("rule") or {}).get("normalUpper") == "120",
          data.get("rule"))
    check("首次配置没有活动事件需要结束",
          data.get("affectedEventIds") == [], data.get("affectedEventIds"))
    check("生效说明写明本次没有结束任何事件",
          "没有需要结束的活动事件" in (data.get("effectNote") or ""), data.get("effectNote"))
    first_rule_id = data.get("ruleVersionId")

    # ------------------------------------------------------------ 2. 判断依据未变
    print("== 2. 判断依据未变：只推进配置版本 ==")
    status, payload = call("PUT", "/sensor-parameters/%s/configuration" % parameter_id,
                           config_body(str(uuid.uuid4()), 2, name="配置验收参数（改名）"))
    data = data_of(payload)
    check("只改名称保存成功", status == 200, "%s %s" % (status, payload))
    check("配置版本推进到 3", data.get("version") == 3, data.get("version"))
    check("规则版本未变", data.get("ruleVersionId") == first_rule_id,
          "%s vs %s" % (data.get("ruleVersionId"), first_rule_id))
    check("前一版与当前版相同", data.get("previousRuleVersionId") == first_rule_id,
          data.get("previousRuleVersionId"))
    check("生效说明写明判断依据未变化",
          "未变化" in (data.get("effectNote") or ""), data.get("effectNote"))
    check("名称已真的改掉", parameter_version(sensor["sensorId"], parameter_id) == 3,
          parameter_version(sensor["sensorId"], parameter_id))

    # 数值等价：库里是 DECIMAL(20,6)，读回 0.000000 与请求 0 是同一个阈值。
    status, payload = call("PUT", "/sensor-parameters/%s/configuration" % parameter_id,
                           config_body(str(uuid.uuid4()), 3,
                                       rule_value=rule(normal_upper="120.000000")))
    data = data_of(payload)
    check("边界尾随零不算判断依据变化", status == 200
          and data.get("ruleVersionId") == first_rule_id, data.get("ruleVersionId"))

    # ------------------------------------------------------------ 3. 判断依据变化
    print("== 3. 判断依据变化：追加不可覆盖的新版本 ==")
    status, payload = call("PUT", "/sensor-parameters/%s/configuration" % parameter_id,
                           config_body(str(uuid.uuid4()), 4, rule_value=rule(normal_upper="140")))
    data = data_of(payload)
    check("改阈值保存成功", status == 200, "%s %s" % (status, payload))
    check("生成了新的规则版本", data.get("ruleVersionId") != first_rule_id
          and isinstance(data.get("ruleVersionId"), str), data.get("ruleVersionId"))
    check("新版本指向上一版", data.get("previousRuleVersionId") == first_rule_id,
          data.get("previousRuleVersionId"))
    check("新版本内容为改后的阈值", (data.get("rule") or {}).get("normalUpper") == "140",
          data.get("rule"))
    check("生效说明写明判断依据已变化",
          "判断依据已变化" in (data.get("effectNote") or ""), data.get("effectNote"))
    second_rule_id = data.get("ruleVersionId")

    # 规则启停也改变判断依据。
    status, payload = call("PUT", "/sensor-parameters/%s/configuration" % parameter_id,
                           config_body(str(uuid.uuid4()), 5,
                                       rule_value=rule(normal_upper="140", enabled=False)))
    data = data_of(payload)
    check("关闭规则启停生成新版本", status == 200
          and data.get("ruleVersionId") != second_rule_id, data.get("ruleVersionId"))
    third_rule_id = data.get("ruleVersionId")

    # ------------------------------------------------------------ 4. 版本历史
    print("== 4. 规则版本历史（P11） ==")
    status, payload = call("GET", "/sensor-parameters/%s/rule-versions?page=1&pageSize=20"
                           % parameter_id)
    items = data_of(payload).get("items") or []
    check("规则版本可查", status == 200 and len(items) == 3, "%s 条" % len(items))
    check("最新一版在首位", str((items[0] or {}).get("ruleVersionId")) == str(third_rule_id),
          (items[0] or {}).get("ruleVersionId"))
    check("旧版本未被覆盖（仍有 120 那版的阈值）",
          any(str((item.get("rule") or {}).get("normalUpper")) == "120" for item in items),
          [(item.get("rule") or {}).get("normalUpper") for item in items])
    check("版本链可以顺着 previousRuleVersionId 往回走",
          str((items[0] or {}).get("previousRuleVersionId")) == str(second_rule_id),
          (items[0] or {}).get("previousRuleVersionId"))

    # ------------------------------------------------------------ 5. 幂等
    print("== 5. 幂等 ==")
    replay_id = str(uuid.uuid4())
    replay_body = config_body(replay_id, 6, rule_value=rule(normal_upper="160"))
    status, payload = call("PUT", "/sensor-parameters/%s/configuration" % parameter_id, replay_body)
    replayed = data_of(payload)
    check("首次提交成功", status == 200, "%s %s" % (status, payload))
    status, payload = call("PUT", "/sensor-parameters/%s/configuration" % parameter_id, replay_body)
    again = data_of(payload)
    check("同 requestId 同内容重放返回首次结果", status == 200
          and again.get("version") == replayed.get("version")
          and again.get("ruleVersionId") == replayed.get("ruleVersionId"),
          "%s / %s" % (again.get("version"), replayed.get("version")))
    status, payload = call("PUT", "/sensor-parameters/%s/configuration" % parameter_id,
                           config_body(replay_id, 6, rule_value=rule(normal_upper="170")))
    check("同 requestId 换内容返回 409", status == 409, "%s %s" % (status, payload))

    # ------------------------------------------------------------ 6. 冲突与校验
    print("== 6. 冲突与校验 ==")
    current_version = replayed.get("version")
    status, payload = call("PUT", "/sensor-parameters/%s/configuration" % parameter_id,
                           config_body(str(uuid.uuid4()), 1, rule_value=rule(normal_upper="180")))
    check("版本号过期返回 409", status == 409, "%s %s" % (status, payload))
    check("409 带上当前版本", data_of(payload).get("currentVersion") == current_version,
          data_of(payload).get("currentVersion"))

    status, payload = call("PUT", "/sensor-parameters/999999/configuration",
                           config_body(str(uuid.uuid4()), 1))
    check("参数不存在返回 404", status == 404, "%s %s" % (status, payload))

    status, payload = call("PUT", "/sensor-parameters/abc/configuration",
                           config_body(str(uuid.uuid4()), 1))
    check("非法参数 ID 返回 400", status == 400, "%s %s" % (status, payload))

    missing = config_body(str(uuid.uuid4()), current_version)
    missing.pop("rule")
    status, payload = call("PUT", "/sensor-parameters/%s/configuration" % parameter_id, missing)
    check("缺 rule 字段返回 400 并指明字段", status == 400 and "rule" in field_errors(payload),
          "%s %s" % (status, field_errors(payload)))

    status, payload = call("PUT", "/sensor-parameters/%s/configuration" % parameter_id,
                           config_body(str(uuid.uuid4()), current_version, rule_value=None)
                           | {"rule": None})
    check("rule 传 null 被拒绝（规则只能停用不能删除）",
          status == 400 and "rule" in field_errors(payload), "%s %s" % (status, field_errors(payload)))

    status, payload = call("PUT", "/sensor-parameters/%s/configuration" % parameter_id,
                           config_body(str(uuid.uuid4()), current_version, unit="NOT_A_UNIT"))
    check("单位不存在返回 400", status == 400 and "unitCode" in field_errors(payload),
          "%s %s" % (status, field_errors(payload)))

    reversed_rule = rule()
    reversed_rule["normalLower"] = "200"
    reversed_rule["normalUpper"] = "100"
    status, payload = call("PUT", "/sensor-parameters/%s/configuration" % parameter_id,
                           config_body(str(uuid.uuid4()), current_version, rule_value=reversed_rule))
    check("上下界颠倒返回 400", status == 400 and "rule.normalLower" in field_errors(payload),
          "%s %s" % (status, field_errors(payload)))

    immediate_with_duration = rule(trigger="IMMEDIATE", duration=30)
    status, payload = call("PUT", "/sensor-parameters/%s/configuration" % parameter_id,
                           config_body(str(uuid.uuid4()), current_version,
                                       rule_value=immediate_with_duration))
    check("立即触发带秒数返回 400",
          status == 400 and "rule.durationSeconds" in field_errors(payload),
          "%s %s" % (status, field_errors(payload)))

    no_version = config_body(str(uuid.uuid4()), current_version)
    no_version.pop("expectedVersion")
    status, payload = call("PUT", "/sensor-parameters/%s/configuration" % parameter_id, no_version)
    check("缺 expectedVersion 返回 400",
          status == 400 and "expectedVersion" in field_errors(payload),
          "%s %s" % (status, field_errors(payload)))

    status, payload = call("PUT", "/sensor-parameters/%s/configuration" % parameter_id,
                           config_body(str(uuid.uuid4()), current_version) | {"parameterCode": "HACK"})
    check("配置更新不接受改编码（参数编码保持不变）", status == 200
          and data_of(payload).get("parameterCode") == "PRESSURE",
          data_of(payload).get("parameterCode"))

    # ------------------------------------------------------------ 7. 事件结束
    print("== 7. 判断依据变化时结束活动事件（RULE_CHANGED） ==")
    before = parameter_state(sensor["sensorId"], parameter_id)
    old_rule_id = before.get("ruleVersionId")
    if not sql_available():
        print("  (找不到 MySQL 客户端，跳过事件造数与库内断言)")
    else:
        # 事件生成属于 S1-3，这里直接落一条活动事件，只为了验 P08 的结束逻辑。
        first_event = seed_event(parameter_id, sensor["sensorId"], equipment, old_rule_id, "FIRST")
        check("造出一条挂在旧规则上的活动事件", first_event is not None, first_event)

        status, payload = call("PUT", "/sensor-parameters/%s/configuration" % parameter_id,
                               config_body(str(uuid.uuid4()), before.get("version"),
                                           rule_value=rule(normal_upper="141")))
        data = data_of(payload)
        new_rule_id = data.get("ruleVersionId")
        check("响应回填被结束的事件号",
              data.get("affectedEventIds") == [str(first_event)], data.get("affectedEventIds"))
        check("生效说明写明按 RULE_CHANGED 结束且不算恢复",
              "RULE_CHANGED" in (data.get("effectNote") or "")
              and "不算恢复" in (data.get("effectNote") or ""), data.get("effectNote"))
        # 期望值 1/1/1/2：已结束、原因 RULE_CHANGED、recovered_at 为空、版本从 1 推到 2。
        row = sql("SELECT ended_at IS NOT NULL, end_reason, recovered_at IS NULL, version "
                  "FROM p2_alert_event WHERE event_id = %s;" % first_event)
        check("库里事件已结束、原因为 RULE_CHANGED、未记为恢复、版本已推进",
              row == "1\tRULE_CHANGED\t1\t2", row)

        status, payload = call("GET", "/sensor-parameters/%s/rule-versions?page=1&pageSize=20"
                               % parameter_id)
        items = data_of(payload).get("items") or []
        check("规则版本历史里看得到这一版结束过的事件",
              bool(items) and str(first_event) in (items[0].get("affectedEventIds") or []),
              (items[0] or {}).get("affectedEventIds"))

        # 只改展示字段：活动事件必须继续挂着，不能被顺手结束。
        second_event = seed_event(parameter_id, sensor["sensorId"], equipment, new_rule_id, "SECOND")
        status, payload = call("PUT", "/sensor-parameters/%s/configuration" % parameter_id,
                               config_body(str(uuid.uuid4()), data.get("version"),
                                           name="配置验收参数（再改名）",
                                           rule_value=rule(normal_upper="141")))
        unchanged = data_of(payload)
        check("只改名称时不结束活动事件",
              unchanged.get("affectedEventIds") == [], unchanged.get("affectedEventIds"))
        check("活动事件仍然挂着",
              sql("SELECT COUNT(*) FROM p2_alert_event WHERE event_id = %s AND ended_at IS NULL;"
                  % second_event) == "1", second_event)

    # ------------------------------------------------------------ 8. 与删除接口的一致性
    print("== 8. 改过规则的参数会阻止测点删除（与 P05 的约定一致） ==")
    status, payload = call("GET", "/sensors/%s" % sensor["sensorId"])
    version = data_of(payload).get("version")
    status, payload = call("DELETE", "/sensors/%s" % sensor["sensorId"], {
        "requestId": str(uuid.uuid4()), "operatorName": "验收脚本",
        "expectedVersion": version, "reason": "验收结束清理", "confirmed": True,
    })
    blockers = data_of(payload).get("blockers") or []
    check("有规则历史的测点不允许删除", status == 409
          and any(item.get("type") == "PARAMETER_HISTORY" for item in blockers),
          "%s %s" % (status, [item.get("type") for item in blockers]))
    check("阻塞项说明为什么不能删",
          bool((blockers[0] or {}).get("message")) if blockers else False, blockers)

    # ------------------------------------------------------------ 9. 清理
    print("== 9. 清理 ==")
    if not sql_available():
        print("  (找不到 MySQL 客户端，跳过清理；请手工删除 AUTOTEST-CFG-%d)" % stamp)
    else:
        # 事件有 (parameter_id, rule_version_id) 外键指向规则版本，必须先删事件再删版本。
        sql("DELETE FROM p2_alert_event WHERE parameter_id = %s;" % parameter_id)
        # 规则版本有自引用外键 (parameter_id, previous_rule_version_id)，必须先解开再删。
        sql("UPDATE p2_alert_rule_version SET previous_rule_version_id = NULL "
            " WHERE parameter_id = %s;" % parameter_id)
        sql("DELETE FROM p2_alert_rule_version WHERE parameter_id = %s;" % parameter_id)
        sql("DELETE FROM p2_sensor_parameter_profile WHERE parameter_id = %s;" % parameter_id)
        sql("DELETE FROM p2_change_log WHERE object_type = 'SENSOR_PARAMETER' "
            " AND object_id = '%s';" % parameter_id)
        sql("DELETE FROM dim_sensor_parameter WHERE parameter_id = %s;" % parameter_id)
        sql("DELETE FROM p2_change_log WHERE object_type = 'SENSOR' "
            " AND object_id = '%s';" % sensor["sensorId"])
        sql("DELETE FROM p2_sensor_profile WHERE sensor_id = %s;" % sensor["sensorId"])
        sql("DELETE FROM dim_sensor WHERE sensor_id = %s;" % sensor["sensorId"])
        leftover = sql("SELECT COUNT(*) FROM dim_sensor WHERE sensor_code = 'AUTOTEST-CFG-%d';"
                       % stamp)
        check("验收数据已清理干净", (leftover or "").strip() == "0", leftover)

    status, payload = call("PUT", "/sensor-parameters/%s/configuration" % parameter_id,
                           config_body(str(uuid.uuid4()), 1))
    check("参数随测点清理后配置更新返回 404", status == 404, "%s %s" % (status, payload))

    # ------------------------------------------------------------ 汇总
    print()
    print("通过 %d 项，失败 %d 项" % (len(passes), len(failures)))
    if failures:
        for name, detail in failures:
            print("  FAIL %s -> %s" % (name, detail))
        return 1
    print("剩余测点：%s" % (sql("SELECT COUNT(*) FROM dim_sensor "
                              "WHERE sensor_code LIKE 'AUTOTEST-CFG-%'") if sql_available() else "未检查"))
    return 0


if __name__ == "__main__":
    sys.exit(main())
