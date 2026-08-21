package com.luciano.conversation;

import com.alibaba.dashscope.common.Message;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 对话上下文服务。
 * 按用户隔离存储多轮对话历史,支持滑动窗口裁剪和摘要压缩。
 * 历史持久化到本地 JSON 文件,重启后恢复(该文件已加入 .gitignore)。
 * 线程安全:同一用户的操作通过 synchronized 保证一致性。
 */
@Service
public class ConversationService {

    private static final Logger log = LoggerFactory.getLogger(ConversationService.class);

    /** 最多维护的会话用户数,超出后清理最不活跃的会话 */
    private static final int MAX_USERS = 500;

    /** 记忆文件路径(已加入 .gitignore,不提交) */
    private static final Path STORE_PATH = Paths.get("wechat-memory.json");

    private final ObjectMapper objectMapper = new ObjectMapper();

    private final ConcurrentHashMap<String, Deque<Message>> histories = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, String> summaries = new ConcurrentHashMap<>();

    @PostConstruct
    public void init() {
        loadFromDisk();
    }

    /** 追加一条消息(用户或助手),并持久化 */
    public synchronized void addMessage(String userId, Message message) {
        if (!histories.containsKey(userId) && histories.size() >= MAX_USERS) {
            evictIdleUser();
        }
        Deque<Message> deque = histories.computeIfAbsent(userId, k -> new ArrayDeque<>());
        deque.addLast(message);
        saveToDisk();
    }

    /** 获取该用户的全部历史消息(不可变副本) */
    public synchronized List<Message> getMessages(String userId) {
        Deque<Message> deque = histories.get(userId);
        return deque == null ? List.of() : List.copyOf(deque);
    }

    /** 裁剪:只保留最近 keep 条,返回被移除的消息(供生成摘要) */
    public synchronized List<Message> trim(String userId, int keep) {
        Deque<Message> deque = histories.get(userId);
        if (deque == null || deque.isEmpty()) {
            return List.of();
        }
        List<Message> removed = new ArrayList<>();
        while (deque.size() > keep) {
            removed.add(deque.removeFirst());
        }
        saveToDisk();
        return removed;
    }

    /** 覆盖该用户的摘要 */
    public synchronized void setSummary(String userId, String summary) {
        summaries.put(userId, summary);
    }

    /** 获取该用户的摘要,无则返回 null */
    public synchronized String getSummary(String userId) {
        return summaries.get(userId);
    }

    /** 清理最不活跃的会话(历史条数最少的用户),防止内存膨胀 */
    private void evictIdleUser() {
        String idleUser = null;
        int minSize = Integer.MAX_VALUE;
        for (var entry : histories.entrySet()) {
            int size = entry.getValue().size();
            if (size < minSize) {
                minSize = size;
                idleUser = entry.getKey();
            }
        }
        if (idleUser != null) {
            histories.remove(idleUser);
            summaries.remove(idleUser);
        }
    }

    /** 当前历史消息条数 */
    public synchronized int size(String userId) {
        Deque<Message> deque = histories.get(userId);
        return deque == null ? 0 : deque.size();
    }

    /** 清空该用户上下文 */
    public synchronized void clear(String userId) {
        histories.remove(userId);
        summaries.remove(userId);
        saveToDisk();
    }

    /** 持久化所有用户历史到本地文件 */
    private synchronized void saveToDisk() {
        try {
            Map<String, List<Entry>> raw = new LinkedHashMap<>();
            histories.forEach((userId, deque) -> {
                List<Entry> entries = new ArrayList<>(deque.size());
                for (Message m : deque) {
                    entries.add(new Entry(m.getRole(), m.getContent()));
                }
                raw.put(userId, entries);
            });
            objectMapper.writeValue(STORE_PATH.toFile(), raw);
        } catch (IOException e) {
            log.warn("保存对话记忆失败: {}", e.getMessage());
        }
    }

    /** 启动时从本地文件恢复历史 */
    private synchronized void loadFromDisk() {
        if (!Files.exists(STORE_PATH)) {
            return;
        }
        try {
            Map<String, List<Entry>> raw = objectMapper.readValue(STORE_PATH.toFile(),
                    new TypeReference<Map<String, List<Entry>>>() {
                    });
            raw.forEach((userId, entries) -> {
                Deque<Message> deque = new ArrayDeque<>();
                for (Entry e : entries) {
                    deque.addLast(Message.builder().role(e.role).content(e.content).build());
                }
                if (!deque.isEmpty()) {
                    histories.put(userId, deque);
                }
            });
            log.info("已从 {} 恢复 {} 个用户的对话记忆", STORE_PATH.toAbsolutePath(), histories.size());
        } catch (IOException e) {
            log.warn("读取对话记忆失败: {}", e.getMessage());
        }
    }

    /** 可序列化的消息快照 */
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
