package com.luciano.wechat;

import com.github.wechat.ilink.sdk.ILinkClient;
import com.github.wechat.ilink.sdk.ILinkClientBuilder;
import com.github.wechat.ilink.sdk.core.config.ILinkConfig;
import com.github.wechat.ilink.sdk.core.context.ResumeContext;
import com.github.wechat.ilink.sdk.core.listener.OnLoginListener;
import com.github.wechat.ilink.sdk.core.login.LoginContext;
import com.github.wechat.ilink.sdk.core.login.LoginStatus;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 多用户会话管理器。
 * 每个用户一个专属 ILinkClient,以 sessionId 为标志存入 clients 集合。
 * - create:后台线程(线程池)创建 client 并申请二维码,多用户并发扫码不互相阻塞
 * - restoreAll:启动时用已保存的登录态恢复,免扫码
 * - 登录成功自动保存登录态,移除会话时同步清理
 */
@Component
public class SessionManager {

    private static final Logger log = LoggerFactory.getLogger(SessionManager.class);

    /** 创建会话的线程池:防止多个扫码登录的 HTTP 请求互相阻塞 */
    private final ExecutorService loginExecutor = Executors.newFixedThreadPool(4);

    /** 会话数量上限,防止连接/内存膨胀 */
    private static final int MAX_SESSIONS = 50;

    /** 单会话消息积压上限,超出丢弃最旧,防止前端不轮询导致内存膨胀 */
    private static final int MAX_PENDING_MESSAGES = 200;

    /** 会话集合:sessionId -> 微信客户端 */
    private final Map<String, ILinkClient> clients = new ConcurrentHashMap<>();

    /** 会话消息积压:sessionId -> 收到的消息队列(供 REST 接口轮询取走,与自动回复并存) */
    private final Map<String, java.util.Queue<com.github.wechat.ilink.sdk.core.model.WeixinMessage>> pendingMessages
            = new ConcurrentHashMap<>();

    /** 会话创建时间:sessionId -> 创建时间戳(用于未登录会话清理) */
    private final Map<String, Long> sessionCreated = new ConcurrentHashMap<>();

    private final WechatBotRunner handler;
    private final LoginStateStore loginStateStore;

    public SessionManager(WechatBotRunner handler, LoginStateStore loginStateStore) {
        this.handler = handler;
        this.loginStateStore = loginStateStore;
    }

    /** 创建新会话:线程池中建 client 并申请二维码,返回 CompletableFuture<二维码内容> */
    public CompletableFuture<String> create(String sessionId) {
        return CompletableFuture.supplyAsync(() -> {
            if (clients.size() >= MAX_SESSIONS && !evictIdleSession()) {
                throw new IllegalStateException("会话数量已达上限(" + MAX_SESSIONS + "),请先清理空闲会话");
            }
            ILinkClient client = buildClient(sessionId);
            clients.put(sessionId, client);
            sessionCreated.put(sessionId, System.currentTimeMillis());
            return client.executeLogin();
        }, loginExecutor);
    }

    /** 清理一个未登录会话腾出空间,返回是否腾出成功 */
    private boolean evictIdleSession() {
        for (Map.Entry<String, ILinkClient> entry : clients.entrySet()) {
            if (!entry.getValue().isLoggedIn()) {
                remove(entry.getKey());
                return true;
            }
        }
        return false;
    }

    /** 定期清理超过 10 分钟仍未扫码登录的会话,防止连接泄漏 */
    @Scheduled(fixedDelay = 5 * 60 * 1000)
    public void cleanupExpiredSessions() {
        long now = System.currentTimeMillis();
        clients.forEach((sessionId, client) -> {
            Long created = sessionCreated.get(sessionId);
            if (!client.isLoggedIn() && created != null && now - created > 10 * 60 * 1000L) {
                log.info("清理长时间未登录会话: {}", sessionId);
                remove(sessionId);
            }
        });
    }

    /** 获取会话客户端,不存在返回 null */
    public ILinkClient get(String sessionId) {
        return clients.get(sessionId);
    }

    /** 查询会话登录状态,会话不存在返回 null */
    public LoginStatus status(String sessionId) {
        ILinkClient client = clients.get(sessionId);
        return client == null ? null : client.getLoginStatus();
    }

    /** 所有会话及其登录状态 */
    public Map<String, Boolean> all() {
        Map<String, Boolean> result = new LinkedHashMap<>();
        clients.forEach((sessionId, client) -> result.put(sessionId, client.isLoggedIn()));
        return result;
    }

    /** 移除会话:关闭客户端并清除持久化登录态 */
    public void remove(String sessionId) {
        sessionCreated.remove(sessionId);
        pendingMessages.remove(sessionId);
        ILinkClient client = clients.remove(sessionId);
        if (client != null) {
            client.close();
            loginStateStore.remove(sessionId);
            log.info("会话已移除: {}", sessionId);
        }
    }

