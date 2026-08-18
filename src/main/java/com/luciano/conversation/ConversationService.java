package com.luciano.conversation;

import com.alibaba.dashscope.common.Message;
import org.springframework.stereotype.Service;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 对话上下文服务。
 * 按用户隔离存储多轮对话历史,支持滑动窗口裁剪和摘要压缩。
 * 线程安全:同一用户的操作通过 synchronized 保证一致性。
 * 内存治理:达到最大用户数时清理最不活跃的会话,防止长期运行内存膨胀。
 */
@Service
public class ConversationService {

    /** 最多维护的会话用户数,超出后清理最不活跃的会话 */
    private static final int MAX_USERS = 500;

    private final ConcurrentHashMap<String, Deque<Message>> histories = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, String> summaries = new ConcurrentHashMap<>();

    /** 追加一条消息(用户或助手) */
    public synchronized void addMessage(String userId, Message message) {
        if (!histories.containsKey(userId) && histories.size() >= MAX_USERS) {
            evictIdleUser();
        }
        Deque<Message> deque = histories.computeIfAbsent(userId, k -> new ArrayDeque<>());
        deque.addLast(message);
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
    }
}
