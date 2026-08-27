package com.luciano.wechat;

import com.github.wechat.ilink.sdk.ILinkClient;
import com.github.wechat.ilink.sdk.core.model.MessageItem;
import com.github.wechat.ilink.sdk.core.model.WeixinMessage;
import com.luciano.llm.ImageService;
import com.luciano.llm.LlmService;
import com.luciano.llm.TtsService;
import com.luciano.rag.RagService;
import com.luciano.skill.Skill;
import com.luciano.skill.SkillRouter;
import com.luciano.tool.GenerateImageTool;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * 微信消息处理器(无状态,多会话共用)。
 * 每个方法都接收所属会话的 client 参数,消息从哪个会话来就用哪个 client 回复。
 * 按意图分发:文本问答 / 语音回复 / 文生图 / 图片识别 / 图文合并。
 */
@Component
public class WechatBotRunner {

    private static final Logger log = LoggerFactory.getLogger(WechatBotRunner.class);

    private final LlmService llmService;
    private final TtsService ttsService;
    private final ImageService imageService;
    private final com.luciano.conversation.ConversationService conversationService;
    private final SkillRouter skillRouter;
    private final RagService ragService;
    private final UserClientRegistry userClientRegistry;
    private final com.luciano.agent.AgentRouter agentRouter;
    private final AudioCodec audioCodec;

    /** 按用户分片的串行执行器:同一用户消息固定路由到同一 worker,保证顺序;跨用户并行 */
    private static final int USER_SHARDS = 8;
    private final List<ThreadPoolExecutor> userShards = new ArrayList<>();

    {
        for (int i = 0; i < USER_SHARDS; i++) {
            final int shard = i;
            ThreadPoolExecutor e = new ThreadPoolExecutor(
                    1, 1, 0L, TimeUnit.MILLISECONDS,
                    new ArrayBlockingQueue<>(200),
                    r -> {
                        Thread t = new Thread(r, "bot-reply-" + shard);
                        t.setDaemon(true);
                        return t;
                    });
            // 队列满时丢弃并告警,防止无界堆积;正常规模下不会触发
            e.setRejectedExecutionHandler((r, executor) ->
                    log.warn("消息队列已满,丢弃消息任务: {}", r));
            userShards.add(e);
        }
    }

    /** 按用户提交任务:同一用户串行执行,跨用户分片并行;为每条消息生成 traceId 便于日志串联 */
    private void executeSerial(String userId, Runnable task) {
        int shard = (userId == null ? 0 : Math.abs(userId.hashCode())) % USER_SHARDS;
        String traceId = java.util.UUID.randomUUID().toString().substring(0, 8);
        userShards.get(shard).execute(() -> {
            org.slf4j.MDC.put("traceId", traceId);
            try {
                task.run();
            } finally {
                org.slf4j.MDC.remove("traceId");
            }
        });
    }

    public WechatBotRunner(LlmService llmService,
                           TtsService ttsService,
                           ImageService imageService,
                           com.luciano.conversation.ConversationService conversationService,
                           SkillRouter skillRouter,
                           RagService ragService,
                           UserClientRegistry userClientRegistry,
                           com.luciano.agent.AgentRouter agentRouter,
                           AudioCodec audioCodec) {
        this.llmService = llmService;
        this.ttsService = ttsService;
        this.imageService = imageService;
        this.conversationService = conversationService;
        this.skillRouter = skillRouter;
        this.ragService = ragService;
        this.userClientRegistry = userClientRegistry;
        this.agentRouter = agentRouter;
        this.audioCodec = audioCodec;
    }

    /** 处理单条消息:按消息类型分发。先缓存图片,再处理文字,保证图文合并能匹配 */
    public void handleMessage(ILinkClient client, WeixinMessage msg) {
        if (client == null || msg.getItem_list() == null) {
            return;
        }
        String fromUser = msg.getFrom_user_id();
        // 登记用户与当前会话客户端,供日程提醒等主动推送定位
        userClientRegistry.bind(fromUser, client);
        List<MessageItem> items = msg.getItem_list();
        // 第一遍:先下载缓存图片,确保文字消息检查待合并图片时已就绪
        for (MessageItem item : items) {
            if (item.getImage_item() != null) {
                log.info("收到来自 {} 的图片消息", fromUser);
                handleImageMessage(client, fromUser, item);
            }
        }
        // 第二遍:处理文字/语音
        for (MessageItem item : items) {
            if (item.getText_item() != null) {
                String userText = item.getText_item().getText();
                log.info("[{}] 收到来自 {} 的文本消息: {}", now(), fromUser, userText);
                handleTextMessage(client, fromUser, userText);
            } else if (item.getVoice_item() != null) {
                // 语音消息:读取服务端转写文本,走与文本相同的 Skill/RAG/LLM 路由
                String voiceText = item.getVoice_item().getText();
                log.info("收到来自 {} 的语音消息,转写文本: {}", fromUser, voiceText);
                routeText(client, fromUser, voiceText);
            }
        }
    }

