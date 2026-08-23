package com.luciano.wechat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.wechat.ilink.sdk.core.model.MessageItem;
import com.github.wechat.ilink.sdk.core.model.VoiceItem;
import com.github.wechat.ilink.sdk.core.model.WeixinMessage;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 多会话共享的消息处理大脑:所有会话收到的消息都交给它处理(意图识别、
 * 天气、Function Calling 工具、语音识别/回复、对话记忆),发送时通过
 * 传入的 {@link WechatSession} 用对应 client 回复,实现多用户互不干扰。
 *
 * <p>处理线程池是所有会话共享的(简单起见),语音链路单独隔离,
 * 避免慢任务占满线程池堵住其他用户的普通回复。
 */
@Component
public class MessageDispatcher {

    private static final Logger log = LoggerFactory.getLogger(MessageDispatcher.class);

    /** 解析 LLM 意图 JSON 用的 Jackson 实例。 */
    private static final ObjectMapper objectMapperForIntent = new ObjectMapper();

    private final DashScopeClient dashScopeClient;
    private final WeatherClient weatherClient;
    private final MemoryStore memoryStore;
    private final AudioCodec audioCodec;
    private final FunctionCallService functionCallService;

    /** 天气查询的默认城市(消息里没提到城市时使用)。 */
    @Value("${weather.default-city:南京}")
    private String defaultCity;

    /** 语音回复最多播报的字数上限。 */
    @Value("${voice.max-chars:120}")
    private int voiceMaxChars;

    /** 处理自动回复的共享线程池(所有会话共用)。 */
    private final ExecutorService replyExecutor = Executors.newFixedThreadPool(4);

    /** 语音消息专用线程池:语音识别链路较慢,单独隔离避免卡住普通回复。 */
    private final ExecutorService voiceExecutor = Executors.newFixedThreadPool(2);

    public MessageDispatcher(DashScopeClient dashScopeClient, WeatherClient weatherClient,
                             MemoryStore memoryStore, AudioCodec audioCodec,
                             FunctionCallService functionCallService) {
        this.dashScopeClient = dashScopeClient;
        this.weatherClient = weatherClient;
        this.memoryStore = memoryStore;
        this.audioCodec = audioCodec;
        this.functionCallService = functionCallService;
    }

    @PreDestroy
    public void destroy() {
        replyExecutor.shutdownNow();
        voiceExecutor.shutdownNow();
    }

    /**
     * 某个会话收到新消息:积压供 REST 接口取走,并异步处理自动回复。
     */
    public void onMessages(WechatSession session, List<WeixinMessage> messages) {
        if (messages == null || messages.isEmpty()) {
            return;
        }
        session.addPendingMessages(messages);
        log.info("[{}] 收到 {} 条消息", session.getSessionId(), messages.size());
        for (WeixinMessage msg : messages) {
            // 语音消息单独走 voiceExecutor,避免慢链路占满共享线程池堵住其他回复
            boolean hasVoice = msg.getItem_list() != null
                    && msg.getItem_list().stream().anyMatch(i -> i.getVoice_item() != null);
            ExecutorService executor = hasVoice ? voiceExecutor : replyExecutor;
            executor.submit(() -> handleMessage(session, msg));
        }
    }

