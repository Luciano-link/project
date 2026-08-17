package com.luciano.wechat;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 对话记忆持久化:按用户保存最近若干条历史消息到本地 JSON,重启后恢复。
 *
 * <p>记忆按 {@code userId} 隔离(不同微信用户互不可见),每个用户最多保留
 * {@link #MAX_MESSAGES} 条消息(user 与 assistant 各算一条),超出时丢弃最旧的消息。
 */
@Component
public class MemoryStore {

    private static final Logger log = LoggerFactory.getLogger(MemoryStore.class);

    private static final Path STORE_PATH = Paths.get("wechat-memory.json");

    /** 每个用户最多保留的历史消息条数。 */
    private static final int MAX_MESSAGES = 20;

    private final ObjectMapper objectMapper = new ObjectMapper();

    /** 内存态:userId -> 历史消息列表。 */
    private final Map<String, List<DashScopeClient.HistoryMessage>> memory = new ConcurrentHashMap<>();

    public MemoryStore() {
        load();
    }

    /**
     * 取某个用户的对话历史快照(不存在则返回空列表)。
     */
    public List<DashScopeClient.HistoryMessage> get(String userId) {
        List<DashScopeClient.HistoryMessage> list = memory.computeIfAbsent(userId, k -> new ArrayList<>());
        synchronized (list) {
            return new ArrayList<>(list);
        }
    }

    /**
     * 追加一轮对话(用户消息 + Bot 回复),截断到最近 {@link #MAX_MESSAGES} 条后落盘。
     */
    public void append(String userId, DashScopeClient.HistoryMessage userMsg, DashScopeClient.HistoryMessage assistantMsg) {
        List<DashScopeClient.HistoryMessage> list = memory.computeIfAbsent(userId, k -> new ArrayList<>());
        synchronized (list) {
            list.add(userMsg);
            list.add(assistantMsg);
            while (list.size() > MAX_MESSAGES) {
                list.remove(0);
            }
        }
        save();
    }

    private void load() {
        if (!Files.exists(STORE_PATH)) {
            return;
        }
        try {
            Map<String, List<Entry>> raw = objectMapper.readValue(STORE_PATH.toFile(),
                    new TypeReference<Map<String, List<Entry>>>() {});
            raw.forEach((userId, entries) -> {
                List<DashScopeClient.HistoryMessage> list = new ArrayList<>();
                for (Entry e : entries) {
                    list.add(new DashScopeClient.HistoryMessage(e.role, e.content));
                }
                memory.put(userId, list);
            });
            log.info("已从 {} 恢复 {} 个用户的对话记忆", STORE_PATH.toAbsolutePath(), memory.size());
        } catch (IOException e) {
            log.warn("读取对话记忆失败: {}", e.getMessage());
        }
    }

    private void save() {
        try {
            Map<String, List<Entry>> raw = new LinkedHashMap<>();
            memory.forEach((userId, list) -> {
                synchronized (list) {
                    List<Entry> entries = new ArrayList<>(list.size());
                    for (DashScopeClient.HistoryMessage m : list) {
                        entries.add(new Entry(m.role(), m.content()));
                    }
                    raw.put(userId, entries);
                }
            });
            objectMapper.writeValue(STORE_PATH.toFile(), raw);
        } catch (IOException e) {
            log.warn("保存对话记忆失败: {}", e.getMessage());
        }
    }

    /**
     * 可序列化的消息快照(用普通 POJO 而非 record,避免手动 ObjectMapper 反序列化 record 的兼容问题)。
     */
    public static class Entry {
        public String role;
        public String content;

        public Entry() {
        }

        public Entry(String role, String content) {
            this.role = role;
            this.content = content;
        }
    }
}
