package com.luciano.wechat;

import com.github.wechat.ilink.sdk.ILinkClient;
import com.github.wechat.ilink.sdk.core.config.ILinkConfig;
import com.github.wechat.ilink.sdk.core.listener.OnLoginListener;
import com.github.wechat.ilink.sdk.core.listener.OnMessageListener;
import com.github.wechat.ilink.sdk.core.login.LoginContext;
import com.github.wechat.ilink.sdk.core.model.MessageItem;
import com.github.wechat.ilink.sdk.core.model.WeixinMessage;
import com.luciano.llm.ImageService;
import com.luciano.llm.LlmService;
import com.luciano.llm.TtsService;
import com.luciano.tool.GenerateImageTool;
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
    private final TtsService ttsService;
    private final ImageService imageService;
    private final LoginStateStore loginStateStore;
    private final com.luciano.conversation.ConversationService conversationService;

    private ILinkClient client;
    /** 最近一次二维码内容(供 Web 页面展示) */
    private volatile String qrcodeContent;
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
                           TtsService ttsService,
                           ImageService imageService,
                           LoginStateStore loginStateStore,
                           com.luciano.conversation.ConversationService conversationService) {
        this.llmService = llmService;
        this.ttsService = ttsService;
        this.imageService = imageService;
        this.loginStateStore = loginStateStore;
        this.conversationService = conversationService;
    }

    /** 项目启动后自动执行 */
    @jakarta.annotation.PostConstruct
    public void start() {
        try {
            ILinkConfig config = ILinkConfig.builder()
                    .connectTimeoutMs(15000)
                    // get_qrcode_status 和 getupdates 是长轮询接口,读超时需足够大,否则扫码/收消息易超时
                    .readTimeoutMs(60000)
                    .writeTimeoutMs(15000)
                    .httpMaxRetries(3)
                    .retryBaseDelayMs(1000)
                    .retryMaxDelayMs(10000)
                    .heartbeatEnabled(true)
                    // 心跳(消息轮询)间隔:调小到3秒,消息才能及时拉取;过大的间隔会导致收到消息延迟
                    .heartbeatIntervalMs(3000)
                    .channelVersion("1.0.0")
                    .build();

            var builder = ILinkClient.builder()
                    .config(config)
                    .onLogin(new OnLoginListener() {
                        @Override
                        public void onLoginSuccess(LoginContext context) {
                            log.info("微信登录成功,botId = {}", context.getBotId());
                            loginStateStore.save(context);
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
                    });

            // 尝试免扫码恢复登录
            com.github.wechat.ilink.sdk.core.login.LoginContext saved = loginStateStore.load();
            if (saved != null) {
                builder.loginContext(saved);
                log.info("检测到已保存的登录态,尝试免扫码恢复...");
            }

            client = builder.build();

            if (client.isLoggedIn()) {
                log.info("登录态恢复成功,botId = {}", client.getLoginContext().getBotId());
            } else {
                this.qrcodeContent = client.executeLogin();
                log.info("请用微信扫描以下二维码登录机器人:");
                System.out.println("============================== 二维码内容 ==============================");
                System.out.println(this.qrcodeContent);
                System.out.println("==========================================================================");
                log.info("二维码内容已输出,可用 Web 页面 /wechat/qrcode 查看");
            }
            keepAlive.await();
        } catch (Exception e) {
            log.error("微信客户端启动失败", e);
            throw new IllegalStateException("微信客户端启动失败", e);
        }
    }

    /** 是否已登录 */
    public boolean isLoggedIn() {
        return client != null && client.isLoggedIn();
    }

    /** 获取二维码内容(未登录时),已登录返回 null */
    public String getQrcodeContent() {
        return isLoggedIn() ? null : qrcodeContent;
    }

    /** 获取已登录的 botId,未登录返回 null */
    public String getBotId() {
        if (client == null || !client.isLoggedIn() || client.getLoginContext() == null) {
            return null;
        }
        return client.getLoginContext().getBotId();
    }

    /** 处理单条消息:按消息类型分发。先缓存图片,再处理文字,保证图文合并能匹配 */
    private void handleMessage(WeixinMessage msg) {
        if (msg.getItem_list() == null) {
            return;
        }
        String fromUser = msg.getFrom_user_id();
        List<MessageItem> items = msg.getItem_list();
        // 第一遍:先下载缓存图片,确保文字消息检查待合并图片时已就绪
        for (MessageItem item : items) {
            if (item.getImage_item() != null) {
                log.info("收到来自 {} 的图片消息", fromUser);
                handleImageMessage(fromUser, item);
            }
        }
        // 第二遍:处理文字/语音
        for (MessageItem item : items) {
            if (item.getText_item() != null) {
                String userText = item.getText_item().getText();
                log.info("收到来自 {} 的文本消息: {}", fromUser, userText);
                handleTextMessage(fromUser, userText);
            } else if (item.getVoice_item() != null) {
                // 语音消息:读取服务端转写文本,按意图处理
                String voiceText = item.getVoice_item().getText();
                log.info("收到来自 {} 的语音消息,转写文本: {}", fromUser, voiceText);
                dispatchByIntent(fromUser, voiceText);
            }
        }
    }

    /** 文本消息:直接走 LLM(意图交给 LLM 自主判断);若有极短时间内待合并的图片则图文合并,否则秒回 */
    private void handleTextMessage(String fromUser, String userText) {
        ImagePendingStore.PendingImage pending = ImagePendingStore.getPending(fromUser);
        if (pending != null) {
            log.info("检测到 {} 的待合并图片(id={}),执行图文合并识别", fromUser, pending.id());
            handleImageRecognitionWithText(fromUser, userText, pending);
            return;
        }
        // 下行图片关键词("下面/这张/这个/下图"):用户提到图片但还没发,缓存为待合并文字等图片到达合并
        if (hasImageReferDirective(userText)) {
            ImagePendingStore.putText(fromUser, userText);
            log.info("检测到下行图片关键词,等待图片上传,fromUser = {}, text = {}", fromUser, userText);
            // 用较长窗口等待(用户明确要图,给足上传时间);超时无图则提示发图
            final long waitMs = ImagePendingStore.MERGE_WINDOW_MS;
            replyExecutor.execute(() -> {
                try {
                    Thread.sleep(waitMs);
                    ImagePendingStore.PendingText pt = ImagePendingStore.takeText(fromUser);
                    if (pt != null) {
                        log.info("等待图片超时,提示发图,fromUser = {}", fromUser);
                        safeSendText(fromUser, "请先发送图片,我会结合图片和你的描述来分析~");
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            });
            return;
        }
        // 其他:直接走 LLM 工具链(秒回;上行历史图片关键词的识图结果已在上下文,LLM 能引用)
        dispatchByIntent(fromUser, userText);
    }

    /** 判断是否含"提到图片但图片还没发"的关键词(下行图片引用) */
    private boolean hasImageReferDirective(String text) {
        return text.contains("下面这个图片") || text.contains("下面这张图片")
                || text.contains("下面这张图") || text.contains("下面的图片")
                || text.contains("看这张图片") || text.contains("看这个图片")
                || text.contains("这张图片") || text.contains("这个图片")
                || text.contains("这张图") || text.contains("这个图")
                || text.contains("图片中") || text.contains("图里")
                || text.contains("图片里") || text.contains("下图")
                || text.contains("图中") || text.contains("图上")
                || text.contains("图片上") || text.contains("看下图")
                || text.contains("看图片") || text.contains("看这张")
                || text.contains("图片是") || text.contains("图片的");
    }

    /** 图片消息:同步下载并立即识别;若有待合并文字(先文后图)则合并,否则单独识图 */
    private void handleImageMessage(String fromUser, MessageItem item) {
        try {
            byte[] imageBytes = client.downloadImageFromMessageItem(item);
            if (imageBytes == null || imageBytes.length == 0) {
                log.warn("图片下载失败或为空,fromUser = {}", fromUser);
                safeSendText(fromUser, "抱歉,图片下载失败,请重试。");
                return;
            }
            String fileName = "image_" + UUID.randomUUID().toString().substring(0, 8) + ".png";
            String imageId = ImagePendingStore.put(fromUser, imageBytes, fileName);
            log.info("图片已缓存,fromUser = {}, imageId = {}", fromUser, imageId);

            // 先发文字后发图:图片到达时检查是否有待合并文字,有则图文合并
            ImagePendingStore.PendingText pendingText = ImagePendingStore.takeText(fromUser);
            if (pendingText != null) {
                log.info("检测到 {} 的待合并文字(id={}),执行图文合并识别", fromUser, pendingText.id());
                handleImageRecognitionWithText(fromUser, pendingText.text(),
                        ImagePendingStore.getPending(fromUser));
                return;
            }

            // 无待合并文字:立即单独识图(不等,避免"发图不说话"长时间无响应)
            ImagePendingStore.PendingImage img = ImagePendingStore.takeForFallback(fromUser, imageId);
            if (img != null) {
                log.info("立即单独识图,fromUser = {}, imageId = {}", fromUser, imageId);
                handleImageRecognition(fromUser, img);
            }
        } catch (IOException e) {
            log.error("图片下载失败,fromUser = {}", fromUser, e);
            safeSendText(fromUser, "抱歉,图片处理失败,请稍后再试。");
        }
    }

    /** 单独识图(无文字描述),识别结果存入上下文供后续对话引用 */
    private void handleImageRecognition(String toUserId, ImagePendingStore.PendingImage img) {
        String result = imageService.recognize(img.bytes(), img.fileName(), null);
        log.info("图片识别结果 {}: {}", toUserId, result);
        saveImageContext(toUserId, "用户发送了一张图片", result);
        safeSendText(toUserId, result);
    }

    /** 图文合并识别:文字 + 待合并图片一起交给多模态模型,结果存入上下文 */
    private void handleImageRecognitionWithText(String toUserId, String userText, ImagePendingStore.PendingImage img) {
        // 消费该图片(若已被消费则按普通文本处理)
        ImagePendingStore.PendingImage consumed = ImagePendingStore.take(toUserId, img.id());
        if (consumed == null) {
            dispatchByIntent(toUserId, userText);
            return;
        }
        String result = imageService.recognize(consumed.bytes(), consumed.fileName(), userText);
        log.info("图文合并识别结果 {}: {}", toUserId, result);
        saveImageContext(toUserId, "用户发送了一张图片,并补充描述: " + userText, result);
        safeSendText(toUserId, result);
    }

    /** 把图片识别结果写入对话上下文,使后续对话 LLM 能引用图片内容 */
    private void saveImageContext(String toUserId, String userDesc, String recognizeResult) {
        try {
            conversationService.addMessage(toUserId,
                    com.alibaba.dashscope.common.Message.builder()
                            .role(com.alibaba.dashscope.common.Role.USER.getValue())
                            .content(userDesc).build());
            conversationService.addMessage(toUserId,
                    com.alibaba.dashscope.common.Message.builder()
                            .role(com.alibaba.dashscope.common.Role.ASSISTANT.getValue())
                            .content("图片识别结果: " + recognizeResult).build());
        } catch (Exception e) {
            log.error("保存图片上下文失败,toUserId = {}", toUserId, e);
        }
    }

    /** 文字分发:意图交给 LLM 工具链自主判断;仅语音指令用关键词快速路径(语音不能等) */
    private void dispatchByIntent(String toUserId, String userText) {
        if (userText == null || userText.isBlank()) {
            log.info("收到空文本消息,忽略,toUserId = {}", toUserId);
            return;
        }
        // 语音关键词快速判断:用户明确要求语音时优先语音路径(即使夹杂天气/问答等复合需求)
        if (hasVoiceDirective(userText)) {
            log.info("检测到语音指令,走语音回复,toUserId = {}", toUserId);
            replyExecutor.execute(() -> handleVoice(toUserId, userText));
            return;
        }
        // 其余一律交给 LLM 工具链:LLM 自主决定聊天/天气/生图/搜索/邮件,秒回
        replyExecutor.execute(() -> {
            try {
                handleText(toUserId, userText);
            } catch (Exception e) {
                log.error("消息处理异常,toUserId = {}", toUserId, e);
                safeSendText(toUserId, "抱歉,处理你的消息时出错了,请稍后再试。");
            }
        });
    }

    /** 判断文本是否含明确语音指令关键词 */
    private boolean hasVoiceDirective(String text) {
        return text.contains("用语音") || text.contains("语音回复")
                || text.contains("语音说") || text.contains("语音回答")
                || text.contains("用声音") || text.contains("朗读")
                || text.contains("读给我听") || text.contains("念给我听");
    }

    /** 文本问答(带上下文 + 工具调用),若生图工具生成了图片则一并发送 */
    private void handleText(String toUserId, String userText) {
        LlmService.ChatResult chat = llmService.chatWithTrace(toUserId, userText);
        String reply = chat.reply();
        log.info("LLM 回复 {}: {}", toUserId, reply);
        safeSendText(toUserId, reply);
        byte[] pendingImage = GenerateImageTool.takePendingImage(toUserId);
        if (pendingImage != null) {
            String fileName = "image_" + UUID.randomUUID().toString().substring(0, 8) + ".png";
            try {
                client.sendImage(toUserId, pendingImage, fileName, "为你生成的图片");
                log.info("工具生成的图片已发送 {}: {}", toUserId, fileName);
            } catch (IOException e) {
                log.error("工具生成的图片发送失败,toUserId = {}", toUserId, e);
            }
        }
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

    /** 安全发送文本,吞掉 IO 异常 */
    private void safeSendText(String toUserId, String text) {
        if (text == null || text.isBlank()) {
            log.warn("回复文本为空,不发送,toUserId = {}", toUserId);
            return;
        }
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
