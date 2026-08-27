package com.luciano.skill;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
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
 * 备忘录技能。
 * 按用户隔离存储待办清单,落盘到 memo.json(已 gitignore)。
 * 触发词:
 * - 添加:"记一下 xxx / 记住 xxx / 帮我记 xxx"
 * - 删除:"删除待办 序号/内容 / 完成待办 ..."
 * - 查询:"我的待办 / 备忘录 / 清单"
 */
@Component
public class MemoSkill implements Skill {

    private static final Logger log = LoggerFactory.getLogger(MemoSkill.class);

    private static final Path STORE_PATH = Paths.get("memo.json");

    private static final List<String> ADD_WORDS = List.of("记一下", "帮我记", "添加待办", "加个待办", "记住", "记录一下", "记录:", "记录");
    private static final List<String> DEL_WORDS = List.of("删除待办", "删掉待办", "完成待办", "划掉");
    private static final List<String> QUERY_WORDS = List.of("我的待办", "待办", "备忘录", "清单");

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final Map<String, List<String>> memos = new ConcurrentHashMap<>();

    @PostConstruct
    public void init() {
        load();
    }

    @Override
    public String name() {
        return "memo";
    }

    @Override
    public boolean match(String text) {
        return containsAny(text, ADD_WORDS) || containsAny(text, DEL_WORDS) || containsAny(text, QUERY_WORDS);
    }

    @Override
    public String execute(String userId, String text) {
        String content = extract(text, ADD_WORDS);
        if (content != null) {
            return add(userId, content);
        }
        if (containsAny(text, ADD_WORDS)) {
            return "想让我记什么?格式:记一下 + 内容";
        }
        content = extract(text, DEL_WORDS);
        if (content != null) {
            return remove(userId, content);
        }
        // 纯功能询问:只在没有记录/删除操作时才介绍技能
        if (text.contains("功能") || text.contains("是什么") || text.contains("有什么用") || text.contains("怎么用")) {
            return "备忘录技能:说\"记一下 xxx\"添加待办,\"我的待办\"查看清单,\"删除待办 序号\"删除。";
        }
        return list(userId);
    }

    private String add(String userId, String content) {
        memos.computeIfAbsent(userId, k -> new ArrayList<>()).add(content);
        save();
        return "已记下: " + content + "\n说\"我的待办\"可查看清单。";
    }

    private String remove(String userId, String content) {
        List<String> list = memos.get(userId);
        if (list == null || list.isEmpty()) {
            return "你还没有待办哦~";
        }
        String target = content.trim();
        // 优先按序号删除
        try {
            int idx = Integer.parseInt(target);
            if (idx < 1 || idx > list.size()) {
                return "序号不存在,你共有 " + list.size() + " 条待办。";
            }
            String removed = list.remove(idx - 1);
            save();
            return "已删除待办: " + removed;
        } catch (NumberFormatException ignored) {
            // 按内容模糊删除
        }
        for (int i = list.size() - 1; i >= 0; i--) {
            if (list.get(i).contains(target)) {
                String removed = list.remove(i);
                save();
                return "已删除待办: " + removed;
            }
        }
        return "没找到包含 \"" + target + "\" 的待办。";
    }

    private String list(String userId) {
        List<String> list = memos.get(userId);
        if (list == null || list.isEmpty()) {
            return "你还没有待办,说\"记一下 xxx\"即可添加。";
        }
        StringBuilder sb = new StringBuilder("你的待办清单:\n");
        for (int i = 0; i < list.size(); i++) {
            sb.append(i + 1).append(". ").append(list.get(i)).append("\n");
        }
        return sb.toString().trim();
    }

    /** 提取触发词后面的内容,无内容返回 null */
    private String extract(String text, List<String> words) {
        for (String w : words) {
            int idx = text.indexOf(w);
            if (idx >= 0) {
                String rest = text.substring(idx + w.length()).trim();
                return rest.isEmpty() ? null : rest;
            }
        }
        return null;
    }

    private boolean containsAny(String text, List<String> words) {
        for (String w : words) {
            if (text.contains(w)) {
                return true;
            }
        }
        return false;
    }

    private synchronized void save() {
        try {
            objectMapper.writeValue(STORE_PATH.toFile(), memos);
        } catch (IOException e) {
            log.warn("保存备忘录失败: {}", e.getMessage());
        }
    }

    private synchronized void load() {
        if (!Files.exists(STORE_PATH)) {
            return;
        }
        try {
            Map<String, List<String>> raw = objectMapper.readValue(STORE_PATH.toFile(),
                    new TypeReference<LinkedHashMap<String, List<String>>>() {
                    });
            memos.putAll(raw);
            log.info("已从 {} 恢复 {} 个用户的备忘录", STORE_PATH.toAbsolutePath(), memos.size());
        } catch (IOException e) {
            log.warn("读取备忘录失败: {}", e.getMessage());
        }
    }
}
