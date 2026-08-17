-- NoteTrace 数据库表结构（M1）
-- 说明：embedding 列由 JdbcTemplate 管理（pgvector 类型不映射进 JPA 实体），
--       表结构手写以便完全控制 DDL，后续里程碑可平滑迁移到 Flyway。

CREATE TABLE IF NOT EXISTS documents (
    id          BIGSERIAL PRIMARY KEY,
    title       VARCHAR(500)  NOT NULL,
    source_path VARCHAR(2000) NOT NULL UNIQUE,   -- 相对入库目录的路径
    source_type VARCHAR(20)   NOT NULL,          -- md / txt
    file_hash   VARCHAR(64)   NOT NULL,          -- 内容哈希（变更检测，M2 增量用）
    status      VARCHAR(20)   NOT NULL DEFAULT 'PENDING',  -- PENDING/PROCESSED/FAILED
    created_at  TIMESTAMPTZ   NOT NULL DEFAULT now(),
    updated_at  TIMESTAMPTZ   NOT NULL DEFAULT now()
);

CREATE TABLE IF NOT EXISTS chunks (
    id              BIGSERIAL PRIMARY KEY,
    document_id     BIGINT       NOT NULL REFERENCES documents(id) ON DELETE CASCADE,
    content         TEXT         NOT NULL,
    section_path    VARCHAR(2000),               -- 如 Java/并发/线程池/拒绝策略
    start_line      INT          NOT NULL,
    end_line        INT          NOT NULL,
    token_count     INT          NOT NULL DEFAULT 0,
    embedding       vector(1024),                -- bge-m3 维度；换模型需全量重建
    embedding_model VARCHAR(100),
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS idx_chunks_document_id ON chunks(document_id);
-- HNSW 余弦距离索引（对应 <=> 算子）
CREATE INDEX IF NOT EXISTS idx_chunks_embedding ON chunks USING hnsw (embedding vector_cosine_ops);
