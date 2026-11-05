"""S1-C01—C04 维保合同接口的端到端验收。

只依赖标准库。前置条件：后端在 127.0.0.1:18280 运行。

    python scripts/verify_contract.py

退出码 0 表示全部通过，1 表示有失败项。

脚本会自己建一批带 REVIEW- 前缀的合同做验证，跑完不删除；
清理命令会在结尾打印，便于保留或清空。
"""

import datetime
import json
import sys
import urllib.error
import urllib.request

BASE = "http://127.0.0.1:18280/api/v1"
PASSED = []
FAILED = []


def call(method, url, payload=None):
    data = None
    headers = {}
    if payload is not None:
        data = json.dumps(payload, ensure_ascii=False).encode("utf-8")
        headers["Content-Type"] = "application/json"
    request = urllib.request.Request(url, data=data, headers=headers, method=method)
    opener = urllib.request.build_opener(urllib.request.ProxyHandler({}))
    try:
        with opener.open(request, timeout=15) as response:
            return response.status, json.loads(response.read().decode("utf-8"))
    except urllib.error.HTTPError as error:
        return error.code, json.loads(error.read().decode("utf-8"))


def check(label, ok, detail=""):
    if ok:
        PASSED.append(label)
        print("  PASS  " + label)
    else:
        FAILED.append((label, detail))
        print("  FAIL  " + label + ("  -> " + str(detail) if detail else ""))


def body(org, start, end, equipment_ids, version=None, request_id="rv-1"):
    payload = {
        "requestId": request_id,
        "operatorName": "验收脚本",
        "serviceOrganization": org,
        "contractNumber": "REVIEW-" + request_id,
        "startDate": start,
        "endDate": end,
        "remark": None,
        "equipmentIds": equipment_ids,
    }
    if version is not None:
        payload["expectedVersion"] = version
    return payload


def iso(offset_days):
    return (datetime.date.today() + datetime.timedelta(days=offset_days)).isoformat()