    /**
     * 处理单条消息并自动回复(在异步线程中执行)。
     */
    private void handleMessage(WechatSession session, WeixinMessage msg) {
        String sessionId = session.getSessionId();
        String fromUserId = msg.getFrom_user_id();
        session.setLastFromUserId(fromUserId);
        try {
            List<MessageItem> items = msg.getItem_list();
            if (items == null || items.isEmpty()) {
                return;
            }
            for (MessageItem item : items) {
                // 图片消息 → 理解图片并回复描述
                if (item.getImage_item() != null) {
                    byte[] imageBytes = session.downloadImage(item);
                    String description = dashScopeClient.describeImage(imageBytes, "请用简洁的中文描述这张图片的内容。");
                    session.sendText(fromUserId, description);
                    return;
                }
                // 语音消息 → 转文字(优先微信自带转写,兜底 DashScope ASR)→ 统一意图分发,默认用语音回复
                if (item.getVoice_item() != null) {
                    // 先给出即时反馈,避免语音链路慢时用户以为机器人没反应
                    try {
                        session.startTyping(fromUserId);
                    } catch (Exception ignored) {
                        // typing 指示失败不影响主流程
                    }
                    session.sendText(fromUserId, "🔊 收到语音,正在识别,请稍候…");
                    try {
                        Transcription tr = transcribeVoice(session, item, fromUserId);
                        if (tr.text() == null || tr.text().isBlank()) {
                            String reason = tr.error() == null ? "识别服务暂不可用" : tr.error();
                            if (reason.length() > 100) {
                                reason = reason.substring(0, 100) + "...";
                            }
                            session.sendText(fromUserId, "抱歉,没听懂你的语音(原因:" + reason + "),请发文字或再试一次~");
                            return;
                        }
                        handleIntent(session, fromUserId, tr.text(), true);
                        return;
                    } finally {
                        try {
                            session.stopTyping(fromUserId);
                        } catch (Exception ignored) {
                            // 忽略
                        }
                    }
                }
                // 文本消息 → 统一意图识别,按意图分发(文字/语音/图片/天气)
                if (item.getText_item() != null) {
                    String text = item.getText_item().getText();
                    handleIntent(session, fromUserId, text, false);
                    return;
                }
            }
        } catch (Exception e) {
            log.error("[{}] 处理消息失败: {}", sessionId, e.getMessage(), e);
            try {
                session.sendText(fromUserId, "抱歉,处理出错了,请稍后再试~");
            } catch (Exception ignored) {
                // 连错误提示都发不出去就放弃
            }
        }
    }

    /** 语音转写结果:text 为空表示失败,error 为失败原因。 */
    private record Transcription(String text, String error) {
    }

    /**
     * 语音消息转文字:优先用微信服务端自带的转写,为空时下载语音走 DashScope ASR。
     */
    private Transcription transcribeVoice(WechatSession session, MessageItem item, String fromUserId) {
        VoiceItem voice = item.getVoice_item();
        if (voice == null) {
            return new Transcription("", "消息里没有语音");
        }
        String serverText = voice.getText();
        if (serverText != null && !serverText.isBlank()) {
            log.info("语音服务端转写成功(from={}): {}", fromUserId, serverText);
            return new Transcription(serverText.trim(), null);
        }
        log.info("语音无服务端转写,走本地 ASR: encode_type={}, sample_rate={}, bits={}, playtime={}ms",
                voice.getEncode_type(), voice.getSample_rate(), voice.getBits_per_sample(), voice.getPlaytime());
        long t0 = System.currentTimeMillis();
        try {
            byte[] silk = session.downloadVoice(item);
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
            return new Transcription(text, null);
        } catch (Exception e) {
            String error = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
            log.warn("语音识别链路失败(from={}, 耗时 {}ms): {}", fromUserId, System.currentTimeMillis() - t0, error, e);
            return new Transcription("", error);
        }
    }

    // ==================== 意图识别 ====================

    /** 用户消息意图类型:决定回复方式与要调用的能力。 */
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

    /** 意图识别结果:arg1/arg2 语义随 type 而定。 */
    private record Intent(IntentType type, String arg1, String arg2) {
    }

    /** 语音指令清理。 */
    private static final Pattern VOICE_DIRECTIVE = Pattern.compile(
            "^(?:请|麻烦|帮我|帮忙|帮我用|请用|麻烦用)?(?:用语音|语音|用声音)?(?:回复|回答|说|告诉我|讲给我听|念给我听|读给我听|念出来|读出来)?(?:一下)?"
    );

    /** 天气文本前缀清理。 */
    private static final Pattern WEATHER_PREFIX = Pattern.compile(
            "^(?:请|麻烦|帮我|帮忙|请问|我想知道|想知道|帮我查|帮我看看)?(?:查|查询|问|看看|看一下|查一下|看下)?(?:一下)?"
    );

    /** 天气文本里的时间词。 */
    private static final Pattern TIME_WORDS = Pattern.compile(
            "(?:今天|明天|后天|昨天|现在|未来|这几天|几天|下周|这周|本周|今晚|明晚|周末)"
    );

