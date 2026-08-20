package com.luciano.wechat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 微信 iLink Bot 核心服务:持有唯一 {@link ILinkClient},封装登录、收发消息与 LLM 自动回复。
 */
@Service
public class WechatBotService {

    private static final Logger log = LoggerFactory.getLogger(WechatBotService.class);

    /** 解析 LLM 意图 JSON 用的 Jackson 实例。 */
    private static final ObjectMapper objectMapperForIntent = new ObjectMapper();

    private final LoginStateStore loginStateStore;
    private final DashScopeClient dashScopeClient;
    private final WeatherClient weatherClient;
    private final MemoryStore memoryStore;
    private final AudioCodec audioCodec;
    private final FunctionCallService functionCallService;

    /** 微信 iLink 客户端,登录成功后持有连接。 */
    private volatile ILinkClient client;

    /** 天气查询的默认城市(消息里没提到城市时使用)。 */
    @Value("${weather.default-city:南京}")
    private String defaultCity;

    /**
     * 语音回复最多播报的字数上限:微信语音消息约 60 秒时长上限,
     * 超过此字数会自动截断播报并补发完整文字,避免超长语音被微信拒收。
     */
    @Value("${voice.max-chars:120}")
    private int voiceMaxChars;

    /** 当前二维码图片内容,登录后清空。 */
    private volatile String qrcodeImgContent;

    /** 积压的未消费消息。 */
    private final List<WeixinMessage> pendingMessages = new CopyOnWriteArrayList<>();

    private final ScheduledExecutorService poller = Executors.newSingleThreadScheduledExecutor();

    /** 处理自动回复的独立线程池,避免阻塞消息轮询线程。 */
    private final ExecutorService replyExecutor = Executors.newFixedThreadPool(4);

    /**
     * 语音消息专用线程池:语音识别链路较慢(下载/解码/ASR 都可能耗时较长),
     * 单独隔离,避免卡住普通文字/图片回复。
     */
    private final ExecutorService voiceExecutor = Executors.newFixedThreadPool(2);

    private final AtomicBoolean loginTriggered = new AtomicBoolean(false);

    public WechatBotService(LoginStateStore loginStateStore, DashScopeClient dashScopeClient, WeatherClient weatherClient, MemoryStore memoryStore, AudioCodec audioCodec, FunctionCallService functionCallService) {
        this.loginStateStore = loginStateStore;
        this.dashScopeClient = dashScopeClient;
        this.weatherClient = weatherClient;
        this.memoryStore = memoryStore;
        this.audioCodec = audioCodec;
        this.functionCallService = functionCallService;
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
        voiceExecutor.shutdownNow();
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

    public void sendVoice(String toUserId, byte[] bytes, String fileName, Integer playTimeMs, Integer sampleRate) throws Exception {
        client.sendVoice(toUserId, bytes, fileName, playTimeMs, sampleRate);
    }

    /**
     * 发送语音并附带转写文本(部分服务端校验需要 text 字段)。
     */
    public void sendVoiceWithTranscript(String toUserId, byte[] bytes, String fileName,
                                        Integer playTimeMs, Integer sampleRate, String transcriptText) throws Exception {
        client.sendVoice(toUserId, bytes, fileName, playTimeMs, sampleRate, null, null, null, transcriptText);
    }

    /**
     * 语音发送调试接口:用不同参数(采样率/时长单位/是否带转写文本)发一条短语音,
     * 返回实际使用的参数与各环节耗时,便于定位服务端拒收的原因。
     *
     * @param toUserId     接收人(为空时用最近发消息的用户)
     * @param sampleRate   SILK 采样率,默认 24000,可试 16000
     * @param playtimeUnit playtime 单位:"ms"(默认)或 "s"
     * @param withTranscript 是否附带转写文本
     */
    public Map<String, Object> sendTestVoice(String toUserId, Integer sampleRate, String playtimeUnit, boolean withTranscript) throws Exception {
        String target = toUserId == null || toUserId.isBlank() ? lastFromUserId : toUserId;
        if (target == null || target.isBlank()) {
            throw new IllegalStateException("toUserId 为空,且还没有用户给机器人发过消息");
        }
        String speakText = "你好,我是小助手,这是一条测试语音,你能听到吗?";
        int rate = sampleRate != null && sampleRate > 0 ? sampleRate : 24000;
        long t0 = System.currentTimeMillis();
        byte[] mp3 = dashScopeClient.synthesizeSpeech(speakText);
        long t1 = System.currentTimeMillis();
        byte[] silk = audioCodec.mp3ToSilk(mp3);
        long t2 = System.currentTimeMillis();
        int playtime = "s".equalsIgnoreCase(playtimeUnit)
                ? Math.max(1, silk.length / 3000)   // 秒
                : Math.max(500, silk.length / 3);   // 毫秒
        String unit = "s".equalsIgnoreCase(playtimeUnit) ? "seconds" : "milliseconds";
        if (withTranscript) {
            client.sendVoice(target, silk, "voice.silk", playtime, rate, null, null, null, speakText);
        } else {
            client.sendVoice(target, silk, "voice.silk", playtime, rate);
        }
        long t3 = System.currentTimeMillis();

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("toUserId", target);
        result.put("speakText", speakText);
        result.put("silkBytes", silk.length);
        result.put("sampleRate", rate);
        result.put("playtimeValue", playtime);
        result.put("playtimeUnit", unit);
        result.put("withTranscript", withTranscript);
        result.put("ttsMs", t1 - t0);
        result.put("encodeMs", t2 - t1);
        result.put("sendMs", t3 - t2);
        result.put("totalMs", t3 - t0);
        log.info("调试语音已发送: toUserId={}, sampleRate={}, playtime={}{}, transcript={}",
                target, rate, playtime, unit, withTranscript);
        return result;
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
            // 语音消息单独走 voiceExecutor,避免慢链路占满共享线程池堵住其他回复
            boolean hasVoice = msg.getItem_list() != null
                    && msg.getItem_list().stream().anyMatch(i -> i.getVoice_item() != null);
            ExecutorService executor = hasVoice ? voiceExecutor : replyExecutor;
            executor.submit(() -> handleMessage(msg));
        }
    }

