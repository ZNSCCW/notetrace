package com.notetrace.web;

import java.util.List;

import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;

import com.notetrace.chunk.ChunkRepository;
import com.notetrace.document.Document;
import com.notetrace.document.DocumentRepository;
import com.notetrace.qa.QaService;

/**
 * Web 页面控制器（FR-16）：问答页 + 入库状态页。
 */
@Controller
public class WebController {

    /** 文档状态行：文档 + 其 chunk 数 */
    public record DocumentStatus(Document doc, long chunkCount) {
    }

    private final QaService qaService;
    private final DocumentRepository documentRepository;
    private final ChunkRepository chunkRepository;

    public WebController(QaService qaService,
                         DocumentRepository documentRepository,
                         ChunkRepository chunkRepository) {
        this.qaService = qaService;
        this.documentRepository = documentRepository;
        this.chunkRepository = chunkRepository;
    }

    @GetMapping("/")
    public String index() {
        return "qa";
    }

    /** 问答（FR-10/FR-11）：提交问题与 AI 选择（api=DeepSeek / local=Ollama），渲染带引用回答 */
    @PostMapping("/qa")
    public String ask(@RequestParam String question,
                      @RequestParam(name = "ai", defaultValue = "api") String aiChoice,
                      Model model) {
        QaService.QaResult result = qaService.ask(question, aiChoice);
        model.addAttribute("question", question);
        model.addAttribute("aiChoice", aiChoice);
        model.addAttribute("result", result);
        model.addAttribute("answerHtml", QaService.renderAnswerHtml(result.answer()));
        return "qa";
    }

    /** 入库状态页 */
    @GetMapping("/documents")
    public String documents(Model model) {
        List<DocumentStatus> rows = documentRepository.findAll().stream()
                .sorted((a, b) -> a.getUpdatedAt().compareTo(b.getUpdatedAt()) * -1)
                .map(d -> new DocumentStatus(d, chunkRepository.countByDocumentId(d.getId())))
                .toList();
        model.addAttribute("documents", rows);
        return "documents";
    }
}
