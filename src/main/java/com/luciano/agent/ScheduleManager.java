package com.luciano.agent;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.wechat.ilink.sdk.ILinkClient;
import com.luciano.wechat.UserClientRegistry;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 日程提醒管理:登记日程,周期性扫描,到"出发前提醒时间"自动向用户推送提醒。
 * 落盘 schedule.json 持久化,重启保留。提醒推送通过 UserClientRegistry 定位用户会话。
 */
@Component
public class ScheduleManager {

    private static final Logger log = LoggerFactory.getLogger(ScheduleManager.class);

    private static final Path STORE_PATH = Paths.get("schedule.json");
    private static final DateTimeFormatter FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    /** 出发前提前提醒的分钟数 */
    private static final long REMIND_AHEAD_MINUTES = 20;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final Map<String, ScheduleItem> items = new ConcurrentHashMap<>();
    private final UserClientRegistry userClientRegistry;

    public ScheduleManager(UserClientRegistry userClientRegistry) {
        this.userClientRegistry = userClientRegistry;
    }

    /** 日程条目(可序列化落盘) */
    public static class ScheduleItem {
        public String id;
        public String userId;
        public String title;
        public String location;
        /** 出发时间(yyyy-MM-dd HH:mm) */
        public String when;
        /** 提醒触发时间戳 */
        public long remindAt;
        public boolean reminded;

        public ScheduleItem() {
        }

        public ScheduleItem(String id, String userId, String title, String location, String when, long remindAt) {
            this.id = id;
            this.userId = userId;
            this.title = title;
            this.location = location;
            this.when = when;
            this.remindAt = remindAt;
        }
    }

    @PostConstruct
    public void init() {
        load();
    }

    /** 登记日程,自动按"出发前 REMIND_AHEAD_MINUTES 分钟"计算提醒时间 */
    public ScheduleItem add(String userId, String title, String location, String when) {
        long whenTs = LocalDateTime.parse(when, FMT)
                .atZone(ZoneId.systemDefault()).toInstant().toEpochMilli();
        ScheduleItem item = new ScheduleItem(UUID.randomUUID().toString(), userId, title, location, when,
                whenTs - REMIND_AHEAD_MINUTES * 60_000);
        items.put(item.id, item);
        save();
        log.info("已登记日程: userId = {}, when = {}, title = {}", userId, when, title);
        return item;
    }

    public List<ScheduleItem> all() {
        return new ArrayList<>(items.values());
    }

    /** 周期性扫描:到达提醒时间且未提醒则推送 */
    @Scheduled(fixedDelay = 30 * 1000)
    public void scanAndRemind() {
        long now = System.currentTimeMillis();
        for (ScheduleItem item : items.values()) {
            if (item.reminded || now < item.remindAt) {
                continue;
            }
            // 提醒窗口已过(超过 1 小时)不再补发过时提醒,避免离线用户反复重试
            if (now - item.remindAt > 60 * 60 * 1000L) {
                item.reminded = true;
                save();
                continue;
            }
            ILinkClient client = userClientRegistry.get(item.userId);
            if (client == null) {
                log.info("用户 {} 当前无在线会话,跳过提醒", item.userId);
                continue;
            }
            try {
                String text = "⏰ 提醒:" + item.title
                        + (item.location != null && !item.location.isBlank() ? " @" + item.location : "")
                        + "\n出发时间:" + item.when + ",建议提前出发。";
                client.sendText(item.userId, text);
                item.reminded = true;
                save();
                log.info("已推送提醒: userId = {}, when = {}, title = {}", item.userId, item.when, item.title);
            } catch (Exception e) {
                log.error("提醒推送失败: {}", item.id, e);
            }
        }
    }

    private synchronized void save() {
        try {
            objectMapper.writeValue(STORE_PATH.toFile(), items);
        } catch (IOException e) {
            log.warn("保存日程失败: {}", e.getMessage());
        }
    }

    private synchronized void load() {
        if (!Files.exists(STORE_PATH)) {
            return;
        }
        try {
            Map<String, ScheduleItem> raw = objectMapper.readValue(STORE_PATH.toFile(),
                    new TypeReference<Map<String, ScheduleItem>>() {
                    });
            items.putAll(raw);
            log.info("已从 {} 恢复 {} 条日程", STORE_PATH.toAbsolutePath(), items.size());
        } catch (IOException e) {
            log.warn("读取日程失败: {}", e.getMessage());
        }
    }
}
