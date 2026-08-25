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
 * 知识库来自 rag-knowledge.json。
 * 支持可选 cities 字段:有城市限定时,仅当用户文本提到该城市才命中,避免串城。
 * 可返回多条通用知识拼接(最多 {@link #MAX_HITS} 条)。
 */
@Component
public class RagService {

    private static final Logger log = LoggerFactory.getLogger(RagService.class);

    /** 单次最多注入的知识条数,避免 Prompt 过长 */
    private static final int MAX_HITS = 3;

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
        List<String> hits = new ArrayList<>();
        for (Knowledge k : knowledge) {
            if (hits.size() >= MAX_HITS) {
                break;
            }
            if (!cityAllowed(text, k) || !keywordHit(text, k)) {
                continue;
            }
            hits.add(k.content);
            log.info("RAG 命中: cities={}, keywords 样本={}", k.cities, sampleKeywords(k));
        }
        if (hits.isEmpty()) {
            return null;
        }
        return String.join("\n---\n", hits);
    }

    /** 无 cities 或空 = 通用知识;有 cities 则用户文本需包含其中任一城市名 */
    private boolean cityAllowed(String text, Knowledge k) {
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

    private boolean keywordHit(String text, Knowledge k) {
        if (k.keywords == null) {
            return false;
        }
        for (String keyword : k.keywords) {
            if (keyword != null && !keyword.isBlank() && text.contains(keyword)) {
                return true;
            }
        }
        return false;
    }

    private String sampleKeywords(Knowledge k) {
        if (k.keywords == null || k.keywords.isEmpty()) {
            return "";
        }
        return k.keywords.get(0);
    }

    /** 知识条目:可选城市限定 + 关键词 + 参考内容 */
    public static class Knowledge {
        /** 可选。非空时仅当用户提到这些城市之一才可命中,用于城市专属攻略 */
        public List<String> cities;
        public List<String> keywords;
        public String content;
    }
}
