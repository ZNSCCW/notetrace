# NoteTrace（知溯）· AI 个人知识库

带**引用溯源**的个人知识库——把 Markdown 笔记变成可问答、**每句回答都能点击溯源**的智能知识库。

> 防幻觉设计：回答中的引用编号经程序校验，只允许引用检索到的真实内容，杜绝 LLM 编造来源。

## 特性

- **结构感知切分**：按 Markdown 标题层级切块，保留 `章节路径 + 行号` 定位信息
- **本地嵌入**：Ollama + bge-m3，免费无限额、笔记不出本机
- **向量检索 + 关键词重排**：pgvector HNSW 余弦检索 + 简单可解释的重排
- **防伪溯源问答**：编号约束生成 + 候选集校验，回答必带可点击来源（文档/章节/行号）
- **检索评估集**：20 条「问题→期望来源」回归基准，切分/重排策略调整可量化（当前 Top-5 命中率 100%）
- **零依赖部署**：`docker compose up` 一键起数据库；Thymeleaf 服务端渲染，无前端框架

## 架构

```
┌─────────────────────────────────────────────┐
│ Web UI（Thymeleaf：问答页 + 入库状态页）      │
└──────────────────┬──────────────────────────┘
┌──────────────────▼──────────────────────────┐
│ Spring Boot 3.5 (Java 21)                    │
│  导入解析 │ 切分 │ 向量化 │ 检索重排 │ 生成溯源 │
└──┬──────────────┬──────────────┬────────────┘
┌──▼───┐  ┌───────▼──────┐  ┌────▼─────────────┐
│Postg │  │ pgvector     │  │ LLM 抽象层        │
│res   │  │ HNSW 检索     │  │ Ollama bge-m3 ↔ DeepSeek │
└──────┘  └──────────────┘  └──────────────────┘
```

## 快速开始

前置：Docker（Postgres+pgvector）、Ollama（本地嵌入）、DeepSeek API key（生成，可选）。

```bash
# 1. 起数据库（Postgres 16 + pgvector）
docker compose up -d

# 2. 本地嵌入：安装 Ollama 并拉取 bge-m3
ollama pull bge-m3

# 3. 配置 DeepSeek key（生成用；不配则走 mock）
setx DEEPSEEK_API_KEY "sk-xxx"

# 4. 启动应用（默认 http://localhost:8081）
mvn -f backend/pom.xml spring-boot:run
```

> 入库目录：`data/notes/`（放你的 Markdown/文本笔记；该目录已在 .gitignore，**真实笔记不会进仓库**）。可用环境变量 `NOTETRACE_INGEST_DIR` 覆盖。

## 使用

- **提问**：打开 `http://localhost:8081`，输入问题，回答带 `[n]` 引用，点击展开核对原文
- **入库状态**：`http://localhost:8081/documents` 查看文档/chunks/处理状态
- **评估检索质量**：启动加 `--eval` 参数，输出 Top-5 命中率

```bash
mvn -f backend/pom.xml spring-boot:run -Dspring-boot.run.arguments=--eval
```

## 里程碑

| 版本 | 内容 |
|---|---|
| v0.1-m0 | 骨架：Spring Boot + Postgres/pgvector + /health |
| v0.1-m1 | MVP：导入→切分→向量化→防伪溯源问答→UI + 评估集 |
| M2（规划） | 增量更新 + 去重（改笔记不全量重建） |
| M3（规划） | 知识图谱：实体抽取 / 关系浏览 |

## 技术栈

Java 21 · Spring Boot 3.5 · Spring Data JPA · PostgreSQL 16 + pgvector · Thymeleaf · Ollama bge-m3 · DeepSeek API

## License

[MIT](LICENSE)
