package com.luciano.wechat;

import com.github.wechat.ilink.sdk.ILinkClient;
import com.github.wechat.ilink.sdk.ILinkClientBuilder;
import com.github.wechat.ilink.sdk.core.config.ILinkConfig;
import com.github.wechat.ilink.sdk.core.login.LoginContext;
import com.github.wechat.ilink.sdk.core.login.LoginStatus;
import com.github.wechat.ilink.sdk.core.model.MessageItem;
import com.github.wechat.ilink.sdk.core.model.VoiceItem;
import com.github.wechat.ilink.sdk.core.model.WeixinMessage;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 微信 iLink Bot 核心服务:持有唯一 {@link ILinkClient},封装登录、收发消息与 LLM 自动回复。
 */
@Service
public class WechatBotService {

    private static final Logger log = LoggerFactory.getLogger(WechatBotService.class);

    private final LoginStateStore loginStateStore;
    private final DashScopeClient dashScopeClient;
    private final WeatherClient weatherClient;

    private ILinkClient client;

    /** 当前二维码图片内容,登录后清空。 */
    private volatile String qrcodeImgContent;

    /** 积压的未消费消息。 */
    private final List<WeixinMessage> pendingMessages = new CopyOnWriteArrayList<>();

    private final ScheduledExecutorService poller = Executors.newSingleThreadScheduledExecutor();

    /** 处理自动回复的独立线程池,避免阻塞消息轮询线程。 */
    private final ExecutorService replyExecutor = Executors.newFixedThreadPool(4);

    private final AtomicBoolean loginTriggered = new AtomicBoolean(false);

    public WechatBotService(LoginStateStore loginStateStore, DashScopeClient dashScopeClient, WeatherClient weatherClient) {
        this.loginStateStore = loginStateStore;
        this.dashScopeClient = dashScopeClient;
        this.weatherClient = weatherClient;
    }

    @PostConstruct
    public void init() {
        ILinkClientBuilder builder = ILinkClient.builder()
                .config(ILinkConfig.builder().build())
                .onLogin(new LoginListener())
                .onMessage(this::onMessages);

        LoginContext saved = loginStateStore.load();
        if (saved != null) {
            builder.loginContext(saved);
            log.info("检测到已保存的登录态,尝试免扫码恢复...");
        }

        this.client = builder.build();

        // 后台轮询收消息(仅在已登录时真正拉取)
        poller.scheduleWithFixedDelay(this::pollUpdates, 2, 2, TimeUnit.SECONDS);

        if (client.isLoggedIn()) {
            log.info("已恢复登录,当前用户: {}", client.getLoginContext().getUserId());
        } else {
            log.info("尚未登录,请访问 /wechat/qrcode 扫码");
        }
    }

    @PreDestroy
    public void destroy() {
        poller.shutdownNow();
        replyExecutor.shutdownNow();
        if (client != null) {
            client.close();
        }
    }

    /**
     * 触发登录,返回二维码图片内容字符串。已登录时返回 null。
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
            log.info("二维码已生成,请扫码登录");
            return qrcodeImgContent;
        } catch (Exception e) {
            loginTriggered.set(false);
            log.error("触发登录失败: {}", e.getMessage(), e);
            throw new IllegalStateException("触发登录失败: " + e.getMessage(), e);
        }
    }

    public boolean isLoggedIn() {
        return client != null && client.isLoggedIn();
    }

    public LoginStatus.Status getStatus() {
        return client.getLoginStatus().getStatus();
    }

    public String getQrcodeImgContent() {
        return qrcodeImgContent;
    }

    /**
     * 取出并清空积压消息。
     */
    public List<WeixinMessage> pollMessages() {
        List<WeixinMessage> snapshot = new ArrayList<>(pendingMessages);
        pendingMessages.clear();
        return snapshot;
    }

    public void sendText(String toUserId, String text) throws Exception {
        client.sendText(toUserId, text);
    }

    public void sendImage(String toUserId, byte[] bytes, String fileName) throws Exception {
        client.sendImage(toUserId, bytes, fileName, "图片");
    }

    private void pollUpdates() {
        if (!client.isLoggedIn()) {
            return;
        }
        try {
            client.getUpdates();
        } catch (Exception e) {
            log.debug("拉取消息失败(将自动重试): {}", e.getMessage());
        }
    }

