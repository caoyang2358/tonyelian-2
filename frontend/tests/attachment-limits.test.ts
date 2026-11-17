import assert from 'node:assert/strict';
import test from 'node:test';
import {
  CONTRACT_ACCEPT, CONTRACT_FORMATS_TEXT, MAX_FILE_SIZE, acceptOf, formatsTextOf, localFileError,
} from '../src/features/equipment/attachmentLimits.ts';

/**
 * 这些断言守的是"页面说明与实际规则一致"：
 * accept 属性、页面提示、服务端白名单三者对同一种格式要给出同一个答案。
 */

test('图片类别只放开图片扩展名', () => {
  const accept = acceptOf('IMAGE');

  assert.ok(accept.includes('.jpg'));
  assert.ok(accept.includes('.png'));
  assert.ok(!accept.includes('.pdf'));
  assert.ok(!accept.includes('.docx'));
});

/** 设备手册类不收 Word：页面必须在选中「设备手册」时就说清，否则用户只会反复失败。 */
test('设备手册收 PDF 与图片，不收 Word', () => {
  const accept = acceptOf('MANUAL');

  assert.ok(accept.includes('.pdf'));
  assert.ok(accept.includes('.png'));
  assert.ok(!accept.includes('.docx'));
  assert.match(formatsTextOf('MANUAL'), /PDF/);
});

test('文档资料覆盖 PDF、Word、Excel 与纯文本', () => {
  const accept = acceptOf('DOCUMENT');

  for (const extension of ['.pdf', '.doc', '.docx', '.xls', '.xlsx', '.txt', '.csv']) {
    assert.ok(accept.includes(extension), `缺少 ${extension}：${accept}`);
  }
});

/** 本次需求的核心：任何类别的上传框都不能把可执行文件列进去。 */
test('所有类别的 accept 都不含可执行文件', () => {
  const blocked = ['.exe', '.bat', '.cmd', '.msi', '.sh', '.ps1', '.jar', '.dll'];

  for (const kind of ['IMAGE', 'MANUAL', 'DOCUMENT', 'PHOTO'] as const) {
    const accept = acceptOf(kind);
    for (const extension of blocked) {
      assert.ok(!accept.includes(extension), `${kind} 不应包含 ${extension}`);
    }
  }
  for (const extension of blocked) {
    assert.ok(!CONTRACT_ACCEPT.includes(extension), `合同上传不应包含 ${extension}`);
  }
});

test('合同上传放开图片与文档两类，说明同时提到 PDF 与 Word', () => {
  for (const extension of ['.jpg', '.pdf', '.docx', '.xlsx']) {
    assert.ok(CONTRACT_ACCEPT.includes(extension), `缺少 ${extension}`);
  }
  assert.match(CONTRACT_FORMATS_TEXT, /PDF/);
  assert.match(CONTRACT_FORMATS_TEXT, /Word/);
});

test('每类都有非空的格式说明，页面不会出现"可传："后面空着', () => {
  for (const kind of ['IMAGE', 'MANUAL', 'DOCUMENT', 'PHOTO'] as const) {
    assert.ok(formatsTextOf(kind).length > 0, kind);
  }
});

/**
 * 本地检查只挡空文件与超限；.exe 在这里是放行的，说明前端不重复实现类型规则，
 * 类型判定始终以服务端为准，避免两套白名单逐渐走偏。
 */
test('本地检查不判断类型，可执行文件交给服务端拒绝', () => {
  assert.equal(localFileError(new File([new Uint8Array(0)], 'a.pdf')), '这个文件是空的，请重新选择。');

  const oversized = localFileError(new File([new Uint8Array(MAX_FILE_SIZE + 1)], 'a.pdf'));
  assert.match(String(oversized), /超过 20 MB/);

  assert.equal(localFileError(new File([new Uint8Array(8)], 'setup.exe')), null);
});
