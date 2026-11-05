"""S1-F01—F05 共用附件接口的端到端验收。

只依赖标准库。前置条件：后端在 127.0.0.1:18280 运行。

    python scripts/verify_attachment.py

退出码 0 表示全部通过，1 表示有失败项。

脚本刻意使用固定的 requestId 与固定文件内容：重复运行走幂等重放，不会在库里
堆出一串附件，也不会让磁盘目录越跑越大。附件没有删除接口（契约 F01—F05 未定义），
所以验收数据会保留在目标设备名下，文件名固定为 附件验收.png / 附件验收-替换.png。
"""

import base64
import json
import sys
import urllib.error
import urllib.request
import uuid

BASE = "http://127.0.0.1:18280/api/v1"
PASSED = []
FAILED = []

# 1x1 透明 PNG：合法图片，体积小，便于逐字节比对下载结果。
PNG = base64.b64decode(
    "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mP8z8BQDwAEhQGAhKmMIQAAAABJRU5ErkJggg=="
)
# 替换用的第二份内容：在合法 PNG 后追加字节，摘要必然不同。
PNG_REPLACED = PNG + b"\x00\x00"

UPLOAD_NAME = "附件验收.png"
REPLACED_NAME = "附件验收-替换.png"

# 固定编号 → 重复运行是重放，不会重复建附件。
REQ_UPLOAD = "att-verify-upload"
REQ_REPLACE = "att-verify-replace"


def send(method, url, body=None, content_type=None):
    headers = {}
    if content_type:
        headers["Content-Type"] = content_type
    request = urllib.request.Request(url, data=body, headers=headers, method=method)
    opener = urllib.request.build_opener(urllib.request.ProxyHandler({}))
    try:
        with opener.open(request, timeout=90) as response:
            return response.status, dict(response.headers), response.read()
    except urllib.error.HTTPError as error:
        return error.code, dict(error.headers), error.read()


def call(method, url, payload=None):
    body = None if payload is None else json.dumps(payload, ensure_ascii=False).encode("utf-8")
    status, _, raw = send(method, url, body, None if payload is None else "application/json")
    try:
        return status, json.loads(raw.decode("utf-8"))
    except (ValueError, UnicodeDecodeError):
        return status, {}


def multipart(fields, file_field, filename, media_type, content):
    boundary = "----copper" + uuid.uuid4().hex
    chunks = []
    for name, value in fields.items():
        chunks.append(
            ("--%s\r\nContent-Disposition: form-data; name=\"%s\"\r\n\r\n%s\r\n"
             % (boundary, name, value)).encode("utf-8")
        )
    chunks.append(
        ("--%s\r\nContent-Disposition: form-data; name=\"%s\"; filename=\"%s\"\r\n"
         "Content-Type: %s\r\n\r\n" % (boundary, file_field, filename, media_type)).encode("utf-8")
    )
    chunks.append(content)
    chunks.append(("\r\n--%s--\r\n" % boundary).encode("utf-8"))
    return b"".join(chunks), "multipart/form-data; boundary=" + boundary


def upload(owner_type, owner_id, file_kind, filename, media_type, content,
           request_id=REQ_UPLOAD, operator="验收脚本"):
    fields = {
        "requestId": request_id,
        "operatorName": operator,
        "ownerType": owner_type,
        "ownerId": str(owner_id),
        "fileKind": file_kind,
    }
    body, content_type = multipart(fields, "file", filename, media_type, content)
    status, _, raw = send("POST", BASE + "/attachments", body, content_type)
    return status, _json(raw)


def replace(attachment_id, expected_version, reason, filename, media_type, content,
            request_id=REQ_REPLACE):
    fields = {
        "requestId": request_id,
        "operatorName": "验收脚本",
        "expectedVersion": str(expected_version),
        "reason": reason,
    }
    body, content_type = multipart(fields, "file", filename, media_type, content)
    status, _, raw = send("PUT", BASE + "/attachments/%s/content" % attachment_id, body, content_type)
    return status, _json(raw)


def _json(raw):
    try:
        return json.loads(raw.decode("utf-8"))
    except (ValueError, UnicodeDecodeError):
        return {}


