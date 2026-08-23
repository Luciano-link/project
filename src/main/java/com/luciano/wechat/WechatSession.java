package com.luciano.wechat;

import com.github.wechat.ilink.sdk.ILinkClient;
import com.github.wechat.ilink.sdk.ILinkClientBuilder;
import com.github.wechat.ilink.sdk.core.config.ILinkConfig;
import com.github.wechat.ilink.sdk.core.login.LoginContext;
import com.github.wechat.ilink.sdk.core.login.LoginStatus;
import com.github.wechat.ilink.sdk.core.model.MessageItem;
import com.github.wechat.ilink.sdk.core.model.WeixinMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 一个微信用户的会话:持有独立的 {@link ILinkClient}(一个 client 对应一个微信账号)、
 * 二维码状态、消息积压列表与轮询线程。扫码绑定后,该会话只服务这一个用户。
 *
 * <p>多用户 = 多个会话并存,各自独立登录、独立收发,互不干扰。
 */
public class WechatSession {

    private static final Logger log = LoggerFactory.getLogger(WechatSession.class);

    private final String sessionId;
    private final ILinkClient client;

    /** 当前二维码图片内容,登录成功后清空。 */
    private volatile String qrcodeImgContent;

    private final AtomicBoolean loginTriggered = new AtomicBoolean(false);

    /** 本会话积压的未消费消息(供 REST /messages 接口取走)。 */
    private final List<WeixinMessage> pendingMessages = new CopyOnWriteArrayList<>();

    /** 本会话的轮询线程:定时拉取该 client 的新消息。 */
    private final ScheduledExecutorService poller = Executors.newSingleThreadScheduledExecutor();

    /** 最近一次给本会话发消息的用户(调试接口默认接收人)。 */
    private volatile String lastFromUserId;

    public WechatSession(String sessionId, MessageDispatcher dispatcher,
                         LoginStateStore loginStateStore, LoginContext savedContext) {
        this.sessionId = sessionId;

        ILinkClientBuilder builder = ILinkClient.builder()
                .config(ILinkConfig.builder().build())
                .onLogin(new LoginListener(loginStateStore))
                .onMessage(msgs -> dispatcher.onMessages(this, msgs));
        if (savedContext != null) {
            builder.loginContext(savedContext);
            log.info("[{}] 检测到已保存的登录态,尝试免扫码恢复", sessionId);
        }
        this.client = builder.build();

        // 后台轮询收消息(仅在已登录时真正拉取)
        poller.scheduleWithFixedDelay(this::pollUpdates, 2, 2, TimeUnit.SECONDS);
    }

    public String getSessionId() {
        return sessionId;
    }

    /**
     * 触发登录,返回二维码图片内容字符串;已登录返回 null。
     * 注意:executeLogin 只是请求二维码(HTTP 很快),扫码轮询由 SDK 内部异步完成。
     */
    public synchronized String login() {
        if (client.isLoggedIn()) {
            return null;
        }
        if (!loginTriggered.compareAndSet(false, true)) {
            return qrcodeImgContent;
        }
        try {
            this.qrcodeImgContent = client.executeLogin();
            log.info("[{}] 二维码已生成,请扫码登录", sessionId);
            return qrcodeImgContent;
        } catch (Exception e) {
            loginTriggered.set(false);
            log.error("[{}] 触发登录失败: {}", sessionId, e.getMessage(), e);
            throw new IllegalStateException("触发登录失败: " + e.getMessage(), e);
        }
    }

    public boolean isLoggedIn() {
        return client.isLoggedIn();
    }

    public LoginStatus.Status getStatus() {
        return client.getLoginStatus().getStatus();
    }

    public String getQrcodeImgContent() {
        return qrcodeImgContent;
    }

    public String getUserId() {
        return client.isLoggedIn() ? client.getLoginContext().getUserId() : null;
    }

    public void setLastFromUserId(String fromUserId) {
        this.lastFromUserId = fromUserId;
    }

    public String getLastFromUserId() {
        return lastFromUserId;
    }

    /**
     * 取出并清空本会话的积压消息。
     */
    public List<WeixinMessage> pollMessages() {
        List<WeixinMessage> snapshot = new ArrayList<>(pendingMessages);
        pendingMessages.clear();
        return snapshot;
    }

    public void addPendingMessages(List<WeixinMessage> messages) {
        pendingMessages.addAll(messages);
    }

    // ==================== 发送与下载(转发给 client)====================

    public void sendText(String toUserId, String text) throws Exception {
        client.sendText(toUserId, text);
    }

    public void sendImage(String toUserId, byte[] bytes, String fileName) throws Exception {
        client.sendImage(toUserId, bytes, fileName, "图片");
    }

    public void sendVoice(String toUserId, byte[] bytes, String fileName,
                          Integer playTimeMs, Integer sampleRate) throws Exception {
        client.sendVoice(toUserId, bytes, fileName, playTimeMs, sampleRate);
    }

    /** 发送语音并附带转写文本(部分服务端校验需要 text 字段)。 */
    public void sendVoiceWithTranscript(String toUserId, byte[] bytes, String fileName,
                                        Integer playTimeMs, Integer sampleRate, String transcriptText) throws Exception {
        client.sendVoice(toUserId, bytes, fileName, playTimeMs, sampleRate, null, null, null, transcriptText);
    }

    public void startTyping(String toUserId) throws Exception {
        client.startTyping(toUserId);
    }

    public void stopTyping(String toUserId) throws Exception {
        client.stopTyping(toUserId);
    }

    public byte[] downloadImage(MessageItem item) throws Exception {
        return client.downloadImageFromMessageItem(item);
    }

    public byte[] downloadVoice(MessageItem item) throws Exception {
        return client.downloadVoiceFromMessageItem(item);
    }

    // ==================== 内部 ====================

    private void pollUpdates() {
        if (!client.isLoggedIn()) {
            return;
        }
        try {
            client.getUpdates();
        } catch (Exception e) {
            log.debug("[{}] 拉取消息失败(将自动重试): {}", sessionId, e.getMessage());
        }
    }

    /**
     * 关闭会话:停止轮询并释放连接(注销会话时调用)。
     */
    public void close() {
        poller.shutdownNow();
        try {
            client.close();
        } catch (Exception e) {
            log.warn("[{}] 关闭 client 异常: {}", sessionId, e.getMessage());
        }
        log.info("[{}] 会话已关闭", sessionId);
    }

    /**
     * 登录回调:成功后清空二维码并持久化登录态;失败则允许重新触发登录。
     */
    private class LoginListener implements com.github.wechat.ilink.sdk.core.listener.OnLoginListener {

        private final LoginStateStore loginStateStore;

        LoginListener(LoginStateStore loginStateStore) {
            this.loginStateStore = loginStateStore;
        }

        @Override
        public void onLoginSuccess(LoginContext context) {
            qrcodeImgContent = null;
            loginStateStore.save(sessionId, context);
            log.info("[{}] 登录成功,用户: {}", sessionId, context.getUserId());
        }

        @Override
        public void onLoginFailure(Throwable throwable) {
            loginTriggered.set(false);
            log.error("[{}] 登录失败: {}", sessionId, throwable.getMessage(), throwable);
        }
    }
}
