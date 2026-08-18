# NoteTrace（知溯）· AI 个人知识库

带**引用溯源**的个人知识库——把 Markdown 笔记变成可问答、**每句回答都能点击溯源**、并具备**知识图谱**的智能知识库。

> 防幻觉设计：回答中的引用编号经程序校验，只允许引用检索到的真实内容，杜绝 LLM 编造来源。

## 特性

**检索与问答**
- **结构感知切分**：按 Markdown 标题层级切块，保留 `章节路径 + 行号` 定位信息
- **混合检索**：向量（pgvector HNSW 余弦）+ 关键词（中文 bigram）双路召回融合——精确短语不再被长查询稀释
- **防伪溯源问答**：编号约束生成 + 候选集校验，回答必带可点击来源（文档/章节/行号），展开核对原文
- **双生成引擎**：DeepSeek API / 本地 Ollama qwen2.5:7b（UI 一键切换，零成本本地运行）

**知识图谱（M3）**
- **结构层**：从章节路径自动构建「主题-笔记」层级图（164 主题/15 笔记实测）
- **概念层（M3.1）**：LLM 抽取技术概念（207 概念实测），支持"X 与 Y 共同出现在哪些笔记"关系问答
- **图谱浏览页**：主题树 + 概念标签 + 关系查询

**工程与自用**
- **网页管理**：上传/删除笔记（浏览器即可，删除自动同步清理索引与图谱）
- **编码兼容**：UTF-8 优先 + GBK 回退（Windows ANSI 笔记不乱码）
- **检索评估集**：34 条「问题→期望来源」回归基准，调整切分/重排可量化（**Top-5 命中率 100%**）
- **零依赖部署**：`docker compose up` 一键起数据库；Thymeleaf 服务端渲染

## 架构

```
┌──────────────────────────────────────────────────┐
│ Web UI（Thymeleaf：问答 / 入库状态 / 知识图谱）   │
└──────────────────┬───────────────────────────────┘
┌──────────────────▼───────────────────────────────┐
│ Spring Boot 3.5 (Java 21)                         │
│  导入解析 │ 切分 │ 混合检索 │ 防伪溯源 │ 图谱构建   │
└──┬──────────────┬──────────────┬────────────┬────┘
┌──▼───┐  ┌───────▼──────┐  ┌────▼─────────┐ ┌───▼────┐
│Postg │  │ pgvector     │  │ LLM 抽象层    │ │ 图谱    │
│res   │  │ HNSW 余弦     │  │ bge-m3 嵌入   │ │ 主题-概念│
│      │  │ + 关键词召回  │  │ DeepSeek/qwen│ │ 关系表  │
└──────┘  └──────────────┘  └──────────────┘ └────────┘
```

## 快速开始

前置：Docker（Postgres+pgvector）、Ollama（本地嵌入）、DeepSeek API key（生成，可选）。

```bash
# 1. 起数据库（Postgres 16 + pgvector）
docker compose up -d

# 2. 本地嵌入：安装 Ollama 并拉取 bge-m3（本地生成可选 qwen2.5:7b）
ollama pull bge-m3

# 3. 配置 DeepSeek key（生成用；不配则走本地/mock）
setx DEEPSEEK_API_KEY "sk-xxx"

# 4. 启动应用（默认 http://localhost:8081）
#    Windows:  直接运行 start.bat（自动配置入库目录与 key）
#    Git Bash: bash start.sh
mvn -f backend/pom.xml spring-boot:run
```

> 入库目录：`data/notes/`（放你的 Markdown/文本笔记；该目录已在 .gitignore，**真实笔记不会进仓库**）。启动脚本自动指向项目根下的 `data/notes`；手动启动可用 `NOTETRACE_INGEST_DIR` 覆盖。

## 使用

| 页面 | 功能 |
|---|---|
| `/` | 问答：选「API / 本地 AI」提问，回答带可点击 `[n]` 引用 + 相关笔记推荐 |
| `/documents` | 入库状态：上传/删除笔记、chunks 数、处理状态（失败可见） |
| `/graph` | 知识图谱：主题树浏览、概念标签、关系问答（X 与 Y 共同出现的笔记） |
| `--eval` | 检索质量回归：`start.bat -Dspring-boot.run.arguments=--eval`，输出 Top-5 命中率 |

## 里程碑

| 版本 | 内容 |
|---|---|
| v0.1-m0 | 骨架：Spring Boot + Postgres/pgvector + /health |
| v0.1-m1 | MVP：导入→切分→向量化→防伪溯源问答→UI + 评估集（20 条） |
| M2 | 混合检索（向量+关键词融合、中文 bigram）→ 评估 100%；删除检测 |
| M3 | 知识图谱：主题-笔记图构建 + /graph 浏览与关联查询 |
| M3.1 | LLM 概念抽取（207 概念）+ 关系问答 |

## 技术栈

Java 21 · Spring Boot 3.5 · Spring Data JPA · PostgreSQL 16 + pgvector（HNSW）· Thymeleaf · Ollama（bge-m3 / qwen2.5:7b）· DeepSeek API

## License

[MIT](LICENSE)
