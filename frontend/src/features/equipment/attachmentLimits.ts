import type { AttachmentFileKind } from '../../lib/api';

/**
 * 上传限制与说明文字的唯一来源（设备附件页签、合同文件区共用）。
 *
 * <p>此前两个入口各写一份说明：设备页说"支持 Word/Excel"，合同区说"可传签署扫描件或 PDF"，
 * 而实际规则由后端 {@code AttachmentStorage.ALLOWED_TYPES} 决定，说法和事实对不上。
 * 现在同一张表既生成 accept 属性、也生成页面提示，改规则只改这张表，
 * 不会出现"页面写着能传、实际被拒"。</p>
 *
 * <p>前端不做类型白名单判断：accept 只是让文件选择器少列无用项，最终由服务端裁定。</p>
 */

/** 与后端 phase2.attachment.max-file-size 默认值（20971520 字节）保持一致。 */
export const MAX_FILE_SIZE = 20 * 1024 * 1024;
export const MAX_FILE_SIZE_TEXT = '20 MB';

type Format = {label: string; extensions: string[]};

const IMAGE_FORMAT: Format = {
  label: '图片（JPG、PNG、WebP、GIF、BMP）',
  extensions: ['jpg', 'jpeg', 'png', 'webp', 'gif', 'bmp'],
};

/**
 * 按类别列出允许的格式。
 *
 * <p>必须与后端 {@code AttachmentStorage.ALLOWED_TYPES} 一一对应：改后端白名单时同步这里，
 * 否则页面会提示"能传"而服务端拒收。文档资料类不含图片——扫描图请走图片类别，
 * 这样附件列表里的类别名与文件真实内容不会打架。</p>
 */
const FORMATS: Record<AttachmentFileKind, Format[]> = {
  IMAGE: [IMAGE_FORMAT],
  PHOTO: [IMAGE_FORMAT],
  MANUAL: [
    {label: 'PDF', extensions: ['pdf']},
    {label: '图片（JPG、PNG、WebP）', extensions: ['jpg', 'jpeg', 'png', 'webp']},
  ],
  DOCUMENT: [
    {label: 'PDF', extensions: ['pdf']},
    {label: 'Word（DOC、DOCX）', extensions: ['doc', 'docx']},
    {label: 'Excel（XLS、XLSX）', extensions: ['xls', 'xlsx']},
    {label: '文本（TXT、CSV）', extensions: ['txt', 'csv']},
  ],
};

function acceptOfKinds(kinds: AttachmentFileKind[]): string {
  const extensions = kinds.flatMap(kind => FORMATS[kind].flatMap(format => format.extensions));
  return [...new Set(extensions)].map(extension => `.${extension}`).join(',');
}

function textOfKinds(kinds: AttachmentFileKind[]): string {
  return kinds.flatMap(kind => FORMATS[kind].map(format => format.label)).join('、');
}

/** 某一类附件的 accept 属性，可直接交给 input[type=file]。 */
export function acceptOf(kind: AttachmentFileKind): string {
  return acceptOfKinds([kind]);
}

/** 某一类附件允许的格式说明，用于页面提示与本地报错。 */
export function formatsTextOf(kind: AttachmentFileKind): string {
  return textOfKinds([kind]);
}

/**
 * 「支持哪些格式、单个多大」的统一说法，三个上传入口共用一句。
 *
 * <p>此前设备附件写「当前类别可传…」、合同文件写「支持…」、替换弹窗写「只能换成…」，
 * 同一件事三种句式，还各自带一句「可执行文件一律不收」。现在统一成这一句，
 * 格式清单仍来自 {@link FORMATS}，与后端白名单同源。</p>
 */
export function supportHintOf(kinds: AttachmentFileKind[]): string {
  return `支持以下格式：${textOfKinds(kinds)}，单个不超过 ${MAX_FILE_SIZE_TEXT}。`;
}

/**
 * 合同文件不选类别：图片按 IMAGE 归档，其余按 DOCUMENT 归档，
 * 所以上传框要放开这两类的并集，单看 DOCUMENT 会把手机拍的扫描件挡在外面。
 */
export const CONTRACT_ACCEPT = acceptOfKinds(['IMAGE', 'DOCUMENT']);
export const CONTRACT_FORMATS_TEXT = textOfKinds(['IMAGE', 'DOCUMENT']);

export function formatSize(bytes: number): string {
  if (bytes >= 1024 * 1024) return `${(bytes / 1024 / 1024).toFixed(1)} MB`;
  if (bytes >= 1024) return `${Math.round(bytes / 1024)} KB`;
  return `${bytes} B`;
}

/**
 * 上传前的本地检查只挡明显错误（空文件、超上限），格式由服务端判定。
 *
 * <p>两处上传共用这一份，是为了让"超过 20 MB"的提示在两个页签里说法一致。</p>
 */
export function localFileError(file: File): string | null {
  if (file.size === 0) return '这个文件是空的，请重新选择。';
  if (file.size > MAX_FILE_SIZE) return `文件大小为 ${formatSize(file.size)}，超过 ${MAX_FILE_SIZE_TEXT} 上限。`;
  return null;
}
