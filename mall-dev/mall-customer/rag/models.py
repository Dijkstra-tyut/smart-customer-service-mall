from dataclasses import dataclass, field
from typing import Any

from pydantic import BaseModel, Field


@dataclass(frozen=True)
class ParentChunk:
    id: str
    document_id: str
    version_id: str
    kb_id: str
    source: str
    section_path: list[str]
    content: str
    ordinal: int


@dataclass(frozen=True)
class ChildChunk:
    id: str
    parent_id: str
    version_id: str
    kb_id: str
    content: str


class SearchRequest(BaseModel):
    query: str = Field(min_length=1, max_length=1000)
    k: int = Field(default=5, ge=1, le=10)


class BuildRequest(BaseModel):
    source: str = Field(min_length=1, max_length=1000, description="知识库源目录内的相对文件路径")


@dataclass
class SearchResult:
    items: list[dict[str, Any]] = field(default_factory=list)
    status: str = "empty"
