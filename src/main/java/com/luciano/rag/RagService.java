package com.luciano.rag;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * 极简关键词检索版 RAG。
 * 知识库来自 rag-knowledge.json:每条知识含关键词列表与内容。
 * 用户消息命中任一关键词即返回对应内容,由路由注入 LLM Prompt 增强回答。
 * rag.enabled 为开关,可对比开启/关闭时的回答差异。
 */
@Component
public class RagService {

    private static final Logger log = LoggerFactory.getLogger(RagService.class);

    @Value("${rag.enabled:false}")
    private boolean enabled;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final List<Knowledge> knowledge = new ArrayList<>();

    @PostConstruct
    public void init() {
        try (InputStream in = getClass().getResourceAsStream("/rag-knowledge.json")) {
            if (in == null) {
                log.warn("rag-knowledge.json 不存在,RAG 不可用");
                return;
            }
            Knowledge[] arr = objectMapper.readValue(in, Knowledge[].class);
            knowledge.addAll(Arrays.asList(arr));
            log.info("RAG 知识库已加载 {} 条,enabled = {}", knowledge.size(), enabled);
        } catch (IOException e) {
            log.warn("加载 RAG 知识库失败: {}", e.getMessage());
        }
    }

    public boolean isEnabled() {
        return enabled;
    }

    /** 关键词检索:返回命中的知识内容;未开启或未命中返回 null */
    public String retrieve(String text) {
        if (!enabled || knowledge.isEmpty()) {
            return null;
        }
        for (Knowledge k : knowledge) {
            for (String keyword : k.keywords) {
                if (text.contains(keyword)) {
                    log.info("RAG 命中关键词: {}", keyword);
                    return k.content;
                }
            }
        }
        return null;
    }

    /** 知识条目:关键词列表 + 参考内容 */
    public static class Knowledge {
        public List<String> keywords;
        public String content;
    }
}
