# NoteTrace 部署与运维

## 依赖

| 组件 | 说明 |
|---|---|
| Docker | Postgres 16 + pgvector（`docker compose up -d`） |
| Ollama | 本地嵌入 bge-m3（必须）；本地生成 qwen2.5:7b（可选） |
| DeepSeek API key | 生成用（可选；不配则走本地/mock） |
| JDK 21 + Maven | 构建运行 |

## 启动

```bash
# 一键（Windows）：自动配置入库目录与读取 DeepSeek key
start.bat

# 或手动
docker compose up -d
export DEEPSEEK_API_KEY=sk-xxx          # PowerShell: $env:DEEPSEEK_API_KEY="sk-xxx"
NOTETRACE_INGEST_DIR="D:/path/to/notes" mvn -f backend/pom.xml spring-boot:run
```

## 数据与备份

- **数据位置**：Docker 卷 `notetrace_pgdata`（`docker compose down` 不丢数据）
- **备份**（定时执行）：
  ```bash
  docker exec notetrace-db pg_dump -U notetrace -d notetrace -F c -f /tmp/notetrace.dump
  docker cp notetrace-db:/tmp/notetrace.dump ./backup/notetrace-$(date +%Y%m%d).dump
  ```
- **恢复**：
  ```bash
  docker cp ./backup/notetrace-YYYYMMDD.dump notetrace-db:/tmp/restore.dump
  docker exec notetrace-db pg_restore -U notetrace -d notetrace --clean --if-exists /tmp/restore.dump
  ```
- **笔记源文件**：`data/notes/` 由 git/网盘自行备份（不依赖数据库）

## 升级

1. `git pull`（代码更新）
2. 重启应用（启动时自动：schema 迁移 → 重新入库 → 重建图谱）
3. 数据库结构变更通过 `schema.sql` 幂等执行（`IF NOT EXISTS` + 显式 `ALTER`）

## 故障排查

| 症状 | 排查 |
|---|---|
| 启动报 `Port 8081 was already in use` | `netstat -ano | grep 8081` 找到 PID 后结束旧实例 |
| 页面渲染异常/旧内容 | 改代码后需 `mvn compile` 再启动（`spring-boot:run` 不自动编译） |
| 问答慢（>30s） | DeepSeek 网络波动重试；本地生成首次加载模型慢属正常 |
| 图谱无概念 | 概念需 DeepSeek API 可用时抽取（日志 `概念抽取` 行确认） |
| ANSI 笔记乱码 | 已内置 UTF-8/GBK 回退；仍乱码检查文件编码 |
