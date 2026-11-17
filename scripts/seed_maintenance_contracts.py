"""设备维保合同的初始化数据（S1-C03 写入路径）。

用途：设备列表的"维保到期"列与合同页需要真实的合同数据才能看出四种到期状态，
本脚本通过正式接口（POST /api/v1/maintenance-contracts）建档，因此每一步都留下
p2_change_log 与 p2_request_result 记录，和页面手工录入完全等价。

前置条件：后端在 127.0.0.1:18280 运行，数据库已初始化。

    python scripts/seed_maintenance_contracts.py            # 建档（重复执行无副作用）
    python scripts/seed_maintenance_contracts.py --show     # 只看当前结果

数据口径：
    · 到期状态由结束日期与 p2_reminder_setting.maintenance_lead_days（当前 45 天）
      计算，不写死。
    · 一件设备挂多份合同时，设备列表取最早结束日（预警位口径）。1#多道轧机组
      同时挂年度维保与轧辊专项，用来验证"取最早"这条规则。
    · 2#测径仪只建档、不填结束日期，对应"日期待补"的 UNKNOWN 状态。

清理（按编号删除，同时清掉关联与留痕）：

    DELETE ce FROM p2_contract_equipment ce
      JOIN p2_maintenance_contract c ON c.contract_id = ce.contract_id
     WHERE c.contract_number LIKE 'WB-2026-%' OR c.contract_number = 'WB-2025-042';
    DELETE FROM p2_maintenance_contract
     WHERE contract_number LIKE 'WB-2026-%' OR contract_number = 'WB-2025-042';
    DELETE FROM p2_change_log
     WHERE object_type = 'MAINTENANCE_CONTRACT' AND object_id NOT IN
           (SELECT contract_id FROM p2_maintenance_contract);
"""

import json
import sys
import urllib.error
import urllib.request

BASE = "http://127.0.0.1:18280/api/v1"
OPERATOR = "设备管理组"

# 设备编号对照（dim_equipment）：1 熔化炉、2 保温炉、3 结晶牵引机、4 多道轧机组、
# 5 1#拉丝机、6 2#拉丝机、7 3#拉丝机、8 2#测径仪。
CONTRACTS = [
    {
        "requestId": "seed-contract-wb-2026-011",
        "serviceOrganization": "江苏中铜炉业工程技术有限公司",
        "contractNumber": "WB-2026-011",
        "startDate": "2026-07-01",
        "endDate": "2027-06-30",
        "remark": "炉窑系统年度维保，含 4 次计划检修与炉衬测厚；故障响应 24 小时内到场。",
        "equipmentIds": [1, 2],
    },
    {
        "requestId": "seed-contract-wb-2026-018",
        "serviceOrganization": "无锡金瑞冶金装备服务有限公司",
        "contractNumber": "WB-2026-018",
        "startDate": "2026-04-01",
        "endDate": "2027-03-31",
        "remark": "连铸连轧主线年度维保，含结晶器铜管更换与轧机牌坊精度校准。",
        "equipmentIds": [3, 4],
    },
    {
        "requestId": "seed-contract-wb-2026-027",
        "serviceOrganization": "常州恒力精工机电有限公司",
        "contractNumber": "WB-2026-027",
        "startDate": "2026-06-01",
        "endDate": "2026-11-28",
        "remark": "轧辊专项服务：辊面修磨与轴承座检测，按季度结算。",
        "equipmentIds": [4],
    },
    {
        "requestId": "seed-contract-wb-2025-042",
        "serviceOrganization": "苏州铜材装备维保有限公司",
        "contractNumber": "WB-2025-042",
        "startDate": "2025-11-01",
        "endDate": "2026-10-31",
        "remark": "拉丝设备年度维保，含塔轮检查与收线张力标定；续签手续办理中。",
        "equipmentIds": [5, 6, 7],
    },
    {
        "requestId": "seed-contract-wb-2026-001",
        "serviceOrganization": "苏州铜材装备维保有限公司",
        "contractNumber": "WB-2026-001",
        "startDate": "2026-01-15",
        "endDate": None,
        "remark": "测径仪维保，合同期限待商务确认后补录。",
        "equipmentIds": [8],
    },
]


