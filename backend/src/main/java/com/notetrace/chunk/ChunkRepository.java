package com.notetrace.chunk;

import org.springframework.data.jpa.repository.JpaRepository;

public interface ChunkRepository extends JpaRepository<Chunk, Long> {

    long countByDocumentId(Long documentId);

    void deleteByDocumentId(Long documentId);
}
