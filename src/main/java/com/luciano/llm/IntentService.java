package com.luciano.llm;

import com.alibaba.dashscope.aigc.generation.Generation;
import com.alibaba.dashscope.aigc.generation.GenerationParam;
import com.alibaba.dashscope.aigc.generation.GenerationResult;
import com.alibaba.dashscope.common.Message;
import com.alibaba.dashscope.common.Role;
import com.luciano.config.LlmProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 意图识别服务。
 * 让大模型判断用户消息属于哪类意图,返回结构化结果,
 * 供后续分发到文本回复 / 语音回复 / 文生图 / 天气查询等处理。
 */
@Service
public class IntentService {

    private static final Logger log = LoggerFactory.getLogger(IntentService.class);

    private final LlmProperties properties;

    public IntentService(LlmProperties properties) {
        this.properties = properties;
    }

    /** 识别出的意图类型 */
    public enum Intent {
        /** 普通文本问答,用文字回复 */
        TEXT,
        /** 要求语音回复(把文字转成语音发回) */
        VOICE,
        /** 要求生成图片 */
        IMAGE,
        /** 查询天气 */
        WEATHER,
        /** 无法识别,按普通文本处理 */
        UNKNOWN
    }

    /** 意图识别结果 */
    public record IntentResult(Intent intent, String city) {
    }

    /**
     * 识别用户文本的意图。
     *
     * @param userText 用户消息文本
     * @return 意图结果;识别失败时回退为 TEXT
     */
    public IntentResult detect(String userText) {
        String prompt = """
                请判断用户这句话的意图,只允许返回以下 JSON 之一(不要多余内容):
                {"intent":"text"}        普通聊天或问答,用文字回复
                {"intent":"voice"}       用户明确要求语音/语音回复/朗读(如"用语音说""语音回复")
                {"intent":"image"}       用户要求生成/画/制作图片
                {"intent":"weather"}     用户查询天气(如"天气""气温""冷不冷"),可带城市名
                用户消息: %s
                如果是 weather 且消息里有城市名,返回 {"intent":"weather","city":"城市名"},没有城市则 city 为空字符串
                """.formatted(userText);
        try {
            GenerationParam param = GenerationParam.builder()
                    .model(properties.getModel())
                    .messages(List.of(
                            Message.builder().role(Role.SYSTEM.getValue())
                                    .content("你只输出合法的 JSON,不输出任何解释。")
                                    .build(),
                            Message.builder().role(Role.USER.getValue())
                                    .content(prompt)
                                    .build()))
                    .resultFormat(GenerationParam.ResultFormat.MESSAGE)
                    .build();
            GenerationResult result = new Generation().call(param);
            String text = extractText(result);
            log.debug("意图识别原始返回: {}", text);
            return parse(text);
        } catch (Exception e) {
            log.error("意图识别失败,按 TEXT 处理,userText = {}", userText, e);
            return new IntentResult(Intent.TEXT, null);
        }
    }

    /** 解析 LLM 返回的 JSON,容忍多余前后缀 */
    private IntentResult parse(String text) {        if (text == null) {
            return new IntentResult(Intent.TEXT, null);
        }
        String json = text.trim();
        int start = json.indexOf('{');
        int end = json.lastIndexOf('}');
        if (start >= 0 && end > start) {
            json = json.substring(start, end + 1);
        } else {
            return new IntentResult(Intent.TEXT, null);
        }
        Intent intent = Intent.TEXT;
        if (json.contains("\"voice\"") || json.contains("voice")) {
            intent = Intent.VOICE;
        } else if (json.contains("\"image\"") || json.contains("image")) {
            intent = Intent.IMAGE;
        } else if (json.contains("\"weather\"")) {
            intent = Intent.WEATHER;
        }
        String city = null;
        int cityIdx = json.indexOf("\"city\"");
        if (cityIdx >= 0) {
            int colon = json.indexOf(':', cityIdx);
            int q1 = json.indexOf('"', colon);
            int q2 = q1 > 0 ? json.indexOf('"', q1 + 1) : -1;
            if (q1 > 0 && q2 > q1) {
                city = json.substring(q1 + 1, q2);
            }
        }
        return new IntentResult(intent, city);
    }

    /** 提取 LLM 文本:优先 getText,部分场景内容在 choices[0].message.content */
    private String extractText(GenerationResult result) {
        if (result.getOutput() == null) {
            return null;
        }
        String text = result.getOutput().getText();
        if (text != null && !text.isBlank()) {
            return text;
        }
        if (result.getOutput().getChoices() != null && !result.getOutput().getChoices().isEmpty()) {
            Message msg = result.getOutput().getChoices().get(0).getMessage();
            if (msg != null && msg.getContent() != null && !msg.getContent().isBlank()) {
                return msg.getContent();
            }
        }
        return null;
    }
}
