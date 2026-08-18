package com.notetrace.qa;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;

import org.junit.jupiter.api.Test;

import com.notetrace.ai.ChatProvider;
import com.notetrace.ai.ChatRouter;
import com.notetrace.ai.deepseek.DeepSeekChatProvider;
import com.notetrace.ai.mock.MockChatProvider;
import com.notetrace.ai.ollama.OllamaChatProvider;
import com.notetrace.qa.QaService.QaResult;
import com.notetrace.qa.QaService.Reference;
import com.notetrace.search.SearchHit;
import com.notetrace.search.VectorSearchService;

/**
 * QaService 防伪机制单测：引用编号校验、越界剔除、无结果降级。
 */
class QaServiceTest {

    private static SearchHit hit(long id, String content, double score) {
        return new SearchHit(id, 1L, "并发笔记.md", content, "线程池/拒绝策略", 3, 5, score);
    }

    private static Reference ref(int index, long chunkId, String title, String path, int start, int end) {
        return new Reference(index, chunkId, title, path, start, end, "摘要", "原文全文");
    }

    @Test
    void validReferencesAreKeptInvalidAreDropped() {
        List<SearchHit> hits = List.of(hit(1, "AbortPolicy 抛异常", 0.9), hit(2, "CallerRunsPolicy 调用者执行", 0.8));
        // 回答引用 [1]（合法）与 [9]（越界，应剔除）
        List<Reference> refs = QaService.extractValidReferences("拒绝策略是 AbortPolicy[1] 或[9] 编造来源", hits);
        assertThat(refs).hasSize(1);
        assertThat(refs.get(0).chunkId()).isEqualTo(1L);
        assertThat(refs.get(0).documentTitle()).isEqualTo("并发笔记.md");
        assertThat(refs.get(0).sectionPath()).isEqualTo("线程池/拒绝策略");
        assertThat(refs.get(0).startLine()).isEqualTo(3);
        assertThat(refs.get(0).endLine()).isEqualTo(5);
    }

    @Test
    void duplicateReferencesAreDeduplicated() {
        List<SearchHit> hits = List.of(hit(1, "内容A", 0.9), hit(2, "内容B", 0.8));
        List<Reference> refs = QaService.extractValidReferences("答案[1] 和 [1] 还有 [2]", hits);
        assertThat(refs).hasSize(2);
    }

    @Test
    void noReferencesInAnswerReturnsEmpty() {
        List<SearchHit> hits = List.of(hit(1, "内容A", 0.9));
        assertThat(QaService.extractValidReferences("没有任何引用的回答", hits)).isEmpty();
    }

    @Test
    void zeroAndOversizedReferenceNumbersAreIgnoredWithoutCrash() {
        List<SearchHit> hits = List.of(hit(1, "内容A", 0.9));
        // [0] 越下界、超长数字串（>6 位）均跳过，[1] 保留——不抛 NumberFormatException
        List<Reference> refs = QaService.extractValidReferences(
                "回答[0] 和[99999999999999999999] 还有[1]", hits);
        assertThat(refs).hasSize(1);
        assertThat(refs.get(0).chunkId()).isEqualTo(1L);
    }

    @Test
    void emptySearchResultReturnsFallbackWithoutCallingChat() {
        VectorSearchService search = mock(VectorSearchService.class);
        when(search.search("知识库外的问题", 8)).thenReturn(List.of());
        ChatRouter router = new ChatRouter(
                mock(DeepSeekChatProvider.class), mock(OllamaChatProvider.class), mock(MockChatProvider.class));
        QaService qa = new QaService(search, new StubReranker(), router);

        QaResult result = qa.ask("知识库外的问题");
        assertThat(result.answer()).contains("没有相关内容");
        assertThat(result.references()).isEmpty();
    }

    /** 测试用 Reranker 替身：原样返回，不重排 */
    static class StubReranker extends com.notetrace.search.Reranker {
        @Override
        public List<SearchHit> rerank(String query, List<SearchHit> hits, int topK) {
            return hits;
        }
    }
}
