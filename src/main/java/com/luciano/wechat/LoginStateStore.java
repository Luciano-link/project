package com.luciano.wechat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.wechat.ilink.sdk.core.login.LoginContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * 登录态持久化。
 * 登录成功后把微信 Bot 凭证存到本地文件,重启后免扫码恢复。
 * 注意:botToken 为敏感凭证,以明文存本地文件,该文件应加入 .gitignore 禁止提交。
 */
@Component
public class LoginStateStore {

    private static final Logger log = LoggerFactory.getLogger(LoginStateStore.class);

    private static final Path STORE_PATH = Paths.get("wechat-login.json");

    private final ObjectMapper objectMapper = new ObjectMapper();

    /** 保存登录上下文到本地文件 */
    public void save(LoginContext context) {
        if (context == null) {
            return;
        }
        try {
            objectMapper.writeValue(STORE_PATH.toFile(), LoginState.from(context));
            log.info("登录态已保存到 {}", STORE_PATH.toAbsolutePath());
        } catch (IOException e) {
            log.warn("保存登录态失败: {}", e.getMessage());
        }
    }

    /** 从本地文件读取登录上下文,不存在或失败返回 null */
    public LoginContext load() {
        if (!Files.exists(STORE_PATH)) {
            return null;
        }
        try {
            LoginState state = objectMapper.readValue(STORE_PATH.toFile(), LoginState.class);
            return state.toLoginContext();
        } catch (IOException e) {
            log.warn("读取登录态失败: {}", e.getMessage());
            return null;
        }
    }

    /** 可序列化的登录态快照 */
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
