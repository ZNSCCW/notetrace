package com.notetrace.ingest;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * 编码兼容单测：UTF-8 严格解码 + GBK 回退（Windows ANSI 笔记可用）。
 */
class EncodingTest {

    @TempDir
    Path tempDir;

    @Test
    void utf8FileIsReadCorrectly() throws IOException {
        Path f = tempDir.resolve("utf8.md");
        Files.writeString(f, "# 中文标题\n内容", StandardCharsets.UTF_8);
        assertThat(IngestService.readFileSmart(f)).contains("# 中文标题");
    }

    @Test
    void gbkFileFallsBackAndIsReadable() throws IOException {
        Path f = tempDir.resolve("ansi.txt");
        // GBK 编码写入（模拟 Windows 记事本 ANSI 保存的中文文件）
        Files.write(f, "中文笔记内容".getBytes(Charset.forName("GBK")));
        assertThat(IngestService.readFileSmart(f)).contains("中文笔记内容");
    }

    @Test
    void asciiFileReadsInBothModes() throws IOException {
        Path f = tempDir.resolve("ascii.txt");
        Files.writeString(f, "plain ascii content", StandardCharsets.US_ASCII);
        assertThat(IngestService.readFileSmart(f)).contains("plain ascii");
    }
}
