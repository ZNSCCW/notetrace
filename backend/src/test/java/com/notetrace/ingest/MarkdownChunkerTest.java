package com.notetrace.ingest;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Test;

import com.notetrace.ingest.MarkdownChunker.ChunkPiece;

class MarkdownChunkerTest {

    private final MarkdownChunker chunker = new MarkdownChunker(2000);

    @Test
    void emptyTextReturnsEmptyList() {
        assertThat(chunker.chunk("")).isEmpty();
        assertThat(chunker.chunk("   \n  \n")).isEmpty();
        assertThat(chunker.chunk(null)).isEmpty();
    }

    @Test
    void plainTextWithoutHeadingYieldsSingleChunk() {
        List<ChunkPiece> pieces = chunker.chunk("第一行内容\n第二行内容");
        assertThat(pieces).hasSize(1);
        assertThat(pieces.get(0).content()).contains("第一行内容", "第二行内容");
        assertThat(pieces.get(0).sectionPath()).isEmpty();
        assertThat(pieces.get(0).startLine()).isEqualTo(1);
        assertThat(pieces.get(0).endLine()).isEqualTo(2);
    }

    @Test
    void splitsByHeadingWithCorrectSectionPath() {
        String md = """
                文件头说明
                # Java 并发
                并发基础内容
                ## 线程池
                线程池内容
                # Spring
                Spring 内容
                """;
        List<ChunkPiece> pieces = chunker.chunk(md);
        // preamble + Java并发 + 线程池 + Spring
        assertThat(pieces).hasSize(4);

        // preamble：无 section_path，行 1
        assertThat(pieces.get(0).sectionPath()).isEmpty();
        assertThat(pieces.get(0).startLine()).isEqualTo(1);
        assertThat(pieces.get(0).endLine()).isEqualTo(1);

        // Java 并发：标题行 L2 + 内容 L3，不含线程池内容
        assertThat(pieces.get(1).sectionPath()).isEqualTo("Java 并发");
        assertThat(pieces.get(1).content()).contains("# Java 并发", "并发基础内容").doesNotContain("线程池内容");
        assertThat(pieces.get(1).startLine()).isEqualTo(2);
        assertThat(pieces.get(1).endLine()).isEqualTo(3);

        // 线程池：路径为 Java 并发/线程池，标题行 L4 + 内容 L5
        assertThat(pieces.get(2).sectionPath()).isEqualTo("Java 并发/线程池");
        assertThat(pieces.get(2).content()).contains("## 线程池", "线程池内容");
        assertThat(pieces.get(2).startLine()).isEqualTo(4);
        assertThat(pieces.get(2).endLine()).isEqualTo(5);

        // Spring：路径回到顶层
        assertThat(pieces.get(3).sectionPath()).isEqualTo("Spring");
        assertThat(pieces.get(3).content()).contains("Spring 内容");
        assertThat(pieces.get(3).startLine()).isEqualTo(6);
        assertThat(pieces.get(3).endLine()).isEqualTo(7);
    }

    @Test
    void headingLevelStackCollapsesCorrectly() {
        String md = """
                # A
                ## A1
                ### A1a
                深度内容
                # B
                B 内容
                """;
        List<ChunkPiece> pieces = chunker.chunk(md);
        // A、A1 为空章节（仅标题行），A1a、B 有内容
        assertThat(pieces).hasSize(4);
        assertThat(pieces.get(0).sectionPath()).isEqualTo("A");
        assertThat(pieces.get(1).sectionPath()).isEqualTo("A/A1");
        assertThat(pieces.get(2).sectionPath()).isEqualTo("A/A1/A1a");
        assertThat(pieces.get(2).content()).contains("深度内容");
        assertThat(pieces.get(3).sectionPath()).isEqualTo("B");
        assertThat(pieces.get(3).content()).contains("B 内容");
    }

    @Test
    void headingInsideCodeBlockIsIgnored() {
        String md = """
                # 用法
                ```java
                // 这不是标题
                # fake heading
                public void run() {}
                ```
                结束段落
                """;
        List<ChunkPiece> pieces = chunker.chunk(md);
        // 整个文档只有一个标题：代码块内的 # fake heading 不算，结束段落仍归"用法"
        assertThat(pieces).hasSize(1);
        assertThat(pieces.get(0).sectionPath()).isEqualTo("用法");
        assertThat(pieces.get(0).content()).contains("# fake heading", "结束段落");
    }

    @Test
    void hashWithoutFollowingSpaceIsNotHeading() {
        List<ChunkPiece> pieces = chunker.chunk("###not-a-heading\n正文");
        assertThat(pieces).hasSize(1);
        assertThat(pieces.get(0).sectionPath()).isEmpty();
    }

    @Test
    void longSectionIsSplitByParagraphs() {
        MarkdownChunker small = new MarkdownChunker(40);
        StringBuilder sb = new StringBuilder("# 长章节\n");
        for (int i = 0; i < 30; i++) {
            sb.append("段落内容第").append(i).append("段，用于撑长文本长度。\n\n");
        }
        List<ChunkPiece> pieces = small.chunk(sb.toString());
        assertThat(pieces.size()).isGreaterThan(1);
        for (ChunkPiece p : pieces) {
            assertThat(p.sectionPath()).isEqualTo("长章节");
        }
        // 行号从标题行（L1）开始，各 chunk 单调递增覆盖到内容末尾 L60
        assertThat(pieces.get(0).startLine()).isEqualTo(1);
        assertThat(pieces.get(0).content()).startsWith("# 长章节");
        assertThat(pieces.get(pieces.size() - 1).endLine()).isEqualTo(60);
        for (int i = 1; i < pieces.size(); i++) {
            assertThat(pieces.get(i).startLine()).isGreaterThanOrEqualTo(pieces.get(i - 1).endLine());
        }
    }

    @Test
    void emptySectionBetweenHeadingsIsSkipped() {
        String md = """
                # 有内容
                内容
                # 空章节
                # 又有内容
                更多
                """;
        List<ChunkPiece> pieces = chunker.chunk(md);
        // 空章节仍输出（含标题行），但路径切换正确
        assertThat(pieces).hasSize(3);
        assertThat(pieces.get(0).sectionPath()).isEqualTo("有内容");
        assertThat(pieces.get(1).sectionPath()).isEqualTo("空章节");
        assertThat(pieces.get(2).sectionPath()).isEqualTo("又有内容");
        assertThat(pieces.get(2).content()).contains("更多");
    }
}
