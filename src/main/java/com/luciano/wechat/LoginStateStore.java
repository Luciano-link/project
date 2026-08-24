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

/**
 * 多会话登录态持久化。
 * 以 sessionId 为 key 隔离保存各用户登录凭证,重启后按会话分别恢复。
 * 注意:botToken 为敏感凭证,以明文存本地文件,该文件已加入 .gitignore 禁止提交。
 */
@Component
public class LoginStateStore {

    private static final Logger log = LoggerFactory.getLogger(LoginStateStore.class);

    private static final Path STORE_PATH = Paths.get("wechat-sessions.json");

    private final ObjectMapper objectMapper = new ObjectMapper();

    /** 保存某会话的登录上下文(多用户并发登录时保护文件写一致性) */
    public synchronized void save(String sessionId, LoginContext context) {
        if (context == null) {
            return;
        }
        try {
            Map<String, LoginState> all = loadRaw();
            all.put(sessionId, LoginState.from(context));
            objectMapper.writeValue(STORE_PATH.toFile(), all);
            log.info("会话 {} 登录态已保存到 {}", sessionId, STORE_PATH.toAbsolutePath());
        } catch (IOException e) {
            log.warn("保存会话 {} 登录态失败: {}", sessionId, e.getMessage());
        }
    }

    /** 读取全部已保存的登录上下文:sessionId -> LoginContext */
    public Map<String, LoginContext> loadAll() {
        Map<String, LoginContext> result = new LinkedHashMap<>();
        loadRaw().forEach((sessionId, state) -> result.put(sessionId, state.toLoginContext()));
        return result;
    }

    /** 删除某会话的持久化登录态 */
    public synchronized void remove(String sessionId) {
        Map<String, LoginState> all = loadRaw();
        if (all.remove(sessionId) != null) {
            try {
                objectMapper.writeValue(STORE_PATH.toFile(), all);
                log.info("会话 {} 登录态已清除", sessionId);
            } catch (IOException e) {
                log.warn("清除会话 {} 登录态失败: {}", sessionId, e.getMessage());
            }
        }
    }

    private synchronized Map<String, LoginState> loadRaw() {
        if (!Files.exists(STORE_PATH)) {
            return new LinkedHashMap<>();
        }
        try {
            return objectMapper.readValue(STORE_PATH.toFile(),
                    new TypeReference<Map<String, LoginState>>() {
                    });
        } catch (IOException e) {
            log.warn("读取登录态失败: {}", e.getMessage());
            return new LinkedHashMap<>();
        }
    }

    /** 可序列化的登录态快照 */
    public static class LoginState {
        public String botToken;
        public String userId;
        public String botId;
        public String baseUrl;

        public LoginState() {
        }

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