    /** 天气城市名提取。 */
    private static final Pattern CITY_PATTERN = Pattern.compile(
            "([\\u4e00-\\u9fa5]{2,4}?)(?:的)?(?:天气|气温|温度|预报)"
    );

    /** 意图识别总入口:关键词快路径 → LLM 结构化判定(严格 JSON)→ 旧格式兜底 → 普通对话。 */
    private Intent analyzeIntent(String text) throws Exception {
        String clean = text == null ? "" : text.trim();
        if (clean.isEmpty()) {
            return new Intent(IntentType.CHAT, clean, "");
        }

        // 1. 关键词快路径
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

    /** 关键词快路径。 */
    private Intent fastIntent(String text) {
        if (Pattern.matches("(?s).*(画|绘制|生成|制作|设计|创作).{0,12}(图片|图|插画|壁纸|头像|海报|漫画|logo|LOGO|表情包).*", text)
                || text.matches("(?s)^(画|帮我画|请画|生成|制作|设计|创作).*")) {
            return new Intent(IntentType.IMAGE, text, "");
        }
        if (text.contains("语音回复") || text.contains("用语音") || text.contains("语音说")
                || text.contains("语音回答") || text.contains("说给我听") || text.contains("念给我听")
                || text.contains("读给我听") || text.contains("用声音") || text.contains("用说的")
                || text.contains("voice") || text.contains("Voice")) {
            return new Intent(IntentType.VOICE, stripVoiceDirective(text), "");
        }
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

    /** 解析 LLM 返回的 JSON。 */
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

    /** 兼容旧的 "type|内容(可选)" 一行格式。 */
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

    private String stripVoiceDirective(String text) {
        String s = VOICE_DIRECTIVE.matcher(text).replaceFirst("").trim();
        return s.isEmpty() ? text : s;
    }

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

    private String extractTime(String text) {
        if (text.contains("明天")) {
            return "明天";
        }
        if (text.contains("后天") || text.contains("几天") || text.contains("未来") || text.contains("下周")) {
            return "几天";
        }
        return "现在";
    }

    // ==================== 意图分发与回复 ====================

    /**
     * 统一意图分发:按意图选择回复方式(文字 / 语音 / 图片),通过 session 的 client 回复。
     */
    private void handleIntent(WechatSession session, String fromUserId, String text, boolean preferVoice) throws Exception {
        Intent intent = analyzeIntent(text);
        switch (intent.type()) {
            case IMAGE -> {
                session.sendText(fromUserId, "正在生成图片,请稍候...");
                byte[] imageBytes = dashScopeClient.generateImage(intent.arg1());
                session.sendImage(fromUserId, imageBytes, "generated.png");
            }
            case WEATHER -> {
                try {
                    String weatherText = buildWeatherText(intent.arg1(), intent.arg2());
                    if (preferVoice) {
                        sendVoiceReply(session, fromUserId, weatherText);
                    } else {
                        session.sendText(fromUserId, weatherText);
                    }
                } catch (Exception e) {
                    log.warn("天气查询失败(city={}): {}", intent.arg1(), e.getMessage());
                    String asked = intent.arg1() == null || intent.arg1().isBlank() ? "这个城市" : "「" + intent.arg1() + "」";
                    session.sendText(fromUserId, "暂时查不到" + asked + "的天气,换个说法试试,比如「北京天气」~");
                }
            }
            case VOICE -> {
                String content = intent.arg1() == null || intent.arg1().isBlank() ? text : intent.arg1();
                sendVoiceReply(session, fromUserId, generateReply(session, fromUserId, content, true));
            }
            default -> {
                // 普通对话走 Function Calling:模型可调用 天气/时间/计算 等工具
                ToolReply tr = chatWithToolsAndMemory(fromUserId, text, preferVoice);
                if (preferVoice) {
                    sendVoiceReply(session, fromUserId, tr.finalAnswer());
                } else {
                    session.sendText(fromUserId, appendToolTrace(tr.finalAnswer(), tr.steps()));
                }
            }
        }
    }

    /** 一次工具化对话的结果。 */
    private record ToolReply(String finalAnswer, List<FunctionCallService.Step> steps) {
    }

    /** 带记忆的 Function Calling 对话,失败时回退普通对话。 */
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
            log.warn("Function Calling 失败,回退普通对话: {}", e.getMessage());
            String fallback = chatWithMemory(fromUserId, text, concise);
            return new ToolReply(fallback, List.of());
        }
    }

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

