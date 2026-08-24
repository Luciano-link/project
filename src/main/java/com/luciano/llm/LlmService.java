package com.luciano.llm;

import com.alibaba.dashscope.aigc.generation.Generation;
import com.alibaba.dashscope.aigc.generation.GenerationParam;
import com.alibaba.dashscope.aigc.generation.GenerationResult;
import com.alibaba.dashscope.common.Message;
import com.alibaba.dashscope.common.Role;
import com.alibaba.dashscope.tools.ToolCallBase;
import com.alibaba.dashscope.tools.ToolCallFunction;
import com.alibaba.dashscope.utils.Constants;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.luciano.config.LlmProperties;
import com.luciano.conversation.ConversationService;
import com.luciano.tool.ImageContext;
import com.luciano.tool.ToolRegistry;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 阿里云百炼(通义千问)文本生成服务。
 * 支持:
 * 1. 多轮对话上下文(滑动窗口 + 摘要压缩)
 * 2. Function Calling / Tool Use:LLM 可自主调用注册的工具,再基于工具结果生成最终回复
 */
@Service
public class LlmService {

    private static final Logger log = LoggerFactory.getLogger(LlmService.class);

    private static final String SYSTEM_PROMPT = "你是一个友好、乐于助人的微信机器人助手,回答要简洁准确,使用中文。"
            + "调用规则:查询天气/生成图片/发送邮件时调用对应工具;"
            + "需要最新资讯、新闻、实时数据时使用联网搜索;"
            + "普通聊天直接回答。多步任务请按顺序调用工具,后一步参数使用前一步的真实结果。";

    /** 触发摘要压缩的历史消息条数 */
    private static final int SUMMARY_TRIGGER = 40;

    /** 摘要压缩后保留的窗口消息条数 */
    private static final int WINDOW_AFTER_SUMMARY = 16;

    /** Function Calling 最大工具调用轮次,防止死循环 */
    private static final int MAX_TOOL_ROUNDS = 3;

    /** 摘要压缩线程池:异步生成摘要,不阻塞用户请求 */
    private final ExecutorService summaryExecutor = Executors.newFixedThreadPool(2);

    /** 工具并行执行线程池:同一轮多个工具调用并行跑,工具多时不串行排队 */
    private final ExecutorService toolExecutor = Executors.newFixedThreadPool(4);

    private final LlmProperties properties;
    private final ConversationService conversationService;
    private final ToolRegistry toolRegistry;

    public LlmService(LlmProperties properties,
                      ConversationService conversationService,
                      ToolRegistry toolRegistry) {
        this.properties = properties;
        this.conversationService = conversationService;
        this.toolRegistry = toolRegistry;
    }

    @PostConstruct
    public void init() {
        String apiKey = properties.getApiKey();
        if (apiKey == null || apiKey.isBlank()) {
            log.warn("未配置 llm.api-key,LLM 回复功能将不可用。请在 application-local.properties 中配置,或设置环境变量 LLM_API_KEY");
            return;
        }
        Constants.apiKey = apiKey;
        log.info("阿里云百炼初始化完成,模型 = {}, 已注册工具数 = {}", properties.getModel(), toolRegistry.getTools().size());
    }

    @PreDestroy
    public void shutdown() {
        summaryExecutor.shutdownNow();
        toolExecutor.shutdownNow();
    }

    /**
     * 生成带上下文和工具能力的回复。
     *
     * @param userId   用户标识(微信 from_user_id),用于隔离上下文与工具结果归属
     * @param userText 用户文本
     * @return 最终回复文本
     */
    public String chat(String userId, String userText) {
        return chatWithTrace(userId, userText).reply();
    }

    /** 对话结果:最终回复 + 工具调用轨迹 */
    public record ChatResult(String reply, List<ToolStep> steps) {
    }

    /** 一次工具调用记录 */
    public record ToolStep(String tool, String arguments, String result) {
    }

    /**
     * 带上下文的回复,返回最终文本与工具调用轨迹。
     */
    public ChatResult chatWithTrace(String userId, String userText) {
        return chatWithTrace(userId, userText, null);
    }