    private void onMessages(List<WeixinMessage> messages) {
        if (messages == null || messages.isEmpty()) {
            return;
        }
        pendingMessages.addAll(messages);
        log.info("收到 {} 条消息", messages.size());
        for (WeixinMessage msg : messages) {
            replyExecutor.submit(() -> handleMessage(msg));
        }
    }

    /**
     * 处理单条消息并自动回复(在异步线程中执行)。
     */
    private void handleMessage(WeixinMessage msg) {
        String fromUserId = msg.getFrom_user_id();
        try {
            List<MessageItem> items = msg.getItem_list();
            if (items == null || items.isEmpty()) {
                return;
            }
            for (MessageItem item : items) {
                // 图片消息 → 理解图片并回复描述
                if (item.getImage_item() != null) {
                    byte[] imageBytes = client.downloadImageFromMessageItem(item);
                    String description = dashScopeClient.describeImage(imageBytes, "请用简洁的中文描述这张图片的内容。");
                    client.sendText(fromUserId, description);
                    return;
                }
                // 语音消息 → 读取微信自带转写文字,按文本处理
                if (item.getVoice_item() != null) {
                    VoiceItem voice = item.getVoice_item();
                    String text = voice.getText();
                    if (text == null || text.isBlank()) {
                        client.sendText(fromUserId, "收到你的语音啦,但我暂时识别不了内容,请发文字给我~");
                        return;
                    }
                    String reply = dashScopeClient.chat(text, List.of());
                    client.sendText(fromUserId, "🔊 语音转文字:「" + text + "」\n" + reply);
                    return;
                }
                // 文本消息 → 判断是否生成图片 / 查天气,否则文本对话
                if (item.getText_item() != null) {
                    String text = item.getText_item().getText();
                    if (isImageRequest(text)) {
                        client.sendText(fromUserId, "正在生成图片,请稍候...");
                        byte[] imageBytes = dashScopeClient.generateImage(text);
                        client.sendImage(fromUserId, imageBytes, "generated.png", "图片");
                    } else if (isWeatherRequest(text)) {
                        handleWeather(fromUserId, text);
                    } else {
                        String reply = dashScopeClient.chat(text, List.of());
                        client.sendText(fromUserId, reply);
                    }
                    return;
                }
            }
        } catch (Exception e) {
            log.error("处理消息失败: {}", e.getMessage(), e);
            try {
                client.sendText(fromUserId, "抱歉,处理出错了:" + e.getMessage());
            } catch (Exception ignored) {
                // 连错误提示都发不出去就放弃
            }
        }
    }

    /**
     * 判断文本是否是「生成图片」请求。
     */
    private boolean isImageRequest(String text) {
        if (text == null) {
            return false;
        }
        return text.contains("画") || text.contains("生成图片") || text.contains("生成一张")
                || text.contains("画一张") || text.contains("画个") || text.contains("画一幅");
    }

    /**
     * 判断文本是否是「天气」请求。
     */
    private boolean isWeatherRequest(String text) {
        return text != null && text.contains("天气");
    }

    /**
     * 处理天气查询:从消息中提取城市,查实时天气 + 3 天预报并回复。
     */
    private void handleWeather(String fromUserId, String text) throws Exception {
        String city = extractCity(text);
        if (city == null || city.isBlank()) {
            client.sendText(fromUserId, "请告诉我想查哪个城市,例如:北京天气");
            return;
        }
        String now = weatherClient.getNow(city);
        String forecast = weatherClient.get3d(city);
        client.sendText(fromUserId, now + "\n" + forecast);
    }

    /**
     * 用 LLM 从消息里提取城市名,返回纯城市名或空串。
     */
    private String extractCity(String text) throws Exception {
        String prompt = "从以下用户消息中提取要查询天气的城市名称,只返回城市名本身,不要任何标点、解释或额外文字。"
                + "如果消息里没有城市名,只返回空。\n消息:" + text;
        String city = dashScopeClient.chat(prompt, List.of());
        return city == null ? "" : city.replace("。", "").replace(":", "").trim();
    }

    /**
     * 登录回调:成功后持久化登录态。
     */
    private class LoginListener implements com.github.wechat.ilink.sdk.core.listener.OnLoginListener {

        @Override
        public void onLoginSuccess(LoginContext context) {
            qrcodeImgContent = null;
            loginStateStore.save(context);
            log.info("登录成功,用户: {}", context.getUserId());
        }

        @Override
        public void onLoginFailure(Throwable throwable) {
            loginTriggered.set(false);
            log.error("登录失败: {}", throwable.getMessage(), throwable);
        }
    }
}
