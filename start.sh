#!/usr/bin/env bash
# NoteTrace 启动脚本（Git Bash / Linux）
# 自动设置入库目录为项目根下的 data/notes
cd "$(dirname "$0")"
export NOTETRACE_INGEST_DIR="$(pwd)/data/notes"
echo "入库目录: $NOTETRACE_INGEST_DIR"
echo "启动 NoteTrace... (http://localhost:8081)"
mvn -f backend/pom.xml spring-boot:run