    /**
     * 带上下文的回复,可注入 RAG 检索到的知识增强回答。
     *
     * @param knowledge RAG 检索到的参考内容,可为 null(无知识增强)
     */
    public ChatResult chatWithTrace(String userId, String userText, String knowledge) {
        if (properties.getApiKey() == null || properties.getApiKey().isBlank()) {
            return new ChatResult("抱歉,我还没有配置大模型能力,请联系管理员配置 llm.api-key 后再试。", List.of());
        }
        List<ToolStep> steps = new ArrayList<>();
        try {
            compressIfNeeded(userId);

            List<Message> messages = buildMessages(userId, userText, knowledge);
            GenerationParam param = buildParam(messages);
            GenerationResult result = callGeneration(param);

            // Function Calling 循环:LLM 要求调用工具则执行并回填,最多 MAX_TOOL_ROUNDS 轮
            int toolRound = 0;
            while (hasToolCalls(result) && toolRound < MAX_TOOL_ROUNDS) {
                toolRound++;
                log.info("第 {} 轮工具调用,userId = {}", toolRound, userId);
                ImageContext.setCurrentUserId(userId);
                try {
                    appendAssistantToolCall(messages, result);
                    executeToolCalls(messages, result, steps);
                    result = callGeneration(buildParam(messages));
                } finally {
                    ImageContext.clear();
                }
            }

            String text = extractText(result);
            if (text == null || text.isBlank()) {
                log.warn("大模型返回为空,requestId = {}", result.getRequestId());
                return new ChatResult("抱歉,大模型没有返回内容,请稍后再试。", steps);
            }

            saveConversation(userId, userText, text);
            return new ChatResult(text, steps);
        } catch (Exception e) {
            log.error("调用阿里云百炼失败,userId = {}", userId, e);
            return new ChatResult("大模型调用失败,请稍后再试。", steps);
        }
    }

    /** 兼容无上下文/无工具调用 */
    public String chat(String userText) {
        return chat("__anonymous__", userText);
    }

    /** 组装带上下文的消息列表 */
    private List<Message> buildMessages(String userId, String userText, String knowledge) {
        List<Message> messages = new ArrayList<>();
        String summary = conversationService.getSummary(userId);
        if (summary != null && !summary.isBlank()) {
            messages.add(Message.builder().role(Role.SYSTEM.getValue())
                    .content("以下是更早对话的摘要,供参考:\n" + summary).build());
        }
        messages.add(Message.builder().role(Role.SYSTEM.getValue()).content(SYSTEM_PROMPT).build());
        messages.addAll(conversationService.getMessages(userId));
        if (knowledge != null && !knowledge.isBlank()) {
            messages.add(Message.builder().role(Role.SYSTEM.getValue())
                    .content("以下是知识库检索到的参考信息,请基于它回答用户问题,不要编造:\n" + knowledge).build());
        }
        messages.add(Message.builder().role(Role.USER.getValue()).content(userText).build());
        return messages;
    }

    /** 构造请求参数(搜索与工具并存,LLM 自主决定:天气等用工具,实时信息用搜索) */
    private GenerationParam buildParam(List<Message> messages) {
        GenerationParam.GenerationParamBuilder<?, ?> builder = GenerationParam.builder()
                .model(properties.getModel())
                .messages(messages)
                .enableSearch(properties.isSearchEnabled());
        if (!toolRegistry.getTools().isEmpty()) {
            builder.tools(toolRegistry.toSdkTools());
        }
        return builder.build();
    }

    private GenerationResult callGeneration(GenerationParam param) throws Exception {
        return new Generation().call(param);
    }

