import re
import unicodedata
import uuid

from langchain_text_splitters import MarkdownHeaderTextSplitter, RecursiveCharacterTextSplitter

from rag.models import ChildChunk, ParentChunk


def optimize_markdown(text: str) -> str:
    """保守优化版式，不让模型改写价格、政策、数字、表格或限制条件。"""
    text = unicodedata.normalize("NFC", text).replace("\r\n", "\n").replace("\r", "\n").replace("\x00", "")
    lines, fenced = [], False
    for line in text.splitlines():
        if line.lstrip().startswith(("```", "~~~")):
            fenced = not fenced
        if not fenced:
            line = line.rstrip()
            line = re.sub(r"^(#{1,6})(?=[^#\s])", r"\1 ", line)
        lines.append(line)
    return "\n".join(lines).strip()


def chunk_document(markdown: str, document_id: str, version_id: str, kb_id: str, source: str,
                   child_size: int = 500, child_overlap: int = 80):
    if not 0 <= child_overlap < child_size:
        raise ValueError("子块重叠长度必须小于块长度")
    sections = MarkdownHeaderTextSplitter(
        headers_to_split_on=[("#" * i, f"h{i}") for i in range(1, 7)], strip_headers=False,
    ).split_text(optimize_markdown(markdown))
    splitter = RecursiveCharacterTextSplitter(
        chunk_size=child_size, chunk_overlap=child_overlap,
        separators=["\n\n", "\n", "。", "！", "？", "；", "，", " ", ""], keep_separator="end",
    )
    parents, children = [], []
    for i, section in enumerate(sections):
        if not section.page_content.strip():
            continue
        path = [section.metadata[f"h{n}"] for n in range(1, 7) if f"h{n}" in section.metadata]
        parent_id = uuid.uuid5(uuid.NAMESPACE_URL, f"{version_id}:parent:{i}").hex
        parents.append(ParentChunk(parent_id, document_id, version_id, kb_id, source, path, section.page_content, i))
        for j, content in enumerate(splitter.split_text(section.page_content)):
            child_id = uuid.uuid5(uuid.NAMESPACE_URL, f"{parent_id}:child:{j}").hex
            children.append(ChildChunk(child_id, parent_id, version_id, kb_id, " > ".join(path) + "\n" + content))
    if not children:
        raise ValueError("文档解析后没有可入库的正文")
    return parents, children