    // ==================== 天气 ====================

    private String buildWeatherText(String city, String time) throws Exception {
        String effective = weatherClient.sanitizeCity(city);
        if (effective.isBlank()) {
            effective = defaultCity;
        }
        try {
            return weatherTextFor(effective, time);
        } catch (Exception first) {
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

    private String weatherTextFor(String city, String time) throws Exception {
        return switch (time) {
            case "明天" -> weatherClient.formatTomorrow(city, weatherClient.getTomorrow(city));
            case "几天" -> weatherClient.format3d(city, weatherClient.get3d(city));
            default -> {
                Weather.Current now = weatherClient.getNow(city);
                List<Weather.Daily> forecast = weatherClient.get3d(city);
                yield weatherClient.describeToday(city, now, forecast.get(0))
                        + "\n" + weatherClient.format3d(city, forecast);
            }
        };
    }

    // ==================== 对话与语音回复 ====================

    private String generateReply(WechatSession session, String fromUserId, String text, boolean concise) throws Exception {
        if (text != null
                && text.matches(".*(天气|气温|温度|预报|下雨|下雪|台风|湿度|有雨|降雨|几度|多少度).*")) {
            Intent sub = analyzeIntent(text);
            if (sub.type() == IntentType.WEATHER) {
                return buildWeatherText(sub.arg1(), sub.arg2());
            }
        }
        return chatWithToolsAndMemory(fromUserId, text, concise).finalAnswer();
    }

    /** 用语音回复:文字 → 截断 → TTS → SILK → 发语音;超长补发完整文字。 */
    private void sendVoiceReply(WechatSession session, String fromUserId, String text) throws Exception {
        int maxChars = Math.max(30, voiceMaxChars);
        boolean truncated = text.length() > maxChars;
        String speakText = truncated ? truncateForVoice(text, maxChars) : text;
        long t0 = System.currentTimeMillis();
        byte[] mp3 = dashScopeClient.synthesizeSpeech(speakText);
        long t1 = System.currentTimeMillis();
        byte[] silk = audioCodec.mp3ToSilk(mp3);
        long t2 = System.currentTimeMillis();
        int playTimeMs = Math.max(500, silk.length / 3);
        session.sendVoiceWithTranscript(fromUserId, silk, "voice.silk", playTimeMs, 24000, speakText);
        long t3 = System.currentTimeMillis();
        log.info("语音回复耗时: TTS {}ms, 转码 {}ms, 发送 {}ms, 总 {}ms (播报 {} 字, 原文 {} 字{})",
                t1 - t0, t2 - t1, t3 - t2, t3 - t0,
                speakText.length(), text.length(), truncated ? ", 已截断" : "");
        if (truncated) {
            session.sendText(fromUserId, text);
        }
    }

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

    private String chatWithMemory(String fromUserId, String text) throws Exception {
        return chatWithMemory(fromUserId, text, false);
    }

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

    // ==================== 调试接口支持 ====================

    /**
     * 语音发送调试接口(会话级):用不同参数发一条短语音,便于定位语音收不到的原因。
     */
    public Map<String, Object> sendTestVoice(WechatSession session, String toUserId, Integer sampleRate,
                                             String playtimeUnit, boolean withTranscript) throws Exception {
        String target = toUserId == null || toUserId.isBlank() ? session.getLastFromUserId() : toUserId;
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
            session.sendVoiceWithTranscript(target, silk, "voice.silk", playtime, rate, speakText);
        } else {
            session.sendVoice(target, silk, "voice.silk", playtime, rate);
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
        log.info("[{}] 调试语音已发送: toUserId={}, sampleRate={}, playtime={}{}, transcript={}",
                session.getSessionId(), target, rate, playtime, unit, withTranscript);
        return result;
    }

    /**
     * 取字节前 n 个的十六进制,用于诊断语音文件头。
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
}
