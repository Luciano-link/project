package com.luciano.wechat;

import com.github.wechat.ilink.sdk.core.login.LoginContext;
import com.github.wechat.ilink.sdk.core.login.LoginStatus;
import com.github.wechat.ilink.sdk.core.model.WeixinMessage;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * 多用户会话管理(核心):用 {@code Map<sessionId, WechatSession>} 保存所有用户的会话。
 *
 * <p>创建会话 → 用户扫码绑定专属 client → 登录态持久化,重启后免扫码恢复;
 * 多个用户可同时登录、同时聊天,互不干扰。
 *
 * <p><b>并发扫码登录</b>:login 的 HTTP 请求(申请二维码)统一提交到共享线程池执行,
 * 避免多个用户同时扫码时互相阻塞;二维码的扫码轮询由 SDK 内部异步完成。
 */
@Service
public class WechatSessionManager {

    private static final Logger log = LoggerFactory.getLogger(WechatSessionManager.class);

    /** 会话集合:sessionId → 会话(核心数据结构)。 */
    private final Map<String, WechatSession> sessions = new ConcurrentHashMap<>();

    /** 扫码登录线程池:并发申请二维码,防止登录 HTTP 请求互相阻塞。 */
    private final ExecutorService loginExecutor = Executors.newFixedThreadPool(4);

    /** 登录请求等待二维码的最长时间。 */
    private static final long LOGIN_TIMEOUT_SECONDS = 30;

    private final MessageDispatcher dispatcher;
    private final LoginStateStore loginStateStore;

    public WechatSessionManager(MessageDispatcher dispatcher, LoginStateStore loginStateStore) {
        this.dispatcher = dispatcher;
        this.loginStateStore = loginStateStore;
    }

    /**
     * 启动时恢复所有已保存登录态的会话,用户无需重新扫码。
     */
    @PostConstruct
    public void init() {
        for (String sessionId : loginStateStore.allSessionIds()) {
            try {
                createSession(sessionId);
                log.info("已恢复会话 {} ", sessionId);
            } catch (Exception e) {
                log.warn("恢复会话 {} 失败: {}", sessionId, e.getMessage());
            }
        }
    }

    /**
     * 创建新会话(分配一个专属 client),返回 sessionId。
     * 前端拿到 sessionId 后访问 /wechat/sessions/{id}/qrcode 扫码绑定。
     */
    public String createSession() {
        return createSession(UUID.randomUUID().toString().substring(0, 8));
    }

    private String createSession(String sessionId) {
        LoginContext saved = loginStateStore.load(sessionId);
        WechatSession session = new WechatSession(sessionId, dispatcher, loginStateStore, saved);
        sessions.put(sessionId, session);
        log.info("已创建会话 {} ({} 个会话在线)", sessionId, sessions.size());
        return sessionId;
    }

    /**
     * 注销会话:释放连接并删除登录态。不存在时返回 false。
     */
    public boolean removeSession(String sessionId) {
        WechatSession session = sessions.remove(sessionId);
        if (session == null) {
            return false;
        }
        session.close();
        loginStateStore.remove(sessionId);
        log.info("已注销会话 {} (剩余 {} 个)", sessionId, sessions.size());
        return true;
    }

    public WechatSession get(String sessionId) {
        return sessions.get(sessionId);
    }

    /**
     * 触发登录(取二维码):提交到登录线程池执行,并发扫码不会互相阻塞。
     * 返回二维码图片内容;已登录返回 null。
     */
    public String login(String sessionId) {
        WechatSession session = require(sessionId);
        try {
            Future<String> future = loginExecutor.submit(session::login);
            return future.get(LOGIN_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } catch (TimeoutException e) {
            throw new IllegalStateException("登录超时,请重试", e);
        } catch (Exception e) {
            throw new IllegalStateException("触发登录失败: " + e.getMessage(), e);
        }
    }

    public boolean isLoggedIn(String sessionId) {
        return require(sessionId).isLoggedIn();
    }

    public LoginStatus.Status getStatus(String sessionId) {
        return require(sessionId).getStatus();
    }

    public String getUserId(String sessionId) {
        return require(sessionId).getUserId();
    }

    public List<WeixinMessage> pollMessages(String sessionId) {
        return require(sessionId).pollMessages();
    }

    public void sendText(String sessionId, String toUserId, String text) throws Exception {
        require(sessionId).sendText(toUserId, text);
    }

    /**
     * 会话列表(调试/前端展示用)。
     */
    public List<Map<String, Object>> listSessions() {
        List<Map<String, Object>> result = new ArrayList<>();
        for (WechatSession session : sessions.values()) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("sessionId", session.getSessionId());
            item.put("loggedIn", session.isLoggedIn());
            item.put("status", session.getStatus().name());
            item.put("userId", session.getUserId());
            result.add(item);
        }
        return result;
    }

    private WechatSession require(String sessionId) {
        WechatSession session = sessions.get(sessionId);
        if (session == null) {
            throw new IllegalArgumentException("会话不存在: " + sessionId);
        }
        return session;
    }

    @PreDestroy
    public void destroy() {
        loginExecutor.shutdownNow();
        for (WechatSession session : sessions.values()) {
            session.close();
        }
        sessions.clear();
    }
}
