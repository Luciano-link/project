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
import java.util.Comparator;
import java.util.List;

/**
 * 极简关键词检索版 RAG。
 * 知识库来自 rag-knowledge.json。
 * 支持可选 cities 字段:有城市限定时,仅当用户文本提到该城市才命中,避免串城。
 * 命中多条时按 priority(高优先)与最长关键词(更具体)排序,返回最多 {@link #MAX_HITS} 条。
 */
@Component
public class RagService {

    private static final Logger log = LoggerFactory.getLogger(RagService.class);

    /** 单次最多注入的知识条数,避免 Prompt 过长 */
    private static final int MAX_HITS = 3;

    /** 未配置 priority 时的默认值 */
    private static final int DEFAULT_PRIORITY = 50;

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

    /**
     * 关键词检索:返回命中的知识内容(多条用分隔符拼接);未开启或未命中返回 null。
     * 带 cities 的条目必须用户文本也提到对应城市才会命中。
     */
    public String retrieve(String text) {
        if (!enabled || knowledge.isEmpty() || text == null || text.isBlank()) {
            return null;
        }
        List<ScoredHit> hits = new ArrayList<>();
        for (Knowledge k : knowledge) {
            if (!cityAllowed(text, k)) {
                continue;
            }
            int keywordLen = bestMatchingKeywordLength(text, k);
            if (keywordLen <= 0) {
                continue;
            }
            hits.add(new ScoredHit(k, keywordLen));
        }
        if (hits.isEmpty()) {
            return null;
        }
        hits.sort(Comparator
                .comparingInt((ScoredHit h) -> h.knowledge.priorityOrDefault()).reversed()
                .thenComparingInt(h -> h.keywordLength).reversed());
        List<String> contents = new ArrayList<>();
        int count = Math.min(MAX_HITS, hits.size());
        for (int i = 0; i < count; i++) {
            ScoredHit hit = hits.get(i);
            contents.add(hit.knowledge.content);
            log.info("RAG 命中: priority={}, keywordLen={}, cities={}, sample={}",
                    hit.knowledge.priorityOrDefault(), hit.keywordLength,
                    hit.knowledge.cities, sampleKeywords(hit.knowledge));
        }
        return String.join("\n---\n", contents);
    }

    /** 无 cities 或空 = 通用知识;有 cities 则用户文本需包含其中任一城市名 */
    boolean cityAllowed(String text, Knowledge k) {
        if (k.cities == null || k.cities.isEmpty()) {
            return true;
        }
        for (String city : k.cities) {
            if (city != null && !city.isBlank() && text.contains(city)) {
                return true;
            }
        }
        return false;
    }

    /** 返回命中的最长关键词长度,未命中返回 0 */
    int bestMatchingKeywordLength(String text, Knowledge k) {
        if (k.keywords == null) {
            return 0;
        }
        int best = 0;
        for (String keyword : k.keywords) {
            if (keyword != null && !keyword.isBlank() && text.contains(keyword)) {
                best = Math.max(best, keyword.length());
            }
        }
        return best;
    }

    private String sampleKeywords(Knowledge k) {
        if (k.keywords == null || k.keywords.isEmpty()) {
            return "";
        }
        return k.keywords.get(0);
    }

    private record ScoredHit(Knowledge knowledge, int keywordLength) {
    }

    /** 知识条目:可选城市限定 + 优先级 + 关键词 + 参考内容 */
    public static class Knowledge {
        /** 可选。非空时仅当用户提到这些城市之一才可命中,用于城市专属攻略 */
        public List<String> cities;
        /** 可选。数值越大越优先注入,模板类建议 90+,元信息建议 10 以下 */
        public Integer priority;
        public List<String> keywords;
        public String content;

        int priorityOrDefault() {
            return priority == null ? DEFAULT_PRIORITY : priority;
        }
    }
}
