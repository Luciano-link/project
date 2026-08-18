package com.luciano.wechat;

import com.github.wechat.ilink.sdk.ILinkClient;
import com.github.wechat.ilink.sdk.core.config.ILinkConfig;
import com.github.wechat.ilink.sdk.core.listener.OnLoginListener;
import com.github.wechat.ilink.sdk.core.listener.OnMessageListener;
import com.github.wechat.ilink.sdk.core.login.LoginContext;
import com.github.wechat.ilink.sdk.core.model.MessageItem;
import com.github.wechat.ilink.sdk.core.model.WeixinMessage;
import com.luciano.llm.ImageService;
import com.luciano.llm.IntentService;
import com.luciano.llm.LlmService;
import com.luciano.llm.TtsService;
import com.luciano.weather.WeatherService;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * 微信 iLink Bot 客户端。
 * 启动后输出二维码供扫码登录,长轮询接收消息,
 * 按意图分发:文本问答 / 语音回复 / 文生图 / 天气查询。
 */
@Component
public class WechatBotRunner {

    private static final Logger log = LoggerFactory.getLogger(WechatBotRunner.class);

    private final LlmService llmService;
    private final IntentService intentService;
    private final TtsService ttsService;
    private final ImageService imageService;
    private final WeatherService weatherService;

    private ILinkClient client;
    /** 有界线程池:核心 2,最大 8,队列 100,防止恶意消息耗尽资源 */
    private final ThreadPoolExecutor replyExecutor = new ThreadPoolExecutor(
            2, 8, 60L, TimeUnit.SECONDS,
            new ArrayBlockingQueue<>(100),
            r -> {
                Thread t = new Thread(r, "bot-reply");
                t.setDaemon(true);
                return t;
            },
            new ThreadPoolExecutor.CallerRunsPolicy());
    /** 防御性阻塞,避免非 Web 场景下进程退出 */
    private final CountDownLatch keepAlive = new CountDownLatch(1);

    public WechatBotRunner(LlmService llmService,
                           IntentService intentService,
                           TtsService ttsService,
                           ImageService imageService,
                           WeatherService weatherService) {
        this.llmService = llmService;
        this.intentService = intentService;
        this.ttsService = ttsService;
        this.imageService = imageService;
        this.weatherService = weatherService;
    }

    /** 项目启动后自动执行 */
    @jakarta.annotation.PostConstruct
    public void start() {
        try {
            ILinkConfig config = ILinkConfig.builder()
                    .connectTimeoutMs(15000)
                    .readTimeoutMs(15000)
                    .writeTimeoutMs(15000)
                    .httpMaxRetries(3)
                    .retryBaseDelayMs(1000)
                    .retryMaxDelayMs(10000)
                    .heartbeatEnabled(true)
                    .heartbeatIntervalMs(30000)
                    .channelVersion("1.0.0")
                    .build();

            client = ILinkClient.builder()
                    .config(config)
                    .onLogin(new OnLoginListener() {
                        @Override
                        public void onLoginSuccess(LoginContext context) {
                            log.info("微信登录成功,botId = {}", context.getBotId());
                        }

                        @Override
                        public void onLoginFailure(Throwable throwable) {
                            log.error("微信登录失败: {}", throwable.getMessage());
                        }
                    })
                    .onMessage(new OnMessageListener() {
                        @Override
                        public void onMessages(List<WeixinMessage> messages) {
                            for (WeixinMessage msg : messages) {
                                handleMessage(msg);
                            }
                        }
                    })
                    .build();

            String qrCodeContent = client.executeLogin();
            log.info("请用微信扫描以下二维码登录机器人:");
            System.out.println("============================== 二维码内容 ==============================");
            System.out.println(qrCodeContent);
            System.out.println("==========================================================================");
            log.info("二维码内容已输出,请用支持渲染二维码的工具(如 QR 码生成器)生成后扫码登录");
            keepAlive.await();
        } catch (Exception e) {
            log.error("微信客户端启动失败", e);
            throw new IllegalStateException("微信客户端启动失败", e);
        }
    }