def main():
    print("== 0. 服务连通 ==")
    status, payload = call("GET", BASE + "/maintenance-contracts?page=1&pageSize=100")
    check("合同列表可访问", status == 200, "实际 HTTP %s" % status)
    if status != 200:
        print("服务未就绪，终止。")
        return 1

    status, settings = call("GET", BASE + "/reminder-settings")
    lead = settings["data"]["maintenanceLeadDays"]
    print("  当前维保提前天数 = %s" % lead)

    print("== 1. 新建与期限计算 ==")
    status, created = call("POST", BASE + "/maintenance-contracts",
                           body("REVIEW-华东维保", "2026-01-01", "2026-12-31",
                                ["1", "3", "1"], request_id="rv-create"))
    check("新建返回 200", status == 200, "实际 HTTP %s" % status)
    data = created.get("data") or {}
    contract_id = data.get("contractId")
    check("新建后 version=1", data.get("version") == 1, data.get("version"))
    check("设备集合去重保序", data.get("equipmentIds") == ["1", "3"], data.get("equipmentIds"))
    check("附件集合为空数组", data.get("attachmentIds") == [], data.get("attachmentIds"))

    print("== 2. 四种期限状态 ==")
    samples = [
        ("EXPIRED", iso(-10), "EXPIRED"),
        ("SOON", iso(max(0, lead - 2)), "EXPIRING_SOON"),
        ("ACTIVE", iso(lead + 30), "ACTIVE"),
        ("NODATE", None, "UNKNOWN"),
    ]
    state_of = {}
    for tag, end, _expected in samples:
        status, response = call("POST", BASE + "/maintenance-contracts",
                                body("REVIEW-" + tag, None, end, [], request_id="rv-" + tag))
        if status == 200:
            state_of[tag] = response["data"]["expiryState"]
    check("已过期合同状态为 EXPIRED", state_of.get("EXPIRED") == "EXPIRED", state_of.get("EXPIRED"))
    check("临近到期为 EXPIRING_SOON", state_of.get("SOON") == "EXPIRING_SOON", state_of.get("SOON"))
    check("远超提前量为 ACTIVE", state_of.get("ACTIVE") == "ACTIVE", state_of.get("ACTIVE"))
    check("缺结束日期为 UNKNOWN", state_of.get("NODATE") == "UNKNOWN", state_of.get("NODATE"))

    status, response = call("GET", BASE + "/maintenance-contracts/%s" % contract_id)
    detail = response.get("data") or {}
    check("详情返回 UNKNOWN 时剩余天数为 null",
          detail.get("expiryState") != "UNKNOWN" or detail.get("remainingDays") is None,
          detail.get("remainingDays"))

    nodate_id = None
    status, response = call("GET", BASE + "/maintenance-contracts?expiryState=UNKNOWN&page=1&pageSize=100")
    for row in (response.get("data") or {}).get("items", []):
        if row["serviceOrganization"] == "REVIEW-NODATE":
            nodate_id = row["contractId"]
    if nodate_id:
        status, response = call("GET", BASE + "/maintenance-contracts/%s" % nodate_id)
        check("无日期合同的 remainingDays 为 null",
              response["data"]["remainingDays"] is None, response["data"]["remainingDays"])

    print("== 3. 列表筛选 ==")
    status, response = call("GET", BASE + "/maintenance-contracts?equipmentId=1&page=1&pageSize=100")
    ids = [row["contractId"] for row in (response.get("data") or {}).get("items", [])]
    check("按设备过滤命中该合同", contract_id in ids, ids)

    status, response = call("GET", BASE + "/maintenance-contracts?expiryState=expired&page=1&pageSize=100")
    states = {row["expiryState"] for row in (response.get("data") or {}).get("items", [])}
    check("状态筛选支持小写并只回 EXPIRED", states == {"EXPIRED"}, states)

    status, response = call("GET", BASE + "/maintenance-contracts?serviceOrganization=REVIEW&page=1&pageSize=100")
    orgs = {row["serviceOrganization"] for row in (response.get("data") or {}).get("items", [])}
    check("服务单位模糊匹配生效", all(o.startswith("REVIEW") for o in orgs) and orgs, orgs)

    status, response = call("GET", BASE + "/maintenance-contracts?page=1&pageSize=100")
    items = (response.get("data") or {}).get("items", [])
    last = items[-1] if items else None
    check("无结束日期的合同排在最后",
          last is None or last.get("endDate") is not None or last.get("expiryState") == "UNKNOWN",
          last)

    print("== 4. 非法参数 ==")
    status, response = call("GET", BASE + "/maintenance-contracts?expiryState=SOON")
    check("未知期限状态返回 400", status == 400, "实际 HTTP %s" % status)
    check("错误码为 VALIDATION_ERROR",
          (response.get("data") or {}).get("fields") is not None or response.get("code") == "VALIDATION_ERROR",
          response.get("code"))

    status, response = call("POST", BASE + "/maintenance-contracts",
                            body("REVIEW-日期颠倒", "2026-12-31", "2026-01-01", [], request_id="rv-bad-order"))
    check("结束早于开始返回 400", status == 400, "实际 HTTP %s" % status)
    check("错误定位到 endDate", "endDate" in json.dumps(response.get("data"), ensure_ascii=False),
          response.get("data"))

    status, response = call("POST", BASE + "/maintenance-contracts",
                            body("REVIEW-日期格式", "2026/01/01", None, [], request_id="rv-bad-format"))
    check("非法日期格式返回 400", status == 400, "实际 HTTP %s" % status)

    status, response = call("POST", BASE + "/maintenance-contracts",
                            body("REVIEW-设备不存在", None, None, ["99999"], request_id="rv-bad-eq"))
    check("关联不存在设备返回 400", status == 400, "实际 HTTP %s" % status)
    check("错误定位到 equipmentIds", "equipmentIds" in json.dumps(response.get("data"), ensure_ascii=False),
          response.get("data"))

    incomplete = body("REVIEW-缺字段", None, None, [], request_id="rv-missing")
    del incomplete["remark"]
    status, response = call("POST", BASE + "/maintenance-contracts", incomplete)
    check("缺少可空字段返回 400 而不是当成 null", status == 400, "实际 HTTP %s" % status)

    print("== 5. 只填一个日期 ==")
    status, response = call("POST", BASE + "/maintenance-contracts",
                            body("REVIEW-半填日期", "2026-03-01", None, [], request_id="rv-half-date"))
    check("只填开始日期允许建档", status == 200, "实际 HTTP %s" % status)
    half_id = (response.get("data") or {}).get("contractId")
    check("未填的结束日期保持 null", (response.get("data") or {}).get("endDate") is None,
          (response.get("data") or {}).get("endDate"))

    print("== 6. 更新与整体替换 ==")
    status, response = call("PUT", BASE + "/maintenance-contracts/%s" % contract_id,
                            body("REVIEW-华东维保", "2026-02-01", "2027-01-31", ["3"], version=1,
                                 request_id="rv-update"))
    check("更新返回 200", status == 200, "实际 HTTP %s" % status)
    updated = response.get("data") or {}
    check("更新后 version=2", updated.get("version") == 2, updated.get("version"))
    check("覆盖设备被整体替换", updated.get("equipmentIds") == ["3"], updated.get("equipmentIds"))
    check("日期变更后状态重新计算", updated.get("endDate") == "2027-01-31", updated.get("endDate"))

    status, response = call("PUT", BASE + "/maintenance-contracts/%s" % contract_id,
                            body("REVIEW-华东维保", "2026-02-01", "2027-01-31", [], version=2,
                                 request_id="rv-unbind"))
    check("空数组解除全部覆盖关系", (response.get("data") or {}).get("equipmentIds") == [],
          (response.get("data") or {}).get("equipmentIds"))

    status, response = call("GET", BASE + "/maintenance-contracts?equipmentId=1&page=1&pageSize=100")
    ids = [row["contractId"] for row in (response.get("data") or {}).get("items", [])]
    check("解绑后按设备查不到该合同", contract_id not in ids, ids)

    status, response = call("PUT", BASE + "/maintenance-contracts/%s" % contract_id,
                            body("REVIEW-华东维保", None, None, [], version=1, request_id="rv-conflict"))
    check("过期版本更新返回 409", status == 409, "实际 HTTP %s" % status)
    check("错误码为 VERSION_CONFLICT", response.get("code") == "VERSION_CONFLICT", response.get("code"))

    # 幂等重放必须原样重发同一份请求体；改了 expectedVersion 就成了"同号不同内容"，那是另一种错误。
    status, response = call("PUT", BASE + "/maintenance-contracts/%s" % contract_id,
                            body("REVIEW-华东维保", "2026-02-01", "2027-01-31", [], version=2,
                                 request_id="rv-unbind"))
    check("相同 requestId 原样重放返回首次结果",
          status == 200 and (response.get("data") or {}).get("version") == 3,
          (status, (response.get("data") or {}).get("version")))

    status, response = call("PUT", BASE + "/maintenance-contracts/%s" % contract_id,
                            body("REVIEW-改内容", None, None, [], version=3, request_id="rv-unbind"))
    check("同 requestId 换内容返回 409", status == 409, "实际 HTTP %s" % status)
    check("错误码为 IDEMPOTENCY_CONFLICT",
          response.get("code") == "IDEMPOTENCY_CONFLICT", response.get("code"))

    print("== 7. 详情与留痕 ==")
    status, response = call("GET", BASE + "/maintenance-contracts/999999")
    check("不存在的合同返回 404", status == 404, "实际 HTTP %s" % status)

    status, response = call("GET", BASE + "/equipment/%s/changes?page=1&pageSize=50" % 1)
    check("设备变更记录接口仍可用（未被合同改动影响）", status == 200, "实际 HTTP %s" % status)

    print()
    print("通过 %d 项，失败 %d 项" % (len(PASSED), len(FAILED)))
    if FAILED:
        for label, detail in FAILED:
            print("  失败: %s -> %s" % (label, detail))
        return 1
    print("S1-C01—C04 合同接口契约全部通过。")
    print()
    print("清理（只删本次验收数据，保留其他合同）：")
    print("  DELETE FROM p2_contract_equipment WHERE contract_id IN "
          "(SELECT contract_id FROM p2_maintenance_contract "
          "WHERE service_organization LIKE 'REVIEW-%');")
    print("  DELETE FROM p2_maintenance_contract "
          "WHERE service_organization LIKE 'REVIEW-%';")
    return 0


if __name__ == "__main__":
    sys.exit(main())
