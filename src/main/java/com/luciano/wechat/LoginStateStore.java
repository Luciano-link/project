package com.luciano.wechat;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.wechat.ilink.sdk.core.login.LoginContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * 登录态持久化(多会话版):把每个会话的 {@link LoginContext} 按 sessionId
 * 存进同一个本地 JSON 文件,重启后按会话逐个恢复,无需重新扫码。
 */
@Component
public class LoginStateStore {

    private static final Logger log = LoggerFactory.getLogger(LoginStateStore.class);

    private static final Path STORE_PATH = Paths.get("wechat-logins.json");

    private final ObjectMapper objectMapper = new ObjectMapper();

    /** 内存态:sessionId → 登录态快照。 */
    private final Map<String, LoginState> states = new LinkedHashMap<>();

    public LoginStateStore() {
        load();
    }

    /**
     * 保存某个会话的登录上下文。
     */
    public synchronized void save(String sessionId, LoginContext context) {
        if (sessionId == null || context == null) {
            return;
        }
        states.put(sessionId, LoginState.from(context));
        persist();
    }

    /**
     * 读取某个会话的登录上下文,不存在返回 null。
     */
    public synchronized LoginContext load(String sessionId) {
        LoginState state = states.get(sessionId);
        return state == null ? null : state.toLoginContext();
    }

    /**
     * 删除某个会话的登录态(会话注销时调用)。
     */
    public synchronized void remove(String sessionId) {
        if (states.remove(sessionId) != null) {
            persist();
        }
    }

    /**
     * 所有已保存登录态的会话 id(启动时用于免扫码恢复)。
     */
    public synchronized Set<String> allSessionIds() {
        return Set.copyOf(states.keySet());
    }

    private void load() {
        if (!Files.exists(STORE_PATH)) {
            return;
        }
        try {
            Map<String, LoginState> raw = objectMapper.readValue(STORE_PATH.toFile(),
                    new TypeReference<Map<String, LoginState>>() {});
            states.putAll(raw);
            log.info("已从 {} 恢复 {} 个会话的登录态", STORE_PATH.toAbsolutePath(), states.size());
        } catch (IOException e) {
            log.warn("读取登录态失败: {}", e.getMessage());
        }
    }

    private void persist() {
        try {
            objectMapper.writeValue(STORE_PATH.toFile(), states);
        } catch (IOException e) {
            log.warn("保存登录态失败: {}", e.getMessage());
        }
    }

    /**
     * 可序列化的登录态快照。
     */
    public static class LoginState {
        public String botToken;
        public String userId;
        public String botId;
        public String baseUrl;

        public static LoginState from(LoginContext context) {
            LoginState state = new LoginState();
            state.botToken = context.getBotToken();
            state.userId = context.getUserId();
            state.botId = context.getBotId();
            state.baseUrl = context.getBaseUrl();
            return state;
        }

        public LoginContext toLoginContext() {
            return new LoginContext(botToken, userId, botId, baseUrl);
        }
    }
}
