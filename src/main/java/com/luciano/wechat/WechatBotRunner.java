package com.luciano.wechat;

import com.github.wechat.ilink.sdk.ILinkClient;
import com.github.wechat.ilink.sdk.core.config.ILinkConfig;
import com.github.wechat.ilink.sdk.core.listener.OnLoginListener;
import com.github.wechat.ilink.sdk.core.listener.OnMessageListener;
import com.github.wechat.ilink.sdk.core.login.LoginContext;
import com.github.wechat.ilink.sdk.core.model.MessageItem;
import com.github.wechat.ilink.sdk.core.model.WeixinMessage;
import com.luciano.llm.LlmService;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 微信 iLink Bot 客户端。
 * 启动后输出二维码供扫码登录,长轮询接收消息,
 * 收到文本消息后调用 LLM 生成回复并发回。
 */
@Component
public class WechatBotRunner {

    private static final Logger log = LoggerFactory.getLogger(WechatBotRunner.class);

    private final LlmService llmService;
    private ILinkClient client;
    private final ExecutorService replyExecutor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "llm-reply");
        t.setDaemon(true);
        return t;
    });
    /** 阻塞主线程,保证应用在非 Web 环境下持续运行 */
    private final CountDownLatch keepAlive = new CountDownLatch(1);

    public WechatBotRunner(LlmService llmService) {
        this.llmService = llmService;
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

    /** 处理单条消息:提取文本 -> LLM 生成回复 -> 发送 */
    private void handleMessage(WeixinMessage msg) {
        if (msg.getItem_list() == null) {
            return;
        }
        for (MessageItem item : msg.getItem_list()) {
            if (item.getText_item() != null) {
                String userText = item.getText_item().getText();
                String fromUser = msg.getFrom_user_id();
                log.info("收到来自 {} 的文本消息: {}", fromUser, userText);
                replyAsync(fromUser, userText);
            }
        }
    }

    /** 异步调用 LLM 并回复,避免阻塞 SDK 长轮询 */
    private void replyAsync(String toUserId, String userText) {
        replyExecutor.execute(() -> {
            try {
                String reply = llmService.chat(userText);
                log.info("LLM 回复 {}: {}", toUserId, reply);
                client.sendTextWithTyping(toUserId, reply, 500L);
            } catch (IOException e) {
                log.error("回复消息发送失败,toUserId = {}", toUserId, e);
            } catch (Exception e) {
                log.error("LLM 回复处理失败,toUserId = {}", toUserId, e);
            }
        });
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