    /** 判断 LLM 响应是否包含工具调用 */
    private boolean hasToolCalls(GenerationResult result) {
        if (result.getOutput() == null || result.getOutput().getChoices() == null
                || result.getOutput().getChoices().isEmpty()) {
            return false;
        }
        Message msg = result.getOutput().getChoices().get(0).getMessage();
        return msg != null && msg.getToolCalls() != null && !msg.getToolCalls().isEmpty();
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

    /** 把 LLM 的助手消息(含 tool_calls)追加到对话中 */
    private void appendAssistantToolCall(List<Message> messages, GenerationResult result) {
        Message assistantMsg = result.getOutput().getChoices().get(0).getMessage();
        messages.add(Message.builder()
                .role(Role.ASSISTANT.getValue())
                .content(assistantMsg.getContent())
                .toolCalls(assistantMsg.getToolCalls())
                .build());
    }

    /** 执行所有工具调用(同一轮的工具并行执行,互不依赖时节省总耗时),并把结果以 tool 角色回填,同时记录调用轨迹 */
    private void executeToolCalls(List<Message> messages, GenerationResult result, List<ToolStep> steps) {
        Message assistantMsg = result.getOutput().getChoices().get(0).getMessage();
        // 并行线程不继承主线程的 InheritableThreadLocal,需显式传递 userId 给工具(生图结果归属等)
        String userId = ImageContext.getCurrentUserId();
        List<CompletableFuture<ToolResult>> futures = new ArrayList<>();
        List<ToolCallFunction> calls = new ArrayList<>();
        for (ToolCallBase callBase : assistantMsg.getToolCalls()) {
            if (!(callBase instanceof ToolCallFunction call)) {
                continue;
            }
            calls.add(call);
            futures.add(CompletableFuture.supplyAsync(() -> runTool(call, userId), toolExecutor));
        }
        CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();
        for (int i = 0; i < calls.size(); i++) {
            ToolResult tr = futures.get(i).join();
            steps.add(new ToolStep(tr.name(), tr.arguments(), tr.result()));
            messages.add(Message.builder()
                    .role("tool")
                    .name(tr.name())
                    .toolCallId(tr.callId())
                    .content(tr.result() == null ? "工具执行成功" : tr.result())
                    .build());
        }
    }

    /** 执行单个工具调用(在 toolExecutor 线程运行) */
    private ToolResult runTool(ToolCallFunction call, String userId) {
        String name = call.getFunction().getName();
        String arguments = call.getFunction().getArguments();
        log.info("执行工具调用: name={}, arguments={}", name, arguments);
        JsonObject argObj;
        try {
            argObj = JsonParser.parseString(arguments).getAsJsonObject();
        } catch (Exception e) {
            log.warn("工具参数解析失败,按空对象处理: {}", arguments, e);
            argObj = new JsonObject();
        }
        ImageContext.setCurrentUserId(userId);
        try {
            String toolResult = toolRegistry.execute(name, argObj);
            return new ToolResult(call.getId(), name, arguments, toolResult);
        } finally {
            ImageContext.clear();
        }
    }

    /** 一次工具调用的执行结果 */
    private record ToolResult(String callId, String name, String arguments, String result) {
    }

    /** 保存本轮对话到上下文 */
    private void saveConversation(String userId, String userText, String text) {
        conversationService.addMessage(userId, Message.builder().role(Role.USER.getValue()).content(userText).build());
        conversationService.addMessage(userId, Message.builder().role(Role.ASSISTANT.getValue()).content(text).build());
    }

    /** 上下文超长时压缩(异步:摘要生成在线程池执行,不阻塞用户请求) */
    private void compressIfNeeded(String userId) {
        if (conversationService.size(userId) <= SUMMARY_TRIGGER) {
            return;
        }
        List<Message> oldest = conversationService.trim(userId, WINDOW_AFTER_SUMMARY);
        if (oldest.isEmpty()) {
            return;
        }
        log.info("触发异步摘要压缩,userId = {}, 丢弃 {} 条消息", userId, oldest.size());
        summaryExecutor.execute(() -> {
            try {
                String summary = summarize(oldest);
                if (summary == null || summary.isBlank()) {
                    log.warn("异步摘要压缩失败,丢弃最旧消息,userId = {}", userId);
                    return;
                }
                String old = conversationService.getSummary(userId);
                conversationService.setSummary(userId, old == null ? summary : old + "\n" + summary);
                log.info("异步摘要完成,userId = {}", userId);
            } catch (Exception e) {
                log.error("异步摘要失败,userId = {}", userId, e);
            }
        });
    }

    private String summarize(List<Message> messages) throws Exception {
        List<Message> prompt = new ArrayList<>();
        prompt.add(Message.builder().role(Role.SYSTEM.getValue())
                .content("你是对话摘要助手。请将以下多轮对话压缩为一段不超过200字的中文摘要,保留关键事实、话题和结论。")
                .build());
        prompt.addAll(messages);
        GenerationParam param = GenerationParam.builder()
                .model(properties.getModel())
                .messages(prompt)
                .build();
        GenerationResult result = new Generation().call(param);
        return extractText(result);
    }
}
