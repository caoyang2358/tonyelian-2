# -*- coding: utf-8 -*-
"""补全 8 台设备的投用日期、预计使用年限、质保到期日（演示数据）。

口径与 docs/数据来源说明.md 第 6 节「2026-11-17 补全设备档案」一致：
- 车间1 设备投用 2023 年、车间2 投用 2024 年，购置→投用间隔 3~4 个月；
- 质保到期日 = 投用日期 + 24 个月 - 1 天（全部设计为已过期）；
- 预计使用年限：熔炼/保温 15、连铸/轧制 12、拉丝 10、测径仪 8；
- 全部走 PUT /api/v1/equipment/{id}（S1-E04）正式更正，留变更记录。

用法：
    python scripts/seed_equipment_profile_dates.py           # 填充
    python scripts/seed_equipment_profile_dates.py --revert  # 逐台清空三个日期字段（同样留痕）

回滚原理：每次更正都会写 p2_change_log（UPDATE，vN → vN+1），
--revert 就是把三个字段置空再走一次更正，不是删数据。
"""
import argparse
import json
import sys
import urllib.request
import urllib.error
import uuid
from datetime import date

BASE = "http://127.0.0.1:18280/api/v1"
OPERATOR = "系统管理员"
REASON_FILL = "按 2026-11-17 会议纪要补全设备档案（演示数据，非现场核验）"
REASON_REVERT = "回滚 2026-11-17 档案演示数据填充（清空投用/年限/质保三个日期字段）"

# equipment_id: (购置日期, 投用日期, 质保到期日, 预计使用年限)
# 质保 = 投用 + 24 个月 - 1 天；CAST-01 与文档示例完全一致
DEVICES = {
    1: ("2023-03-06", "2023-06-15", "2025-06-14", 15),  # SMELT-FUR-01 熔化炉（熔炼）
    2: ("2023-03-20", "2023-06-28", "2025-06-27", 15),  # HOLD-FUR-01 保温炉（保温）
    3: ("2023-04-10", "2023-07-05", "2025-07-04", 12),  # CAST-01 结晶牵引机（连铸，文档示例）
    4: ("2023-05-12", "2023-08-20", "2025-08-19", 12),  # ROLL-01 轧机组（轧制）
    5: ("2024-02-08", "2024-05-16", "2026-05-15", 10),  # DRAW-01 拉丝机
    6: ("2024-02-22", "2024-06-05", "2026-06-04", 10),  # DRAW-02 拉丝机
    7: ("2024-03-06", "2024-06-18", "2026-06-17", 10),  # DRAW-03 拉丝机
    8: ("2024-03-18", "2024-07-02", "2026-07-01", 8),   # GAUGE-02 测径仪
}

DATE_FIELDS = ("commissionedOn", "warrantyExpiresOn")
LIFE_FIELD = "expectedLifeYears"


def api(method: str, path: str, body: dict | None = None) -> dict:
    req = urllib.request.Request(
        BASE + path,
        method=method,
        headers={"Content-Type": "application/json"},
        data=json.dumps(body).encode("utf-8") if body is not None else None,
    )
    try:
        with urllib.request.urlopen(req, timeout=30) as resp:
            payload = json.loads(resp.read().decode("utf-8"))
    except urllib.error.HTTPError as e:
        print(f"  HTTP {e.code}: {e.read().decode('utf-8', 'replace')[:300]}")
        raise
    if payload.get("code") not in ("OK", "0000"):
        raise RuntimeError(f"{method} {path} 返回异常: {payload.get('code')} {payload.get('message')}")
    return payload["data"]


def fill_one(equipment_id: int, dates: tuple, revert: bool) -> str:
    detail = api("GET", f"/equipment/{equipment_id}")
    profile = detail["profile"]
    version = detail["version"]
    code = detail["code"]

    # 幂等：目标值已在档（且购置日期一致）时跳过，避免重复更正刷版本号。
    target = {
        "purchasedOn": None if revert else dates[0],
        "commissionedOn": None if revert else dates[1],
        "warrantyExpiresOn": None if revert else dates[2],
        "expectedLifeYears": None if revert else dates[3],
    }
    current = {k: profile.get(k) for k in target}
    if current == target:
        return f"{code} {detail['name']}: 已是目标值，跳过（v{version}）"

    body = {
        "requestId": f"seed-{code}-{'rv' if revert else 'fl'}-{uuid.uuid4().hex[:12]}",
        "operatorName": OPERATOR,
        "expectedVersion": version,
        "reason": REASON_REVERT if revert else REASON_FILL,
        "code": detail["code"],
        "name": detail["name"],
        "workshopId": detail["workshopId"],
        "responsiblePerson": detail["responsiblePerson"],
        "enabled": detail["enabled"],
        "profile": {
            "typeCode": profile["typeCode"],
            "model": profile["model"],
            "processId": profile["processId"],
            "installationLocation": profile["installationLocation"],
            "manufacturer": profile["manufacturer"],
            "factorySerialNo": profile["factorySerialNo"],
            "purchasedOn": None if revert else dates[0],
            "commissionedOn": None if revert else dates[1],
            "expectedLifeYears": None if revert else dates[3],
            "warrantyExpiresOn": None if revert else dates[2],
            "managementStatus": profile["managementStatus"],
            "technicalParameters": profile["technicalParameters"],
        },
    }
    result = api("PUT", f"/equipment/{equipment_id}", body)
    p = result["profile"]
    action = "清空" if revert else "填充"
    return (f"{code} {detail['name']}: v{version}→v{result['version']} {action} "
            f"投用={p['commissionedOn']} 年限={p['expectedLifeYears']} 质保={p['warrantyExpiresOn']}")


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--revert", action="store_true", help="清空三个日期字段（留痕回滚）")
    args = parser.parse_args()

    print(f"== {('回滚' if args.revert else '填充')} 8 台设备档案日期（{BASE}）==")
    failed = 0
    for equipment_id, dates in DEVICES.items():
        try:
            print(" ", fill_one(equipment_id, dates, args.revert))
        except Exception as e:  # noqa: BLE001
            failed += 1
            print(f"  设备 {equipment_id} 失败: {e}")
    print(f"== 完成，失败 {failed} 台 ==")
    return 1 if failed else 0


if __name__ == "__main__":
    sys.exit(main())