    /** 文本消息:直接走 LLM(意图交给 LLM 自主判断);若有极短时间内待合并的图片则图文合并,否则秒回 */
    private void handleTextMessage(ILinkClient client, String fromUser, String userText) {
        ImagePendingStore.PendingImage pending = ImagePendingStore.getPending(fromUser);
        if (pending != null) {
            log.info("检测到 {} 的待合并图片(id={}),执行图文合并识别", fromUser, pending.id());
            // 识别耗时长,异步执行,避免阻塞消息回调线程
            executeSerial(fromUser, () -> handleImageRecognitionWithText(client, fromUser, userText, pending));
            return;
        }
        // 下行图片关键词("下面/这张/这个/下图"):用户提到图片但还没发,缓存为待合并文字等图片到达合并
        if (hasImageReferDirective(userText)) {
            ImagePendingStore.putText(fromUser, userText);
            log.info("检测到下行图片关键词,等待图片上传,fromUser = {}, text = {}", fromUser, userText);
            // 用较长窗口等待(用户明确要图,给足上传时间);超时无图则提示发图
            final long waitMs = ImagePendingStore.MERGE_WINDOW_MS;
            executeSerial(fromUser, () -> {
                try {
                    Thread.sleep(waitMs);
                    ImagePendingStore.PendingText pt = ImagePendingStore.takeText(fromUser);
                    if (pt != null) {
                        log.info("等待图片超时,提示发图,fromUser = {}", fromUser);
                        safeSendText(client, fromUser, "请先发送图片,我会结合图片和你的描述来分析~");
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            });
            return;
        }
        // 纯文本三层路由:Skill → RAG → LLM 兜底(文本与语音共用,不检查图片)
        routeText(client, fromUser, userText);
    }

    /** 纯文本三层路由:Skill 命中直接执行 → Agent 规划任务 → RAG 增强 → LLM 兜底 */
    private void routeText(ILinkClient client, String toUserId, String userText) {
        Skill skill = skillRouter.match(userText);
        if (skill != null) {
            log.info("[Skill] {} 命中技能 {},跳过 LLM", toUserId, skill.name());
            safeSendText(client, toUserId, skill.execute(toUserId, userText));
            return;
        }
        // Agent 路由:规划类目标或进行中任务的澄清回复
        if (agentRouter.shouldHandle(toUserId, userText)) {
            log.info("[Agent] {} 接管消息,userId = {}", toUserId, userText);
            executeSerial(toUserId, () -> {
                com.luciano.agent.AgentRouter.AgentResponse resp = agentRouter.handle(toUserId, userText,
                        t -> safeSendText(client, toUserId, t));
                if (resp != null) {
                    safeSendText(client, toUserId, resp.immediateReply());
                    if (resp.asyncPlan() != null) {
                        String plan = resp.asyncPlan().get();
                        safeSendText(client, toUserId, plan);
                    }
                }
            });
            return;
        }
        String knowledge = ragService.retrieve(userText);
        if (knowledge != null) {
            log.info("[RAG] {} 命中知识库,走增强 Prompt", toUserId);
            handleTextWithKnowledge(client, toUserId, userText, knowledge);
            return;
        }
        dispatchByIntent(client, toUserId, userText);
    }

    /** 命中 RAG:把知识库内容注入 Prompt 后走 LLM */
    private void handleTextWithKnowledge(ILinkClient client, String toUserId, String userText, String knowledge) {
        executeSerial(toUserId, () -> {
            try {
                long t0 = System.currentTimeMillis();
                LlmService.ChatResult chat = llmService.chatWithTrace(toUserId, userText, knowledge);
                log.info("[RAG] LLM 调用耗时 {}ms,userId = {}", System.currentTimeMillis() - t0, toUserId);
                safeSendText(client, toUserId, chat.reply());
                sendGeneratedImages(client, toUserId);
            } catch (Exception e) {
                log.error("RAG 消息处理异常,toUserId = {}", toUserId, e);
                safeSendText(client, toUserId, "抱歉,处理你的消息时出错了,请稍后再试。");
            }
        });
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
    private void handleImageMessage(ILinkClient client, String fromUser, MessageItem item) {
        try {
            byte[] imageBytes = client.downloadImageFromMessageItem(item);
            if (imageBytes == null || imageBytes.length == 0) {
                log.warn("图片下载失败或为空,fromUser = {}", fromUser);
                safeSendText(client, fromUser, "抱歉,图片下载失败,请重试。");
                return;
            }
            String fileName = "image_" + UUID.randomUUID().toString().substring(0, 8) + ".png";
            String imageId = ImagePendingStore.put(fromUser, imageBytes, fileName);
            log.info("图片已缓存,fromUser = {}, imageId = {}", fromUser, imageId);
            // 立即反馈,避免识别期间(10~30s)用户静默等待
            safeSendText(client, fromUser, "收到图片,正在识别,请稍候~");

            // 先发文字后发图:图片到达时检查是否有待合并文字,有则图文合并
            ImagePendingStore.PendingText pendingText = ImagePendingStore.takeText(fromUser);
            if (pendingText != null) {
                log.info("检测到 {} 的待合并文字(id={}),执行图文合并识别", fromUser, pendingText.id());
                // 识别耗时长,异步执行,避免阻塞消息回调线程
                executeSerial(fromUser, () -> handleImageRecognitionWithText(client, fromUser, pendingText.text(),
                        ImagePendingStore.getPending(fromUser)));
                return;
            }

            // 无待合并文字:立即单独识图(不等,避免"发图不说话"长时间无响应)
            ImagePendingStore.PendingImage img = ImagePendingStore.takeForFallback(fromUser, imageId);
            if (img != null) {
                log.info("立即单独识图,fromUser = {}, imageId = {}", fromUser, imageId);
                executeSerial(fromUser, () -> handleImageRecognition(client, fromUser, img));
            }
        } catch (IOException e) {
            log.error("图片下载失败,fromUser = {}", fromUser, e);
            safeSendText(client, fromUser, "抱歉,图片处理失败,请稍后再试。");
        }
    }

    /** 单独识图(无文字描述),识别结果存入上下文供后续对话引用 */
    private void handleImageRecognition(ILinkClient client, String toUserId, ImagePendingStore.PendingImage img) {
        String result = imageService.recognize(img.bytes(), img.fileName(), null);
        log.info("图片识别结果 {}: {}", toUserId, result);
        saveImageContext(toUserId, "用户发送了一张图片", result);
        safeSendText(client, toUserId, result);
    }

    /** 图文合并识别:文字 + 待合并图片一起交给多模态模型,结果存入上下文 */
    private void handleImageRecognitionWithText(ILinkClient client, String toUserId, String userText,
                                                ImagePendingStore.PendingImage img) {
        // 消费该图片(若已被消费则按普通文本处理)
        ImagePendingStore.PendingImage consumed = ImagePendingStore.take(toUserId, img.id());
        if (consumed == null) {
            dispatchByIntent(client, toUserId, userText);
            return;
        }
        String result = imageService.recognize(consumed.bytes(), consumed.fileName(), userText);
        log.info("图文合并识别结果 {}: {}", toUserId, result);
        saveImageContext(toUserId, "用户发送了一张图片,并补充描述: " + userText, result);
        safeSendText(client, toUserId, result);
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
    private void dispatchByIntent(ILinkClient client, String toUserId, String userText) {
        if (userText == null || userText.isBlank()) {
            log.info("收到空文本消息,忽略,toUserId = {}", toUserId);
            return;
        }
        // 语音关键词快速判断:用户明确要求语音时优先语音路径(即使夹杂天气/问答等复合需求)
        if (hasVoiceDirective(userText)) {
            log.info("检测到语音指令,走语音回复,toUserId = {}", toUserId);
            executeSerial(toUserId, () -> handleVoice(client, toUserId, userText));
            return;
        }
        // 其余一律交给 LLM 工具链:LLM 自主决定聊天/天气/生图/搜索/邮件,秒回
        executeSerial(toUserId, () -> {
            try {
                handleText(client, toUserId, userText);
            } catch (Exception e) {
                log.error("消息处理异常,toUserId = {}", toUserId, e);
                safeSendText(client, toUserId, "抱歉,处理你的消息时出错了,请稍后再试。");
            }
        });
    }

    /** 判断文本是否为生图意图(用于生图前给用户即时反馈) */
    private boolean looksLikeImageRequest(String text) {
        if (text == null) {
            return false;
        }
        return (text.contains("画") || text.contains("绘") || text.contains("生成"))
                && (text.contains("一张") || text.contains("张") || text.contains("图片")
                || text.contains("图") || text.contains("插画") || text.contains("壁纸")
                || text.contains("头像") || text.contains("海报"));
    }

    /** 判断文本是否含明确语音指令关键词 */
    private boolean hasVoiceDirective(String text) {
        return text.contains("用语音") || text.contains("语音回复")
                || text.contains("语音说") || text.contains("语音回答")
                || text.contains("用声音") || text.contains("朗读")
                || text.contains("读给我听") || text.contains("念给我听");
    }

    /** 文本问答(带上下文 + 工具调用),若生图工具生成了图片则一并发送 */
    private void handleText(ILinkClient client, String toUserId, String userText) {
        // 生图意图:先给即时反馈,避免 20~25s 静默等待
        if (looksLikeImageRequest(userText)) {
            safeSendText(client, toUserId, "好的,正在为你生成,请稍候~");
        }
        long t0 = System.currentTimeMillis();
        LlmService.ChatResult chat = llmService.chatWithTrace(toUserId, userText);
        long t1 = System.currentTimeMillis();
        String reply = chat.reply();
        log.info("[{}] LLM 调用耗时 {}ms,userId = {}", now(), t1 - t0, toUserId);
        log.info("LLM 回复 {}: {}", toUserId, reply);
        safeSendText(client, toUserId, reply);
        log.info("[{}] 发送用户耗时 {}ms,userId = {}", now(), System.currentTimeMillis() - t1, toUserId);
        sendGeneratedImages(client, toUserId);
    }

    /** 发送工具生成的图片(生图工具的结果缓存),普通 LLM 与 RAG 两个路径共用 */
    private void sendGeneratedImages(ILinkClient client, String toUserId) {
        List<byte[]> pendingImages = GenerateImageTool.takePendingImages(toUserId);
        if (pendingImages == null) {
            return;
        }
        for (byte[] pendingImage : pendingImages) {
            String fileName = "image_" + UUID.randomUUID().toString().substring(0, 8) + ".png";
            try {
                client.sendImage(toUserId, pendingImage, fileName, "为你生成的图片");
                log.info("工具生成的图片已发送 {}: {}", toUserId, fileName);
            } catch (IOException e) {
                log.error("工具生成的图片发送失败,toUserId = {}", toUserId, e);
            }
        }
    }

    /** 语音回复:LLM 生成文本 -> TTS 合成 mp3 -> 转 SILK -> 发送微信原生语音 */
    private void handleVoice(ILinkClient client, String toUserId, String userText) {
        try {
            client.startTyping(toUserId);
            String replyText = llmService.chat(toUserId, userText);
            byte[] mp3 = ttsService.synthesize(replyText);
            if (mp3 == null) {
                log.warn("语音合成失败,改发文本,toUserId = {}", toUserId);
                safeSendText(client, toUserId, replyText);
                return;
            }
            try {
                // 转 SILK 发微信原生语音;失败(如 ffmpeg 不可用)降级发 mp3 文件
                byte[] silk = audioCodec.mp3ToSilk(mp3);
                int playTimeMs = Math.max(500, silk.length / 3);
                client.sendVoice(toUserId, silk, "voice.silk", playTimeMs, 24000);
                log.info("语音回复已发送 {}: {}", toUserId, replyText);
            } catch (Exception e) {
                log.warn("语音转 SILK 失败,降级发 mp3 文件,toUserId = {}: {}", toUserId, e.getMessage());
                client.sendFile(toUserId, mp3, "voice_" + UUID.randomUUID().toString().substring(0, 8) + ".mp3", "语音回复");
            }
        } catch (Exception e) {
            log.error("语音回复失败,toUserId = {}", toUserId, e);
            safeSendText(client, toUserId, "抱歉,语音回复失败,请稍后再试。");
        }
    }

    /** 安全发送文本,吞掉 IO 异常 */
    private void safeSendText(ILinkClient client, String toUserId, String text) {
        if (client == null || text == null || text.isBlank()) {
            log.warn("回复文本为空或会话已失效,不发送,toUserId = {}", toUserId);
            return;
        }
        try {
            client.sendText(toUserId, text);
        } catch (IOException e) {
            log.error("文本消息发送失败,toUserId = {}", toUserId, e);
        }
    }

    /** 当前时间(HH:mm:ss.SSS),用于链路耗时日志 */
    private static String now() {
        return LocalTime.now().format(DateTimeFormatter.ofPattern("HH:mm:ss.SSS"));
    }

    @PreDestroy
    public void stop() {
        userShards.forEach(ThreadPoolExecutor::shutdownNow);
    }
}