def call(method, url, payload=None):
    data = None
    headers = {}
    if payload is not None:
        data = json.dumps(payload, ensure_ascii=False).encode("utf-8")
        headers["Content-Type"] = "application/json"
    request = urllib.request.Request(url, data=data, headers=headers, method=method)
    opener = urllib.request.build_opener(urllib.request.ProxyHandler({}))
    try:
        with opener.open(request, timeout=20) as response:
            return response.status, json.loads(response.read().decode("utf-8"))
    except urllib.error.HTTPError as error:
        return error.code, json.loads(error.read().decode("utf-8"))


def existing_numbers():
    status, body = call("GET", BASE + "/maintenance-contracts?pageSize=100")
    if status != 200:
        return set()
    rows = (body.get("data") or {}).get("items") or []
    return {row.get("contractNumber") for row in rows}


def create(contract):
    payload = dict(contract)
    payload["operatorName"] = OPERATOR
    payload["reason"] = "按年度维保台账初始化合同数据"
    status, body = call("POST", BASE + "/maintenance-contracts", payload)
    if status in (200, 201):
        data = body.get("data") or {}
        print("  OK   %s  %s  设备 %s  状态 %s"
              % (payload["contractNumber"], data.get("serviceOrganization", ""),
                 ",".join(data.get("equipmentIds") or []), data.get("expiryState")))
    elif status == 409:
        print("  跳过 %s  请求编号已被处理过" % payload["contractNumber"])
    else:
        print("  失败 %s  HTTP %s  %s" % (payload["contractNumber"], status, body.get("message")))
    return status


def show():
    status, body = call("GET", BASE + "/maintenance-contracts?pageSize=50")
    if status != 200:
        print("读取合同失败：HTTP %s" % status)
        return
    rows = (body.get("data") or {}).get("items") or []
    print("合同共 %d 份" % len(rows))
    for row in rows:
        print("  %-12s %-28s %s ~ %-10s 设备 %s  %s 剩 %s 天"
              % (row.get("contractNumber"), row.get("serviceOrganization"),
                 row.get("startDate") or "—", row.get("endDate") or "—",
                 row.get("equipmentCount"), row.get("expiryState"),
                 row.get("remainingDays") if row.get("remainingDays") is not None else "—"))

    status, body = call("GET", BASE + "/equipment")
    if status != 200:
        print("读取设备失败：HTTP %s" % status)
        return
    items = (body.get("data") or {}).get("items") or []
    print("\n设备列表维保到期列")
    for item in items:
        print("  %-12s %-12s %-10s 剩 %s 天  %s"
              % (item.get("code"), item.get("name"),
                 item.get("maintenanceDueDate") or "待补",
                 item.get("maintenanceRemainingDays")
                 if item.get("maintenanceRemainingDays") is not None else "—",
                 item.get("maintenanceExpiryState")))


def main():
    if "--show" in sys.argv:
        show()
        return 0
    already = existing_numbers()
    print("通过接口建档 %d 份合同：" % len(CONTRACTS))
    failures = 0
    skipped = 0
    for contract in CONTRACTS:
        # 编号已在库里就跳过：写接口的幂等只对同一个 requestId 生效，
        # 换一个 requestId 再跑会重复建档，这里先用编号挡住。
        if contract["contractNumber"] in already:
            print("  跳过 %s  库中已有该编号的合同" % contract["contractNumber"])
            skipped += 1
            continue
        if create(contract) not in (200, 201, 409):
            failures += 1
    print("\n新建 %d 份，跳过 %d 份" % (len(CONTRACTS) - skipped - failures, skipped))
    print()
    show()
    return 1 if failures else 0


if __name__ == "__main__":
    sys.exit(main())