    /**
     * 处理单条消息并自动回复(在异步线程中执行)。
     */
    private void handleMessage(WeixinMessage msg) {
        String fromUserId = msg.getFrom_user_id();
        lastFromUserId = fromUserId;
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
                // 语音消息 → 转文字(优先微信自带转写,兜底 DashScope ASR)→ 统一意图分发,默认用语音回复
                if (item.getVoice_item() != null) {
                    // 先给出即时反馈,避免语音链路慢时用户以为机器人没反应
                    try {
                        client.startTyping(fromUserId);
                    } catch (Exception ignored) {
                        // typing 指示失败不影响主流程
                    }
                    client.sendText(fromUserId, "🔊 收到语音,正在识别,请稍候…");
                    try {
                        String text = transcribeVoice(item, fromUserId);
                        if (text == null || text.isBlank()) {
                            String reason = lastVoiceError == null ? "识别服务暂不可用" : lastVoiceError;
                            if (reason.length() > 100) {
                                reason = reason.substring(0, 100) + "...";
                            }
                            client.sendText(fromUserId, "抱歉,没听懂你的语音(原因:" + reason + "),请发文字或再试一次~");
                            return;
                        }
                        handleIntent(fromUserId, text, true);
                        return;
                    } finally {
                        try {
                            client.stopTyping(fromUserId);
                        } catch (Exception ignored) {
                            // 忽略
                        }
                    }
                }
                // 文本消息 → 统一意图识别,按意图分发(文字/语音/图片/天气)
                if (item.getText_item() != null) {
                    String text = item.getText_item().getText();
                    handleIntent(fromUserId, text, false);
                    return;
                }
            }
        } catch (Exception e) {
            log.error("处理消息失败: {}", e.getMessage(), e);
            try {
                client.sendText(fromUserId, "抱歉,处理出错了,请稍后再试~");
            } catch (Exception ignored) {
                // 连错误提示都发不出去就放弃
            }
        }
    }

    /**
     * 用户消息意图类型:决定回复方式与要调用的能力。
     */
    private enum IntentType {
        /** 普通文字对话 → 文字回复 */
        CHAT,
        /** 用户要求语音回复 → TTS 语音回复 */
        VOICE,
        /** 用户要求生成图片 → 图片回复 */
        IMAGE,
        /** 天气查询 → 文字回复 */
        WEATHER
    }

    /**
     * 意图识别结果:arg1/arg2 语义随 type 而定。
     * WEATHER: arg1=城市, arg2=时间;VOICE/IMAGE: arg1=要处理的内容;CHAT: 无。
     */
    private record Intent(IntentType type, String arg1, String arg2) {
    }

    /** 语音指令清理:去掉开头的「请/帮我/用语音回复」等语气词,留下真正要说的内容。 */
    private static final Pattern VOICE_DIRECTIVE = Pattern.compile(
            "^(?:请|麻烦|帮我|帮忙|帮我用|请用|麻烦用)?(?:用语音|语音|用声音)?(?:回复|回答|说|告诉我|讲给我听|念给我听|读给我听|念出来|读出来)?(?:一下)?"
    );

    /** 天气文本前缀清理:去掉「请/帮我查/看一下」等语气词,只保留城市部分。 */
    private static final Pattern WEATHER_PREFIX = Pattern.compile(
            "^(?:请|麻烦|帮我|帮忙|请问|我想知道|想知道|帮我查|帮我看看)?(?:查|查询|问|看看|看一下|查一下|看下)?(?:一下)?"
    );

    /** 天气文本里的时间词,提取城市前先剔除。 */
    private static final Pattern TIME_WORDS = Pattern.compile(
            "(?:今天|明天|后天|昨天|现在|未来|这几天|几天|下周|这周|本周|今晚|明晚|周末)"
    );

    /** 天气关键词触发后,从文本里提取城市名(城市名紧挨着天气词,取 2~4 个汉字)。 */
    private static final Pattern CITY_PATTERN = Pattern.compile(
            "([\\u4e00-\\u9fa5]{2,4}?)(?:的)?(?:天气|气温|温度|预报)"
    );

    /**
     * 意图识别总入口:关键词快路径 → LLM 结构化判定(严格 JSON)→ 旧格式兜底 → 普通对话。
     */
    private Intent analyzeIntent(String text) throws Exception {
        String clean = text == null ? "" : text.trim();
        if (clean.isEmpty()) {
            return new Intent(IntentType.CHAT, clean, "");
        }

        // 1. 关键词快路径:常见触发词直接判定,确定性高且不耗 LLM
        Intent fast = fastIntent(clean);
        if (fast != null) {
            return fast;
        }

        // 2. LLM 结构化判定:要求只输出一个 JSON 对象
        String prompt = """
                你是微信助手的意图识别器。判断用户消息的意图,只输出一个 JSON 对象,禁止输出任何其他文字、解释或代码块。
                JSON 字段:
                - type: 必填,只能是 "chat"、"voice"、"image"、"weather" 之一
                - content: chat 填消息原文;voice 填用户要求语音回答的问题(没有则填空);image 填要生成图片的详细中文描述;weather 填空
                - city: weather 时填城市名,没提到城市就填空(会用默认城市)
                - time: weather 时填 "现在"、"今天"、"明天"、"几天" 之一,没提到就填 "现在"
                判定规则:
                - 用户要求「用语音回复/语音说/念出来」等 → voice
                - 用户要求「画/生成/制作」图片、插画、壁纸、头像、海报等 → image
                - 用户询问天气、气温、下雨下雪等 → weather
                - 其他普通对话 → chat
                消息:%s
                """.formatted(clean);
        String result = dashScopeClient.chat(prompt, List.of());

        // 3. 解析 LLM 输出:JSON → 旧 "type|内容" 一行格式 → 兜底普通对话
        Intent llm = parseIntentJson(result, clean);
        if (llm != null) {
            return llm;
        }
        Intent legacy = parseLegacyIntent(result, clean);
        if (legacy != null) {
            return legacy;
        }
        return new Intent(IntentType.CHAT, clean, "");
    }

    /**
     * 关键词快路径:命中常见触发词时直接返回意图,不命中返回 null 交给 LLM。
     */
    private Intent fastIntent(String text) {
        // 图片生成:明确的「画/生成/制作…(图片/壁纸/头像…)」指令
        if (Pattern.matches("(?s).*(画|绘制|生成|制作|设计|创作).{0,12}(图片|图|插画|壁纸|头像|海报|漫画|logo|LOGO|表情包).*", text)
                || text.matches("(?s)^(画|帮我画|请画|生成|制作|设计|创作).*")) {
            return new Intent(IntentType.IMAGE, text, "");
        }
        // 语音回复:明确要求用语音/声音回答
        if (text.contains("语音回复") || text.contains("用语音") || text.contains("语音说")
                || text.contains("语音回答") || text.contains("说给我听") || text.contains("念给我听")
                || text.contains("读给我听") || text.contains("用声音") || text.contains("用说的")
                || text.contains("voice") || text.contains("Voice")) {
            return new Intent(IntentType.VOICE, stripVoiceDirective(text), "");
        }
        // 天气查询:必须带有明确提问词,避免把「今天天气不错」这类闲聊误判成查询
        if (text.contains("天气") || text.contains("气温") || text.contains("温度") || text.contains("预报")
                || text.contains("下雨") || text.contains("下雪") || text.contains("台风") || text.contains("湿度")
                || text.contains("有雨") || text.contains("降雨")) {
            boolean query = text.contains("怎么样") || text.contains("如何") || text.contains("多少")
                    || text.contains("几度") || text.contains("几点") || text.contains("怎样")
                    || text.contains("吗") || text.contains("呢") || text.contains("查")
                    || text.contains("看看") || text.contains("想知道") || text.contains("预报")
                    || text.contains("有没有") || text.contains("会不");
            if (query) {
                return new Intent(IntentType.WEATHER, extractCity(text), extractTime(text));
            }
        }
        return null;
    }

    /**
     * 解析 LLM 返回的 JSON(容错:允许 markdown 围栏、前后多余文字),解析失败返回 null。
     */
    private Intent parseIntentJson(String result, String fallback) {
        if (result == null) {
            return null;
        }
        String s = result.trim();
        if (s.startsWith("```")) {
            int first = s.indexOf('\n');
            int last = s.lastIndexOf("```");
            if (first > 0 && last > first) {
                s = s.substring(first + 1, last).trim();
            }
        }
        int start = s.indexOf('{');
        int end = s.lastIndexOf('}');
        if (start < 0 || end <= start) {
            return null;
        }
        try {
            JsonNode node = objectMapperForIntent.readTree(s.substring(start, end + 1));
            String type = node.path("type").asText("").trim().toLowerCase();
            String content = node.path("content").asText("").trim();
            String city = node.path("city").asText("").trim();
            String time = node.path("time").asText("").trim();
            return switch (type) {
                case "voice" -> new Intent(IntentType.VOICE, content.isEmpty() ? fallback : content, "");
                case "image" -> new Intent(IntentType.IMAGE, content.isEmpty() ? fallback : content, "");
                case "weather" -> new Intent(IntentType.WEATHER, city, time.isEmpty() ? "现在" : time);
                case "chat" -> new Intent(IntentType.CHAT, fallback, "");
                default -> null;
            };
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * 兼容旧的 "type|内容(可选)" 一行格式,解析失败返回 null。
     */
    private Intent parseLegacyIntent(String result, String fallback) {
        if (result == null) {
            return null;
        }
        String[] parts = result.trim().split("\\|", -1);
        String type = parts[0].trim().toLowerCase();
        return switch (type) {
            case "voice" -> new Intent(IntentType.VOICE,
                    parts.length > 1 && !parts[1].isBlank() ? parts[1].trim() : fallback, "");
            case "image" -> new Intent(IntentType.IMAGE,
                    parts.length > 1 && !parts[1].isBlank() ? parts[1].trim() : fallback, "");
            case "weather" -> new Intent(IntentType.WEATHER,
                    parts.length > 1 ? parts[1].trim() : "",
                    parts.length > 2 && !parts[2].isBlank() ? parts[2].trim() : "现在");
            default -> null;
        };
    }

    /**
     * 去掉「请/帮我/用语音回复」等语音指令前缀,留下要表达的内容。
     */
    private String stripVoiceDirective(String text) {
        String s = VOICE_DIRECTIVE.matcher(text).replaceFirst("").trim();
        return s.isEmpty() ? text : s;
    }

    /**
     * 从天气文本里提取城市名:先去掉「请/帮我查/看一下」前缀与时间词,
     * 再取紧挨着天气词的 2~4 个汉字;提取不到或命中虚词返回空(→ 用默认城市)。
     */
    private String extractCity(String text) {
        String s = WEATHER_PREFIX.matcher(text).replaceFirst("");
        s = TIME_WORDS.matcher(s).replaceAll("");
        Matcher m = CITY_PATTERN.matcher(s);
        if (m.find()) {
            String city = m.group(1);
            if (!city.matches(".*[会不吗呢吧了的是有没有几明昨后].*")) {
                return city;
            }
        }
        return "";
    }

    /**
     * 从天气文本里提取时间意图(明天 / 几天 / 现在)。
     */
    private String extractTime(String text) {
        if (text.contains("明天")) {
            return "明天";
        }
        if (text.contains("后天") || text.contains("几天") || text.contains("未来") || text.contains("下周")) {
            return "几天";
        }
        return "现在";
    }

    /** 最近一次语音识别失败的原因(供诊断与回复提示)。 */
    private volatile String lastVoiceError = "识别服务暂不可用";

    /** 最近一次给我发消息的用户(供调试接口默认使用)。 */
    private volatile String lastFromUserId;

    /**
     * 语音消息转文字:优先用微信服务端自带的转写,为空时下载语音走 DashScope ASR。
     */
    private String transcribeVoice(MessageItem item, String fromUserId) {
        VoiceItem voice = item.getVoice_item();
        if (voice == null) {
            lastVoiceError = "消息里没有语音";
            return "";
        }
        String serverText = voice.getText();
        if (serverText != null && !serverText.isBlank()) {
            log.info("语音服务端转写成功(from={}): {}", fromUserId, serverText);
            return serverText.trim();
        }
        log.info("语音无服务端转写,走本地 ASR: encode_type={}, sample_rate={}, bits={}, playtime={}ms",
                voice.getEncode_type(), voice.getSample_rate(), voice.getBits_per_sample(), voice.getPlaytime());
        long t0 = System.currentTimeMillis();
        try {
            byte[] silk = client.downloadVoiceFromMessageItem(item);
            long t1 = System.currentTimeMillis();
            log.info("语音下载成功: {} bytes, 头16字节: {}, 耗时 {}ms", silk.length, toHex(silk, 16), t1 - t0);
            int sampleRate = voice.getSample_rate() != null && voice.getSample_rate() > 0
                    ? voice.getSample_rate() : 24000;
            byte[] wav = audioCodec.silkToWav(silk, sampleRate);
            long t2 = System.currentTimeMillis();
            log.info("SILK 转 WAV 成功: {} -> {} bytes (sampleRate={}), 耗时 {}ms",
                    silk.length, wav.length, sampleRate, t2 - t1);
            String text = dashScopeClient.transcribeSpeech(wav, "voice.wav");
            long t3 = System.currentTimeMillis();
            log.info("语音识别成功(from={}): {}, ASR 耗时 {}ms, 总耗时 {}ms", fromUserId, text, t3 - t2, t3 - t0);
            lastVoiceError = "";
            return text;
        } catch (Exception e) {
            lastVoiceError = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
            log.warn("语音识别链路失败(from={}, 耗时 {}ms): {}", fromUserId, System.currentTimeMillis() - t0, lastVoiceError, e);
            return "";
        }
    }

    /**
     * 取字节前 n 个的十六进制,用于诊断语音文件头(判断 SILK 格式)。
     */
    private String toHex(byte[] bytes, int n) {
        int len = Math.min(bytes.length, n);
        StringBuilder sb = new StringBuilder(len * 3);
        for (int i = 0; i < len; i++) {
            if (i > 0) {
                sb.append(' ');
            }
            sb.append(String.format("%02X", bytes[i] & 0xFF));
        }
        return sb.toString();
    }

    /**
     * 统一意图分发:按意图选择回复方式(文字 / 语音 / 图片)。
     *
     * @param preferVoice 语音消息来源时为 true,文字类回复会改用语音发送
     */
    private void handleIntent(String fromUserId, String text, boolean preferVoice) throws Exception {
        Intent intent = analyzeIntent(text);
        switch (intent.type()) {
            case IMAGE -> {
                client.sendText(fromUserId, "正在生成图片,请稍候...");
                byte[] imageBytes = dashScopeClient.generateImage(intent.arg1());
                client.sendImage(fromUserId, imageBytes, "generated.png", "图片");
            }
            case WEATHER -> {
                try {
                    String weatherText = buildWeatherText(intent.arg1(), intent.arg2());
                    if (preferVoice) {
                        sendVoiceReply(fromUserId, weatherText);
                    } else {
                        client.sendText(fromUserId, weatherText);
                    }
                } catch (Exception e) {
                    // 城市查不到/天气接口异常:给友好提示,而不是裸抛错误
                    log.warn("天气查询失败(city={}): {}", intent.arg1(), e.getMessage());
                    String asked = intent.arg1() == null || intent.arg1().isBlank() ? "这个城市" : "「" + intent.arg1() + "」";
                    client.sendText(fromUserId, "暂时查不到" + asked + "的天气,换个说法试试,比如「北京天气」~");
                }
            }
            case VOICE -> {
                String content = intent.arg1() == null || intent.arg1().isBlank() ? text : intent.arg1();
                sendVoiceReply(fromUserId, generateReply(fromUserId, content, true));
            }
            default -> {
                // 普通对话走 Function Calling:模型可调用 天气/时间/计算 等工具,输出执行结果
                ToolReply tr = chatWithToolsAndMemory(fromUserId, text, preferVoice);
                if (preferVoice) {
                    sendVoiceReply(fromUserId, tr.finalAnswer());
                } else {
                    client.sendText(fromUserId, appendToolTrace(tr.finalAnswer(), tr.steps()));
                }
            }
        }
    }

    /** 一次工具化对话的结果:最终回答 + 工具调用轨迹(供回复里展示执行结果)。 */
    private record ToolReply(String finalAnswer, List<FunctionCallService.Step> steps) {
    }

    /**
     * 带记忆的 Function Calling 对话:先尝试工具调用循环,失败时回退普通对话。
     * 无论走哪条路,都会把本轮对话追加进记忆,保持多轮上下文。
     */
    private ToolReply chatWithToolsAndMemory(String fromUserId, String text, boolean concise) throws Exception {
        try {
            FunctionCallService.RunResult result = functionCallService.run(text, concise);
            String answer = result.finalAnswer();
            if (answer == null || answer.isBlank()) {
                throw new IllegalStateException("模型未返回回答");
            }
            memoryStore.append(fromUserId,
                    DashScopeClient.HistoryMessage.user(text),
                    DashScopeClient.HistoryMessage.assistant(answer));
            return new ToolReply(answer, result.steps());
        } catch (Exception e) {
            // 工具调用失败(网络/额度/超轮数)→ 回退普通对话,保证用户永远能得到回答
            log.warn("Function Calling 失败,回退普通对话: {}", e.getMessage());
            String fallback = chatWithMemory(fromUserId, text, concise);
            return new ToolReply(fallback, List.of());
        }
    }

    /**
     * 文字回复时附上工具调用轨迹,让用户直观看到「模型调用了什么工具、传了什么参数」。
     */
    private String appendToolTrace(String answer, List<FunctionCallService.Step> steps) {
        if (steps == null || steps.isEmpty()) {
            return answer;
        }
        StringBuilder trace = new StringBuilder("⚙️ 本次调用工具:\n");
        for (FunctionCallService.Step step : steps) {
            trace.append("• ").append(step.tool()).append('(').append(step.arguments()).append(")\n");
        }
        return answer + "\n\n" + trace.toString().trim();
    }

    /**
     * 按城市与时间意图生成天气文字:
     * 先清洗城市名(去掉「天气/怎么样/今天」等杂质),为空或查不到时自动回落到默认城市。
     */
    private String buildWeatherText(String city, String time) throws Exception {
        String effective = weatherClient.sanitizeCity(city);
        if (effective.isBlank()) {
            effective = defaultCity;
        }
        try {
            return weatherTextFor(effective, time);
        } catch (Exception first) {
            // 指定城市查不到(如「我家天气」)→ 回落默认城市再试一次
            if (!defaultCity.equals(effective)) {
                try {
                    return weatherTextFor(defaultCity, time);
                } catch (Exception fallbackError) {
                    log.warn("默认城市 {} 天气查询也失败: {}", defaultCity, fallbackError.getMessage());
                }
            }
            throw first;
        }
    }

    /**
     * 用指定城市与时间意图生成天气文字。
     */
    private String weatherTextFor(String city, String time) throws Exception {
        return switch (time) {
            case "明天" -> weatherClient.formatTomorrow(city, weatherClient.getTomorrow(city));
            case "几天" -> weatherClient.format3d(city, weatherClient.get3d(city));
            default -> {
                Weather.Current now = weatherClient.getNow(city);
                List<Weather.Daily> forecast = weatherClient.get3d(city);
                // 实时 + 空气质量 + 日出日落/紫外线 + 生活指数 + 3 天预报
                yield weatherClient.describeToday(city, now, forecast.get(0))
                        + "\n" + weatherClient.format3d(city, forecast);
            }
        };
    }

    /**
     * 生成一段回复文字(供 VOICE 意图复用):内容带天气词时优先用确定性天气查询,
     * 其余内容走 Function Calling(模型可调用 天气/时间/计算 工具)。
     *
     * @param concise 语音场景为 true,要求 LLM 简短口语化回答,缩短 TTS 时间
     */
    private String generateReply(String fromUserId, String text, boolean concise) throws Exception {
        if (text != null
                && text.matches(".*(天气|气温|温度|预报|下雨|下雪|台风|湿度|有雨|降雨|几度|多少度).*")) {
            Intent sub = analyzeIntent(text);
            if (sub.type() == IntentType.WEATHER) {
                return buildWeatherText(sub.arg1(), sub.arg2());
            }
        }
        return chatWithToolsAndMemory(fromUserId, text, concise).finalAnswer();
    }

    /**
     * 用语音回复:文字 → 截断到安全长度 → TTS 合成 mp3 → 转 SILK → 发语音。
     * 文本超长时补发完整文字,避免内容丢失(微信语音约 60 秒上限,超长会被拒收)。
     */
    private void sendVoiceReply(String fromUserId, String text) throws Exception {
        int maxChars = Math.max(30, voiceMaxChars); // 防御:配置过低时也至少播 30 字
        boolean truncated = text.length() > maxChars;
        String speakText = truncated ? truncateForVoice(text, maxChars) : text;
        long t0 = System.currentTimeMillis();
        byte[] mp3 = dashScopeClient.synthesizeSpeech(speakText);
        long t1 = System.currentTimeMillis();
        byte[] silk = audioCodec.mp3ToSilk(mp3);
        long t2 = System.currentTimeMillis();
        // 粗略估算播放时长(毫秒):SILK 约 3KB/秒;不精确只影响客户端进度条显示
        int playTimeMs = Math.max(500, silk.length / 3);
        // 带上转写文本,兼容服务端对语音消息 text 字段的校验
        client.sendVoice(fromUserId, silk, "voice.silk", playTimeMs, 24000, null, null, null, speakText);
        long t3 = System.currentTimeMillis();
        log.info("语音回复耗时: TTS {}ms, 转码 {}ms, 发送 {}ms, 总 {}ms (播报 {} 字, 原文 {} 字{})",
                t1 - t0, t2 - t1, t3 - t2, t3 - t0,
                speakText.length(), text.length(), truncated ? ", 已截断" : "");
        if (truncated) {
            // 保证内容完整送达
            client.sendText(fromUserId, text);
        }
    }

    /**
     * 把文本截断到语音播报安全长度,尽量在句号/感叹号/问号处断开。
     */
    private String truncateForVoice(String text, int maxChars) {
        if (text.length() <= maxChars) {
            return text;
        }
        String cut = text.substring(0, maxChars);
        int lastPunct = Math.max(cut.lastIndexOf('。'),
                Math.max(cut.lastIndexOf('!'),
                        Math.max(cut.lastIndexOf('?'),
                                Math.max(cut.lastIndexOf('！'), cut.lastIndexOf('？')))));
        if (lastPunct >= maxChars / 2) {
            cut = cut.substring(0, lastPunct + 1);
        }
        return cut;
    }

    /**
     * 带记忆的文本对话:取该用户历史 → 调 LLM → 把本轮对话追加进记忆。
     */
    private String chatWithMemory(String fromUserId, String text) throws Exception {
        return chatWithMemory(fromUserId, text, false);
    }

    /**
     * 带记忆的文本对话;concise 为 true 时要求 LLM 简短口语化回答(语音播报场景)。
     */
    private String chatWithMemory(String fromUserId, String text, boolean concise) throws Exception {
        List<DashScopeClient.HistoryMessage> history = memoryStore.get(fromUserId);
        String reply = concise
                ? dashScopeClient.chat(text, history, "回答请简洁口语化,控制在150字以内,适合语音播报")
                : dashScopeClient.chat(text, history);
        memoryStore.append(fromUserId,
                DashScopeClient.HistoryMessage.user(text),
                DashScopeClient.HistoryMessage.assistant(reply));
        return reply;
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
