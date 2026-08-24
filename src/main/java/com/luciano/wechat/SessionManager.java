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
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
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

    /** 会话集合:sessionId -> 微信客户端 */
    private final Map<String, ILinkClient> clients = new ConcurrentHashMap<>();

    private final WechatBotRunner handler;
    private final LoginStateStore loginStateStore;

    public SessionManager(WechatBotRunner handler, LoginStateStore loginStateStore) {
        this.handler = handler;
        this.loginStateStore = loginStateStore;
    }

    /** 创建新会话:线程池中建 client 并申请二维码,返回 CompletableFuture<二维码内容> */
    public CompletableFuture<String> create(String sessionId) {
        return CompletableFuture.supplyAsync(() -> {
            ILinkClient client = buildClient(sessionId);
            clients.put(sessionId, client);
            return client.executeLogin();
        }, loginExecutor);
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
        ILinkClient client = clients.remove(sessionId);
        if (client != null) {
            client.close();
            loginStateStore.remove(sessionId);
            log.info("会话已移除: {}", sessionId);
        }
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
                .onMessage(messages -> messages.forEach(msg -> {
                    ILinkClient client = clients.get(sessionId);
                    if (client != null) {
                        handler.handleMessage(client, msg);
                    }
                }));
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
