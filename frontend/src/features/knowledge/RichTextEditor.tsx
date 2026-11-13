import { useEffect, useRef } from "react";

type Props = {
  value: string;
  onChange: (value: string) => void;
  label: string;
  placeholder?: string;
};

const commands = [
  ["bold", "B", "加粗"],
  ["italic", "I", "斜体"],
  ["underline", "U", "下划线"],
  ["insertUnorderedList", "• 列表", "项目符号"],
  ["insertOrderedList", "1. 列表", "编号列表"],
] as const;

export function RichTextEditor({ value, onChange, label, placeholder }: Props) {
  const editor = useRef<HTMLDivElement>(null);
  const image = useRef<HTMLInputElement>(null);
  useEffect(() => {
    if (editor.current && editor.current.innerHTML !== value)
      editor.current.innerHTML = value;
  }, [value]);
  function run(command: string, argument?: string) {
    editor.current?.focus();
    document.execCommand(command, false, argument);
    onChange(editor.current?.innerHTML ?? "");
  }
  function addLink() {
    const url = window.prompt("请输入文章或资料链接（以 http:// 或 https:// 开头）");
    if (!url) return;
    if (!/^https?:\/\//i.test(url)) {
      window.alert("链接需以 http:// 或 https:// 开头");
      return;
    }
    run("createLink", url);
  }
  function addImage(file?: File) {
    if (!file) return;
    if (!file.type.startsWith("image/")) {
      window.alert("请选择图片文件");
      return;
    }
    if (file.size > 3 * 1024 * 1024) {
      window.alert("单张图片请控制在 3MB 以内");
      return;
    }
    const reader = new FileReader();
    reader.onload = () => run("insertImage", String(reader.result));
    reader.readAsDataURL(file);
  }
  return (
    <label className="rich-editor-field">
      <span>{label}</span>
      <div className="rich-editor">
        <div className="rich-toolbar" role="toolbar" aria-label={`${label}编辑工具`}>
          {commands.map(([command, text, title]) => (
            <button key={command} type="button" title={title} aria-label={title} className={`editor-command editor-${command}`} onMouseDown={(e) => e.preventDefault()} onClick={() => run(command)}>{text}</button>
          ))}
          <span className="toolbar-separator" aria-hidden="true" />
          <button type="button" onMouseDown={(e) => e.preventDefault()} onClick={addLink}>🔗 链接</button>
          <button type="button" onClick={() => image.current?.click()}>▧ 图片</button>
          <span className="toolbar-separator" aria-hidden="true" />
          <button type="button" title="撤销" onMouseDown={(e) => e.preventDefault()} onClick={() => run("undo")}>↶</button>
          <button type="button" title="重做" onMouseDown={(e) => e.preventDefault()} onClick={() => run("redo")}>↷</button>
          <button type="button" onMouseDown={(e) => e.preventDefault()} onClick={() => run("removeFormat")}>清除格式</button>
          <input ref={image} type="file" accept="image/*" hidden onChange={(e) => { addImage(e.target.files?.[0]); e.currentTarget.value = ""; }} />
        </div>
        <div
          ref={editor}
          className="rich-editor-body"
          contentEditable
          role="textbox"
          aria-multiline="true"
          data-placeholder={placeholder}
          suppressContentEditableWarning
          onInput={(e) => onChange(e.currentTarget.innerHTML)}
        />
      </div>
      <small className="rich-editor-help">支持基础文字格式、列表、图片文件和网页链接；图片会随文章内容保存。</small>
    </label>
  );
}
