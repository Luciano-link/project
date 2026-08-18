package com.luciano.llm;

import com.alibaba.dashscope.aigc.generation.Generation;
import com.alibaba.dashscope.aigc.generation.GenerationParam;
import com.alibaba.dashscope.aigc.generation.GenerationResult;
import com.alibaba.dashscope.common.Message;
import com.alibaba.dashscope.common.Role;
import com.alibaba.dashscope.exception.InputRequiredException;
import com.alibaba.dashscope.exception.NoApiKeyException;
import com.alibaba.dashscope.utils.Constants;
import com.luciano.config.LlmProperties;
import com.luciano.conversation.ConversationService;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * 阿里云百炼(通义千问)文本生成服务。
 * 支持多轮对话上下文:携带该用户历史消息一起发送,
 * 上下文超长时用滑动窗口裁剪 + LLM 摘要压缩,兼顾记忆与长度控制。
 */
@Service
public class LlmService {

    private static final Logger log = LoggerFactory.getLogger(LlmService.class);

    private static final String SYSTEM_PROMPT = "你是一个友好、乐于助人的微信机器人助手,回答要简洁准确,使用中文。";

    /** 触发摘要压缩的历史消息条数(超过该值则压缩最旧消息) */
    private static final int SUMMARY_TRIGGER = 24;

    /** 摘要压缩后保留的窗口消息条数 */
    private static final int WINDOW_AFTER_SUMMARY = 16;

    private final LlmProperties properties;
    private final ConversationService conversationService;

    public LlmService(LlmProperties properties, ConversationService conversationService) {
        this.properties = properties;
        this.conversationService = conversationService;
    }

    @PostConstruct
    public void init() {
        String apiKey = properties.getApiKey();
        if (apiKey == null || apiKey.isBlank()) {
            log.warn("未配置 llm.api-key,LLM 回复功能将不可用。请在 application-local.properties 中配置,或设置环境变量 LLM_API_KEY");
            return;
        }
        Constants.apiKey = apiKey;
        log.info("阿里云百炼初始化完成,模型 = {}", properties.getModel());
    }

    /**
     * 生成带上下文的文本回复。
     *
     * @param userId   用户标识(微信 from_user_id),用于隔离对话上下文
     * @param userText 用户发送的文本
     * @return 大模型生成的回复;若未配置 API Key 返回提示语
     */
    public String chat(String userId, String userText) {
        String apiKey = properties.getApiKey();
        if (apiKey == null || apiKey.isBlank()) {
            return "抱歉,我还没有配置大模型能力,请联系管理员配置 llm.api-key 后再试。";
        }
        try {
            compressIfNeeded(userId);

            List<Message> history = conversationService.getMessages(userId);
            List<Message> messages = new ArrayList<>();
            String summary = conversationService.getSummary(userId);
            if (summary != null && !summary.isBlank()) {
                messages.add(Message.builder().role(Role.SYSTEM.getValue())
                        .content("以下是更早对话的摘要,供参考:\n" + summary).build());
            }
            messages.add(Message.builder().role(Role.SYSTEM.getValue()).content(SYSTEM_PROMPT).build());
            messages.addAll(history);
            messages.add(Message.builder().role(Role.USER.getValue()).content(userText).build());

            GenerationParam param = GenerationParam.builder()
                    .model(properties.getModel())
                    .messages(messages)
                    .build();
            GenerationResult result = new Generation().call(param);
            String text = result.getOutput().getText();
            if (text == null || text.isBlank()) {
                log.warn("大模型返回为空,requestId = {}", result.getRequestId());
                return "抱歉,大模型没有返回内容,请稍后再试。";
            }

            // 保存本轮对话到上下文
            conversationService.addMessage(userId, Message.builder().role(Role.USER.getValue()).content(userText).build());
            conversationService.addMessage(userId, Message.builder().role(Role.ASSISTANT.getValue()).content(text).build());
            return text;
        } catch (NoApiKeyException e) {
            log.error("未找到 API Key: {}", e.getMessage());
            return "大模型调用失败:未配置 API Key。";
        } catch (InputRequiredException e) {
            log.error("请求参数不合法: {}", e.getMessage());
            return "大模型调用失败:请求参数不合法。";
        } catch (Exception e) {
            log.error("调用阿里云百炼失败", e);
            return "大模型调用失败,请稍后再试。";
        }
    }

    /**
     * 兼容无上下文调用(旧接口),内部委托给带上下文的实现。
     */
    public String chat(String userText) {
        return chat("__anonymous__", userText);
    }

    /**
     * 上下文超长时压缩:把最旧的消息用 LLM 生成摘要,保留最近窗口内的消息。
     */
    private void compressIfNeeded(String userId) throws Exception {
        if (conversationService.size(userId) <= SUMMARY_TRIGGER) {
            return;
        }
        List<Message> oldest = conversationService.trim(userId, WINDOW_AFTER_SUMMARY);
        if (oldest.isEmpty()) {
            return;
        }
        String summary = summarize(oldest);
        if (summary == null || summary.isBlank()) {
            log.warn("摘要压缩失败,丢弃最旧消息,userId = {}", userId);
            return;
        }
        String old = conversationService.getSummary(userId);
        conversationService.setSummary(userId, old == null ? summary : old + "\n" + summary);
        log.info("上下文已压缩,userId = {}, 丢弃 {} 条消息,新摘要长度 = {}", userId, oldest.size(), summary.length());
    }

    /**
     * 将一组历史消息压缩为一段摘要。
     */
    private String summarize(List<Message> messages) throws Exception {
        List<Message> prompt = new ArrayList<>();
        prompt.add(Message.builder().role(Role.SYSTEM.getValue())
                .content("你是对话摘要助手。请将以下多轮对话压缩为一段不超过200字的中文摘要,保留关键事实、话题和结论,不要遗漏重要信息。")
                .build());
        prompt.addAll(messages);
        GenerationParam param = GenerationParam.builder()
                .model(properties.getModel())
                .messages(prompt)
                .build();
        GenerationResult result = new Generation().call(param);
        return result.getOutput().getText();
    }
}