    /** 处理单条消息:按消息类型分发 */
    private void handleMessage(WeixinMessage msg) {
        if (msg.getItem_list() == null) {
            return;
        }
        String fromUser = msg.getFrom_user_id();
        for (MessageItem item : msg.getItem_list()) {
            if (item.getText_item() != null) {
                String userText = item.getText_item().getText();
                log.info("收到来自 {} 的文本消息: {}", fromUser, userText);
                dispatchByIntent(fromUser, userText);
            } else if (item.getVoice_item() != null) {
                // 语音消息:读取服务端转写文本,按意图处理
                String voiceText = item.getVoice_item().getText();
                log.info("收到来自 {} 的语音消息,转写文本: {}", fromUser, voiceText);
                dispatchByIntent(fromUser, voiceText);
            }
        }
    }

    /** 意图识别并分发到具体处理器 */
    private void dispatchByIntent(String toUserId, String userText) {
        if (userText == null || userText.isBlank()) {
            log.info("收到空文本消息,忽略,toUserId = {}", toUserId);
            return;
        }
        replyExecutor.execute(() -> {
            try {
                IntentService.IntentResult intent = intentService.detect(userText);
                log.info("意图识别结果 {}: intent={}, city={}", toUserId, intent.intent(), intent.city());
                switch (intent.intent()) {
                    case VOICE -> handleVoice(toUserId, userText);
                    case IMAGE -> handleImage(toUserId, userText);
                    case WEATHER -> handleWeather(toUserId, intent.city());
                    default -> handleText(toUserId, userText);
                }
            } catch (Exception e) {
                log.error("消息处理异常,toUserId = {}", toUserId, e);
                safeSendText(toUserId, "抱歉,处理你的消息时出错了,请稍后再试。");
            }
        });
    }

    /** 文本问答(带上下文) */
    private void handleText(String toUserId, String userText) {
        String reply = llmService.chat(toUserId, userText);
        log.info("LLM 回复 {}: {}", toUserId, reply);
        safeSendText(toUserId, reply);
    }

    /** 语音回复:LLM 生成文本 -> TTS 合成 mp3 -> 发送语音文件 */
    private void handleVoice(String toUserId, String userText) {
        try {
            client.startTyping(toUserId);
            String replyText = llmService.chat(toUserId, userText);
            byte[] mp3 = ttsService.synthesize(replyText);
            if (mp3 == null) {
                log.warn("语音合成失败,改发文本,toUserId = {}", toUserId);
                safeSendText(toUserId, replyText);
                return;
            }
            String fileName = "voice_" + UUID.randomUUID().toString().substring(0, 8) + ".mp3";
            client.sendFile(toUserId, mp3, fileName, "语音回复");
            log.info("语音回复已发送 {}: {}", toUserId, replyText);
        } catch (Exception e) {
            log.error("语音回复失败,toUserId = {}", toUserId, e);
            safeSendText(toUserId, "抱歉,语音回复失败,请稍后再试。");
        }
    }

    /** 文生图:生成图片并发送 */
    private void handleImage(String toUserId, String userText) {
        try {
            client.startTyping(toUserId);
            byte[] imageBytes = imageService.generate(userText);
            if (imageBytes == null) {
                log.warn("文生图失败,toUserId = {}", toUserId);
                safeSendText(toUserId, "抱歉,图片生成失败,请换个描述试试。");
                return;
            }
            String fileName = "image_" + UUID.randomUUID().toString().substring(0, 8) + ".png";
            client.sendImage(toUserId, imageBytes, fileName, "为你生成的图片");
            log.info("图片已发送 {}: {}", toUserId, userText);
        } catch (Exception e) {
            log.error("文生图回复失败,toUserId = {}", toUserId, e);
            safeSendText(toUserId, "抱歉,图片生成失败,请稍后再试。");
        }
    }

    /** 天气查询 */
    private void handleWeather(String toUserId, String city) {
        String reply = weatherService.getWeatherNow(city);
        log.info("天气查询回复 {}: {}", toUserId, reply);
        safeSendText(toUserId, reply);
    }

    /** 安全发送文本,吞掉 IO 异常 */
    private void safeSendText(String toUserId, String text) {
        try {
            client.sendTextWithTyping(toUserId, text, 500L);
        } catch (IOException e) {
            log.error("文本消息发送失败,toUserId = {}", toUserId, e);
        }
    }

    @PreDestroy
    public void stop() {
        replyExecutor.shutdownNow();
        keepAlive.countDown();
        if (client != null) {
            client.close();
        }
    }
}
