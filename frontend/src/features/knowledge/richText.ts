const allowedTags = new Set(["P", "BR", "H2", "H3", "H4", "STRONG", "B", "EM", "I", "U", "UL", "OL", "LI", "BLOCKQUOTE", "A", "IMG"]);

export function plainText(content?: string | null) {
  if (!content) return "";
  const doc = new DOMParser().parseFromString(content, "text/html");
  return (doc.body.textContent ?? "").replace(/\u00a0/g, " ").trim();
}
export function hasRichContent(content?: string | null) {
  return !!plainText(content) || /<img\b/i.test(content ?? "");
}

export function safeKnowledgeHtml(content?: string | null) {
  if (!content) return "";
  if (!/<[a-z][\s\S]*>/i.test(content)) {
    const div = document.createElement("div");
    div.textContent = content;
    return div.innerHTML.replace(/\r?\n/g, "<br>");
  }
  const doc = new DOMParser().parseFromString(content, "text/html");
  for (const element of Array.from(doc.body.querySelectorAll("*"))) {
    if (!allowedTags.has(element.tagName)) {
      element.replaceWith(...Array.from(element.childNodes));
      continue;
    }
    const href = element.getAttribute("href") ?? "";
    const src = element.getAttribute("src") ?? "";
    const alt = element.getAttribute("alt") ?? "";
    for (const attribute of Array.from(element.attributes)) element.removeAttribute(attribute.name);
    if (element.tagName === "A") {
      if (/^https?:\/\//i.test(href)) {
        element.setAttribute("href", href);
        element.setAttribute("target", "_blank");
        element.setAttribute("rel", "noopener noreferrer");
      }
    }
    if (element.tagName === "IMG") {
      if (/^(data:image\/(png|jpeg|jpg|gif|webp);base64,|https?:\/\/)/i.test(src)) {
        element.setAttribute("src", src);
        element.setAttribute("alt", alt || "知识正文配图");
        element.setAttribute("loading", "lazy");
      } else element.remove();
    }
  }
  return doc.body.innerHTML;
}
