/**
 * 读数显示精度（P09 最新值 / P10 历史读数共用）。
 *
 * <p>后端按契约原样返回 {@code DECIMAL(20,6)} 的字符串（{@code "1092.000000"}），
 * 这是采集侧的真实精度，接口不改；页面上要显示成参数配置的 {@code decimalPlaces}，
 * 否则同一个值在参数行是 {@code 1092}、在历史列表是 {@code 1092.000000}，
 * 演示时会被当成两个数。</p>
 *
 * <p>这里刻意不走 {@code Number} 转换：{@code DECIMAL(20,6)} 能存 20 位有效数字，
 * 超过 15 位就已经丢了精度，页面上会出现末位对不上的情况。改用字符串按位补零与进位，
 * 大数值也是精确的。取舍是"四舍五入"按绝对值进位（{@code -1.005} 保留两位得 {@code -1.01}），
 * 与现场看表的习惯一致。</p>
 */

const DECIMAL_PATTERN = /^([+-]?)(\d*)(?:\.(\d*))?$/;

/** 数值型读数按精度补齐小数位；空值与文本型读数不在本函数职责内，原样返回。 */
export function formatReadingValue(value: string | null | undefined, decimalPlaces: number): string | null {
  if (value == null) return null;
  const text = value.trim();
  if (text === '') return null;

  const matched = DECIMAL_PATTERN.exec(text);
  if (!matched) return value; // 文本型读数（如 "开" "N/A"）没有精度可言
  if (matched[2] === '' && (matched[3] ?? '') === '') return value;

  const places = clampPlaces(decimalPlaces);
  const sign = matched[1] === '-' ? '-' : '';
  const whole = (matched[2] || '0').replace(/^0+(?=\d)/, '');
  const fraction = matched[3] ?? '';

  const kept = fraction.slice(0, places).padEnd(places, '0');
  let digits = whole + kept;
  if (fraction.length > places && fraction.charCodeAt(places) - 48 >= 5) {
    digits = incrementDigits(digits);
  }

  const padded = digits.padStart(places + 1, '0');
  const head = padded.slice(0, padded.length - places);
  const tail = padded.slice(padded.length - places);
  const body = places > 0 ? `${head}.${tail}` : head;
  // -0.00 与 0.00 是同一个读数，不要因为源值多了个负号就显示两个样子。
  return /^0*$/.test(digits) ? body : `${sign}${body}`;
}

function clampPlaces(decimalPlaces: number): number {
  if (!Number.isFinite(decimalPlaces)) return 0;
  return Math.min(Math.max(Math.trunc(decimalPlaces), 0), 6);
}

/** 字符串整数部分加一，逐位进位；全 9 时补一位。 */
function incrementDigits(digits: string): string {
  const chars = digits.split('');
  let index = chars.length - 1;
  while (index >= 0) {
    if (chars[index] === '9') {
      chars[index] = '0';
      index -= 1;
      continue;
    }
    chars[index] = String.fromCharCode(chars[index].charCodeAt(0) + 1);
    return chars.join('');
  }
  return `1${chars.join('')}`;
}