    /** 把消息加入会话积压队列,超过上限时丢弃最旧 */
    private void enqueueMessages(String sessionId, List<com.github.wechat.ilink.sdk.core.model.WeixinMessage> messages) {
        java.util.Queue<com.github.wechat.ilink.sdk.core.model.WeixinMessage> queue =
                pendingMessages.computeIfAbsent(sessionId, k -> new java.util.concurrent.ConcurrentLinkedQueue<>());
        synchronized (queue) {
            if (queue.size() + messages.size() > MAX_PENDING_MESSAGES) {
                queue.clear();
                log.warn("会话 {} 消息积压超限,已丢弃旧消息防止内存膨胀", sessionId);
            }
            queue.addAll(messages);
        }
    }

    /** 取出并清空某会话积压的消息(供前端轮询) */
    public List<com.github.wechat.ilink.sdk.core.model.WeixinMessage> pollMessages(String sessionId) {
        java.util.Queue<com.github.wechat.ilink.sdk.core.model.WeixinMessage> queue = pendingMessages.get(sessionId);
        if (queue == null) {
            return List.of();
        }
        List<com.github.wechat.ilink.sdk.core.model.WeixinMessage> out = new ArrayList<>();
        com.github.wechat.ilink.sdk.core.model.WeixinMessage msg;
        while ((msg = queue.poll()) != null) {
            out.add(msg);
        }
        return out;
    }

    /** 用指定会话的 client 主动发送文本消息 */
    public void sendText(String sessionId, String toUserId, String text) throws java.io.IOException {
        ILinkClient client = clients.get(sessionId);
        if (client == null) {
            throw new IllegalArgumentException("会话不存在: " + sessionId);
        }
        client.sendText(toUserId, text);
    }

    /** 启动时用已保存的登录态恢复所有会话,免扫码 */
    @PostConstruct
    public void restoreAll() {
        Map<String, LoginContext> saved = loginStateStore.loadAll();
        saved.forEach((sessionId, loginContext) -> {
            ILinkClient client = buildClient(sessionId, ResumeContext.of(loginContext));
            if (client.isLoggedIn()) {
                clients.put(sessionId, client);
                log.info("会话 {} 免扫码恢复成功,botId = {}", sessionId, loginContext.getBotId());
            } else {
                client.close();
                // 登录态已失效,清除持久化,避免每次重启都重试无效凭证
                loginStateStore.remove(sessionId);
                log.warn("会话 {} 登录态已失效,已清除持久化,需重新扫码", sessionId);
            }
        });
    }

    /** 构建客户端:消息回调绑定本会话 client,登录成功自动持久化登录态 */
    private ILinkClient buildClient(String sessionId) {
        return buildClient(sessionId, null);
    }

    private ILinkClient buildClient(String sessionId, ResumeContext resume) {
        ILinkClientBuilder builder = ILinkClient.builder()
                .config(config())
                .onLogin(new OnLoginListener() {
                    @Override
                    public void onLoginSuccess(LoginContext context) {
                        log.info("会话 {} 登录成功,botId = {}", sessionId, context.getBotId());
                        // 若会话已被移除,不写回登录态,避免残留
                        if (clients.containsKey(sessionId)) {
                            loginStateStore.save(sessionId, context);
                        } else {
                            log.info("会话 {} 已被移除,跳过保存登录态", sessionId);
                        }
                    }

                    @Override
                    public void onLoginFailure(Throwable throwable) {
                        log.error("会话 {} 登录失败: {}", sessionId, throwable.getMessage());
                    }
                })
                .onMessage(messages -> {
                    // 消息积压:供前端 REST 轮询取走;超限丢弃最旧防内存膨胀
                    enqueueMessages(sessionId, messages);
                    // 同时走自动回复处理
                    messages.forEach(msg -> {
                        ILinkClient client = clients.get(sessionId);
                        if (client != null) {
                            handler.handleMessage(client, msg);
                        }
                    });
                });
        if (resume != null) {
            builder.resumeContext(resume);
        }
        return builder.build();
    }

    private ILinkConfig config() {
        return ILinkConfig.builder()
                .connectTimeoutMs(15000)
                // get_qrcode_status 和 getupdates 是长轮询接口,读超时需足够大
                .readTimeoutMs(60000)
                .writeTimeoutMs(15000)
                .httpMaxRetries(3)
                .retryBaseDelayMs(1000)
                .retryMaxDelayMs(10000)
                .heartbeatEnabled(true)
                .heartbeatIntervalMs(3000)
                .channelVersion("1.0.0")
                .build();
    }

    @PreDestroy
    public void shutdown() {
        loginExecutor.shutdownNow();
        clients.values().forEach(ILinkClient::close);
        clients.clear();
    }
}
