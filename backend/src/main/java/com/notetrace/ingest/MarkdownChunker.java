package com.notetrace.ingest;

import java.util.ArrayList;
import java.util.List;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Markdown 结构感知切分器（FR-5）。
 *
 * <p>规则：
 * <ul>
 *   <li>以标题（#~######，ATX 语法）为边界切块，chunk 保留 {@code section_path}（如 Java/并发/线程池）；</li>
 *   <li>代码块（``` 或 ~~~ 包裹）内的 # 不视为标题；</li>
 *   <li>每个 chunk 记录 1-based 的 (start_line, end_line) 定位信息——引用溯源（FR-10/11）的地基；</li>
 *   <li>超长 chunk 按空行段落二次切分，段落仍超长则按行硬切。</li>
 * </ul>
 */
@Component
public class MarkdownChunker {

    /** 单个 chunk 的最大字符数，超过则二次切分 */
    private final int maxChunkLength;

    public MarkdownChunker(@Value("${notetrace.ingest.max-chunk-length:2000}") int maxChunkLength) {
        if (maxChunkLength <= 0) {
            throw new IllegalArgumentException("maxChunkLength must be > 0");
        }
        this.maxChunkLength = maxChunkLength;
    }

    /** 切分结果：内容 + 章节路径 + 1-based 行号范围 */
    public record ChunkPiece(String content, String sectionPath, int startLine, int endLine) {
    }

    public List<ChunkPiece> chunk(String text) {
        List<ChunkPiece> result = new ArrayList<>();
        if (text == null || text.isBlank()) {
            return result;
        }

        String[] lines = text.split("\n", -1);
        // 标题栈：index = level - 1
        List<String> pathStack = new ArrayList<>();
        boolean inCodeBlock = false;

        Section current = null; // 当前正在累积的 section（含 preamble）

        for (int i = 0; i < lines.length; i++) {
            String line = lines[i];
            String trimmed = line.strip();

            // 代码块开关（不解析其中的标题）
            if (trimmed.startsWith("```") || trimmed.startsWith("~~~")) {
                inCodeBlock = !inCodeBlock;
            }

            if (!inCodeBlock) {
                int level = headingLevel(trimmed);
                if (level > 0) {
                    if (current != null && !current.isEmpty()) {
                        flushSection(result, current);
                    }
                    // 截断并更新标题栈
                    while (pathStack.size() > level) {
                        pathStack.remove(pathStack.size() - 1);
                    }
                    while (pathStack.size() < level) {
                        pathStack.add("");
                    }
                    pathStack.set(level - 1, trimmed.substring(level).strip());

                    current = new Section(line, i + 1, joinPath(pathStack));
                    continue;
                }
            }

            // 普通内容行
            if (current == null) {
                current = new Section(null, i + 1, "");
            }
            current.addLine(line, i + 1);
        }

        if (current != null && !current.isEmpty()) {
            flushSection(result, current);
        }
        return result;
    }

    /** 结算一个 section：按长度需要二次切分并输出 ChunkPiece */
    private void flushSection(List<ChunkPiece> out, Section section) {
        List<String> paragraphs = new ArrayList<>();
        List<Integer> paraStart = new ArrayList<>();
        List<Integer> paraEnd = new ArrayList<>();

        StringBuilder buf = new StringBuilder();
        Integer start = null;
        Integer end = null;

        for (int i = 0; i < section.lines().size(); i++) {
            String line = section.lines().get(i);
            boolean blank = line.isBlank();
            if (!blank) {
                if (start == null) {
                    start = section.lineNos().get(i);
                }
                end = section.lineNos().get(i);
            }
            buf.append(line).append('\n');
            if (blank && start != null) {
                paragraphs.add(buf.toString());
                paraStart.add(start);
                paraEnd.add(end);
                buf = new StringBuilder();
                start = null;
            }
        }
        if (start != null) {
            paragraphs.add(buf.toString());
            paraStart.add(start);
            paraEnd.add(end);
        }

        // 二次切分：把段落合并到 <= maxChunkLength 的块；单段超长则按行硬切
        StringBuilder chunk = new StringBuilder();
        int chunkStart = -1;
        int chunkEnd = -1;

        for (int i = 0; i < paragraphs.size(); i++) {
            String p = paragraphs.get(i);
            int pStart = paraStart.get(i);
            int pEnd = paraEnd.get(i);

            if (chunk.length() > 0 && chunk.length() + p.length() > maxChunkLength) {
                out.add(new ChunkPiece(chunk.toString().stripTrailing(), section.path(), chunkStart, chunkEnd));
                chunk = new StringBuilder();
                chunkStart = -1;
            }
            if (p.length() > maxChunkLength) {
                // 单段超长：先结算已有缓存，再按行硬切
                if (chunk.length() > 0) {
                    out.add(new ChunkPiece(chunk.toString().stripTrailing(), section.path(), chunkStart, chunkEnd));
                    chunk = new StringBuilder();
                    chunkStart = -1;
                }
                hardSplit(out, section, p, pStart, pEnd);
                continue;
            }
            if (chunkStart < 0) {
                chunkStart = pStart;
            }
            chunkEnd = pEnd;
            chunk.append(p);
        }
        if (chunk.length() > 0) {
            out.add(new ChunkPiece(chunk.toString().stripTrailing(), section.path(), chunkStart, chunkEnd));
        }
    }

    /** 单段落超长时按行硬切（保留行号） */
    private void hardSplit(List<ChunkPiece> out, Section section, String paragraph, int start, int end) {
        // 段落来自 lines 的某个连续区间，重新按行切
        int idx = section.lines().size();
        // 找到该段落起止对应的行索引
        for (int i = 0; i < section.lines().size(); i++) {
            if (section.lineNos().get(i) == start) {
                idx = i;
                break;
            }
        }
        StringBuilder buf = new StringBuilder();
        int bufStart = start;
        int bufEnd = start;
        for (int i = idx; i < section.lines().size() && section.lineNos().get(i) <= end; i++) {
            String line = section.lines().get(i);
            if (buf.length() + line.length() + 1 > maxChunkLength && buf.length() > 0) {
                out.add(new ChunkPiece(buf.toString().stripTrailing(), section.path(), bufStart, bufEnd));
                buf = new StringBuilder();
                bufStart = section.lineNos().get(i);
            }
            buf.append(line).append('\n');
            bufEnd = section.lineNos().get(i);
        }
        if (buf.length() > 0) {
            out.add(new ChunkPiece(buf.toString().stripTrailing(), section.path(), bufStart, bufEnd));
        }
    }

    /** ATX 标题级别；非标题返回 0。要求 # 后跟空格（避免误判 "###" 装饰线）。 */
    private int headingLevel(String trimmed) {
        if (trimmed.isEmpty() || trimmed.charAt(0) != '#') {
            return 0;
        }
        int level = 0;
        while (level < trimmed.length() && trimmed.charAt(level) == '#') {
            level++;
        }
        if (level > 6) {
            return 0;
        }
        if (level < trimmed.length() && trimmed.charAt(level) != ' ') {
            return 0;
        }
        return level;
    }

    private String joinPath(List<String> stack) {
        List<String> nonEmpty = stack.stream().filter(s -> !s.isEmpty()).toList();
        return String.join("/", nonEmpty);
    }

    /** 累积中的 section：标题行（可为 null = 文件头 preamble）+ 内容行及其 1-based 行号 */
    private static final class Section {
        private final String titleLine;
        private final int titleLineNo;
        private final String path;
        private final List<String> lines = new ArrayList<>();
        private final List<Integer> lineNos = new ArrayList<>();

        Section(String titleLine, int titleLineNo, String path) {
            this.titleLine = titleLine;
            this.titleLineNo = titleLineNo;
            this.path = path;
            if (titleLine != null) {
                lines.add(titleLine);
                lineNos.add(titleLineNo);
            }
        }

        void addLine(String line, int lineNo) {
            lines.add(line);
            lineNos.add(lineNo);
        }

        boolean isEmpty() {
            return lines.isEmpty();
        }

        String path() { return path; }
        List<String> lines() { return lines; }
        List<Integer> lineNos() { return lineNos; }
    }
}
