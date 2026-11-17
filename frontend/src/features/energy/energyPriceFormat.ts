/**
 * 能源价格的展示与预校验。
 *
 * <p>独立成 .ts 是为了可测：Node 的类型剥离不处理 JSX，放在组件文件里就没法被
 * `node --test` 直接加载。这里只放纯函数，不碰 React。</p>
 */

/**
 * 单价显示。
 *
 * <p>去掉多余尾零但至少保留两位小数：后端按数据库精度返回 10 位（{@code "0.8000000000"}），
 * 原样铺在表格里既占地方又难扫读；而 "0.8" 与 "0.8000000000" 是同一个价，缩成 "0.80" 不丢信息。
 * 需要看完整值时用 title 展示原始字符串。</p>
 */
export function formatPrice(raw: string): string {
  const trimmed = raw.replace(/(\.\d*?)0+$/, '$1').replace(/\.$/, '');
  const [whole, fraction = ''] = trimmed.split('.');
  return `${whole}.${fraction.padEnd(2, '0')}`;
}

/**
 * 单价预校验，与后端同口径：不允许负数，允许明确零，最多 10 位小数。
 *
 * <p>放在提交前是为了省一次往返，但不替代后端校验——接口仍会独立判断。</p>
 */
export function priceError(raw: string): string | null {
  const value = raw.trim();
  if (!value) return '单价为必填项。';
  if (!/^\d+(\.\d+)?$/.test(value)) return '单价只能是不小于 0 的数字。';
  const fraction = value.split('.')[1] ?? '';
  if (fraction.length > 10) return '单价最多保留 10 位小数。';
  return null;
}

/** 本地日期 yyyy-MM-dd，不用 UTC 以免跨零点时把"今天"变成明天。 */
export function localToday(): string {
  const now = new Date();
  const offset = now.getTimezoneOffset() * 60000;
  return new Date(now.getTime() - offset).toISOString().slice(0, 10);
}