def check(label, ok, detail=""):
    if ok:
        PASSED.append(label)
        print("  PASS  " + label)
    else:
        FAILED.append((label, detail))
        print("  FAIL  " + label + ("  -> " + str(detail) if detail else ""))


def fields(payload):
    data = payload.get("data") or {}
    return data.get("fields") or {}


def main():
    print("== 0. 服务连通 ==")
    status, listing = call("GET", BASE + "/equipment?page=1&pageSize=100")
    check("设备列表可访问", status == 200, "实际 HTTP %s" % status)
    if status != 200:
        print("服务未就绪，终止。")
        return 1

    items = (listing.get("data") or {}).get("items") or []
    if not items:
        print("库里没有设备，无法做附件归属验证。")
        return 1
    equipment_id = items[0]["id"]
    print("  目标设备 id=%s  %s" % (equipment_id, items[0].get("name")))

    status, first = call("GET", BASE + "/attachments?ownerType=EQUIPMENT&ownerId=%s&page=1&pageSize=20"
                         % equipment_id)
    check("附件列表可访问", status == 200, "实际 HTTP %s" % status)

    # 脚本可重复运行：已有验收附件时走"重放"路径，此时上传返回的是首次结果（version=1），
    # 而当前记录可能已被替换过（version=2）。两者都正确，区别只在历史快照与当前状态。
    existing = next((row for row in (first.get("data") or {}).get("items") or []
                     if row.get("originalName") in (UPLOAD_NAME, REPLACED_NAME)), None)
    first_run = existing is None
    if first_run:
        print("  未发现历史验收附件，按首次运行校验。")
    else:
        print("  发现历史验收附件 id=%s，按重复运行校验幂等。" % existing.get("attachmentId"))

    print("== 1. 归属与分页校验 ==")
    status, payload = call("GET", BASE + "/attachments?ownerId=%s&page=1&pageSize=20" % equipment_id)
    check("缺 ownerType 返回 400", status == 400, "实际 HTTP %s" % status)
    check("并指出 ownerType 字段", "ownerType" in fields(payload), fields(payload))

    status, payload = call("GET", BASE + "/attachments?ownerType=WORKSHOP&ownerId=1&page=1&pageSize=20")
    check("非法 ownerType 返回 400", status == 400, "实际 HTTP %s" % status)
    check("提示只接受四种归属", "EQUIPMENT" in fields(payload).get("ownerType", ""), fields(payload))

    status, payload = call("GET", BASE + "/attachments?ownerType=EQUIPMENT&ownerId=1&fileKind=VIDEO")
    check("非法 fileKind 返回 400", status == 400, "实际 HTTP %s" % status)

    status, payload = call("GET", BASE + "/attachments?ownerType=EQUIPMENT&ownerId=1&pageSize=0")
    check("pageSize=0 返回 400", status == 400, "实际 HTTP %s" % status)

    status, payload = call("GET", BASE + "/attachments?ownerType=EQUIPMENT&ownerId=abc")
    check("归属 ID 非数字返回 400", status == 400, "实际 HTTP %s" % status)

    print("== 2. F03 上传 ==")
    status, payload = upload("EQUIPMENT", equipment_id, "IMAGE", UPLOAD_NAME, "image/png", PNG)
    check("上传返回 200", status == 200, "实际 HTTP %s / %s" % (status, payload.get("message")))
    data = payload.get("data") or {}
    attachment_id = data.get("attachmentId")
    check("返回附件 ID", bool(attachment_id), data)
    if first_run:
        check("新附件 version=1", data.get("version") == 1, data.get("version"))
    else:
        check("重复运行复用同一附件", str(attachment_id) == str(existing.get("attachmentId")),
              "%s vs %s" % (attachment_id, existing.get("attachmentId")))
    check("媒体类型为 image/png", data.get("mediaType") == "image/png", data.get("mediaType"))
    check("归属对象回显为设备", data.get("ownerType") == "EQUIPMENT" and str(data.get("ownerId")) == str(equipment_id), data)
    check("大小等于上传字节数", data.get("size") == len(PNG), data.get("size"))
    check("文件名保留原始名称", data.get("originalName") == UPLOAD_NAME, data.get("originalName"))
    check("返回受控查看地址", data.get("contentPath") == "/api/v1/attachments/%s/content" % attachment_id, data.get("contentPath"))
    keys = all_keys(data)
    check("响应不含服务器存储路径", "storageKey" not in keys and "storage_key" not in keys, keys)

    status, replay = upload("EQUIPMENT", equipment_id, "IMAGE", UPLOAD_NAME, "image/png", PNG)
    check("同编号同内容重放返回 200", status == 200, "实际 HTTP %s" % status)
    check("重放不新建附件", (replay.get("data") or {}).get("attachmentId") == attachment_id,
          (replay.get("data") or {}).get("attachmentId"))

    status, conflict = upload("EQUIPMENT", equipment_id, "IMAGE", "另一个名字.png", "image/png", PNG)
    check("同编号不同内容返回 409", status == 409, "实际 HTTP %s" % status)
    check("冲突码为幂等冲突", conflict.get("code") == "IDEMPOTENCY_CONFLICT", conflict.get("code"))

    status, payload = upload("EQUIPMENT", equipment_id, "IMAGE", "big.png", "image/png",
                             b"\x00" * (20 * 1024 * 1024 + 1), request_id="att-verify-big")
    check("超过 20 MB 返回 400", status == 400, "实际 HTTP %s" % status)
    check("提示超过上限", "上限" in fields(payload).get("file", ""), fields(payload))

    status, payload = upload("EQUIPMENT", equipment_id, "IMAGE", "manual.pdf",
                             "application/pdf", b"%PDF-1.4", request_id="att-verify-kind")
    check("图片位传 PDF 返回 400", status == 400, "实际 HTTP %s" % status)
    check("提示允许的类型", "image/" in fields(payload).get("file", ""), fields(payload))

    status, payload = upload("EQUIPMENT", 99999999, "IMAGE", UPLOAD_NAME, "image/png", PNG,
                             request_id="att-verify-missing-owner")
    check("归属对象不存在返回 404", status == 404, "实际 HTTP %s" % status)

    print("== 3. F01 列表 / F02 详情 ==")
    status, payload = call("GET", BASE + "/attachments?ownerType=EQUIPMENT&ownerId=%s&page=1&pageSize=100"
                           % equipment_id)
    rows = (payload.get("data") or {}).get("items") or []
    ids = [row.get("attachmentId") for row in rows]
    check("列表包含刚上传的附件", attachment_id in ids, ids)
    check("列表项不含存储路径", all("storageKey" not in row for row in rows), rows[:1])
    check("列表带出受控地址", all(row.get("contentPath") for row in rows), rows[:1])

    status, payload = call("GET", BASE + "/attachments?ownerType=EQUIPMENT&ownerId=%s&fileKind=MANUAL&page=1&pageSize=100"
                           % equipment_id)
    manual_ids = [row.get("attachmentId") for row in (payload.get("data") or {}).get("items") or []]
    check("按类别过滤不含图片", attachment_id not in manual_ids, manual_ids)

    status, payload = call("GET", BASE + "/attachments/%s" % attachment_id)
    detail = payload.get("data") or {}
    listed = next((row for row in rows if row.get("attachmentId") == attachment_id), None)
    check("详情返回 200", status == 200, "实际 HTTP %s" % status)
    check("详情与列表版本一致", listed is not None and detail.get("version") == listed.get("version"),
          "%s vs %s" % (detail.get("version"), (listed or {}).get("version")))
    check("详情给出下载地址", detail.get("downloadPath", "").endswith("?download=true"), detail.get("downloadPath"))

    status, payload = call("GET", BASE + "/attachments/99999999")
    check("不存在的附件返回 404", status == 404, "实际 HTTP %s" % status)

    print("== 4. F04 查看与下载 ==")
    status, headers, raw = send("GET", BASE + "/attachments/%s/content" % attachment_id)
    check("查看返回 200", status == 200, "实际 HTTP %s" % status)
    check("媒体类型正确", (headers.get("Content-Type") or "").startswith("image/png"), headers.get("Content-Type"))
    if first_run:
        check("内容与原文件逐字节一致", raw == PNG, "%s vs %s" % (len(raw), len(PNG)))
    else:
        # 重复运行时当前内容已是上一次替换的结果，用替换后的字节比对。
        check("内容为最近一次替换结果", raw == PNG_REPLACED, "%s vs %s" % (len(raw), len(PNG_REPLACED)))
    check("默认为内联查看", (headers.get("Content-Disposition") or "").startswith("inline"),
          headers.get("Content-Disposition"))
    check("带上版本缓存标识", bool(headers.get("ETag")), headers.get("ETag"))

    status, headers, raw = send("GET", BASE + "/attachments/%s/content?download=true" % attachment_id)
    check("下载返回 200", status == 200, "实际 HTTP %s" % status)
    check("下载使用 attachment 头", (headers.get("Content-Disposition") or "").startswith("attachment"),
          headers.get("Content-Disposition"))

    print("== 5. F05 替换 ==")
    status, payload = replace(attachment_id, 1, "  ", REPLACED_NAME, "image/png", PNG_REPLACED,
                              request_id="att-verify-reason")
    check("缺替换原因返回 400", status == 400, "实际 HTTP %s" % status)

    status, payload = replace(attachment_id, 99, "版本核对", REPLACED_NAME, "image/png", PNG_REPLACED,
                              request_id="att-verify-conflict")
    check("版本不符返回 409", status == 409, "实际 HTTP %s" % status)
    check("冲突码为版本冲突", payload.get("code") == "VERSION_CONFLICT", payload.get("code"))

    status, payload = replace(attachment_id, 1, "厂家补发新版手册", REPLACED_NAME, "image/png", PNG_REPLACED)
    check("替换返回 200", status == 200, "实际 HTTP %s / %s" % (status, payload.get("message")))
    data = payload.get("data") or {}
    check("替换保留附件 ID", data.get("attachmentId") == attachment_id, data.get("attachmentId"))
    check("替换后 version=2", data.get("version") == 2, data.get("version"))
    check("替换更新文件名", data.get("originalName") == REPLACED_NAME, data.get("originalName"))

    status, replay = replace(attachment_id, 1, "厂家补发新版手册", REPLACED_NAME, "image/png", PNG_REPLACED)
    check("同编号重放不重复升版", status == 200 and (replay.get("data") or {}).get("version") == 2,
          (replay.get("data") or {}).get("version"))

    status, payload = replace(attachment_id, 1, "用旧版本号再试", REPLACED_NAME, "image/png", PNG_REPLACED,
                              request_id="att-verify-stale")
    check("旧版本号再次替换返回 409", status == 409, "实际 HTTP %s" % status)

    status, headers, raw = send("GET", BASE + "/attachments/%s/content" % attachment_id)
    check("替换后内容已更新", raw == PNG_REPLACED, "%s vs %s" % (len(raw), len(PNG_REPLACED)))
    check("替换后 ETag 随版本变化", "-2" in (headers.get("ETag") or ""), headers.get("ETag"))

    print("== 6. 与设备删除检查联动 ==")
    status, payload = call("GET", BASE + "/equipment/%s" % equipment_id)
    version = (payload.get("data") or {}).get("version")
    status, payload = call("DELETE", BASE + "/equipment/%s" % equipment_id, {
        "requestId": "att-verify-delete",
        "operatorName": "验收脚本",
        "expectedVersion": version,
        "reason": "验证附件是否计入删除阻塞项",
        "confirmed": True,
    })
    blockers = (payload.get("data") or {}).get("blockers") or []
    kinds = [item.get("type") for item in blockers]
    check("有附件时设备不能删除", status == 409, "实际 HTTP %s" % status)
    check("阻塞项包含 ATTACHMENT", "ATTACHMENT" in kinds, kinds)

    print()
    print("通过 %d 项，失败 %d 项。" % (len(PASSED), len(FAILED)))
    if FAILED:
        for label, detail in FAILED:
            print("  - %s -> %s" % (label, detail))
        return 1
    print("验收附件保留在设备 %s 名下（%s、%s）。附件无删除接口，如需清空请由管理员处理。" %
          (equipment_id, UPLOAD_NAME, REPLACED_NAME))
    return 0


def all_keys(payload):
    """列出响应里出现过的键名，用于断言没有把内部字段漏出去。"""
    keys = set()

    def walk(value):
        if isinstance(value, dict):
            for key, item in value.items():
                keys.add(key)
                walk(item)
        elif isinstance(value, list):
            for item in value:
                walk(item)

    walk(payload)
    return keys


if __name__ == "__main__":
    sys.exit(main())
