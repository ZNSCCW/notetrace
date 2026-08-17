package com.notetrace.document;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

public interface DocumentRepository extends JpaRepository<Document, Long> {

    Optional<Document> findBySourcePath(String sourcePath);

    long countByStatus(String status);
}
