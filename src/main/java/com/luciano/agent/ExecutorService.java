package com.luciano.agent;

import com.luciano.llm.LlmService;
import com.luciano.rag.RagService;
import com.luciano.weather.WeatherService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * 执行器:按规划器拆出的结构化子任务逐步执行(toolHint 分派)。
 * - get_weather → 天气服务(确定性)
 * - search → RAG 检索结果(确定性)
 * - 其他 → LLM 独立生成(带 knowledge + 前步结果)
 * 全部完成后由汇总器 synthesize 合成一份完整方案,写入 results.final。
 */
@Component
public class ExecutorService {

    private static final Logger log = LoggerFactory.getLogger(ExecutorService.class);

    private static final String EXECUTOR_SYSTEM = "你是出行规划 Agent 的执行器,正在完成一个子任务。\n"
            + "规则:\n"
            + "1) 只完成当前子任务,输出简洁、可引用的中文结果;\n"
            + "2) 景点/餐厅必须对应用户目标城市,禁止套用其他城市;\n"
            + "3) 无订房/订票接口时不要声称已预订成功;\n"
            + "4) 结合用户画像定制。";

    private static final String SYNTHESIZER_SYSTEM = "你是出行规划 Agent 的汇总器。根据各子任务执行结果,"
            + "输出一份完整、结构化、可直接使用的出行方案成品。\n"
            + "【格式要求】用清晰标题分章输出:一、行程概览;二、天气与穿衣;三、交通建议;四、住宿建议;"
            + "五、逐日行程(具体到时间段);六、餐饮推荐;七、预算明细;八、注意事项与打包清单。\n"
            + "【行程合理性】每日行程具体到时间段,【留足用餐时间(每餐至少1小时)和景点游玩时间(每处至少2小时)】,行程现实合理。\n"
            + "【禁止】不要输出营销式引导语(如\"如需我为你...\"\"请随时告诉我\"),不要 emoji 堆砌,不要空泛承诺;"
            + "直接给出可执行的具体信息(餐厅、酒店、时间、预算数字)。\n"
            + "城市信息必须与用户目标一致。总字数控制在1200字以内。";

    private final LlmService llmService;
    private final WeatherService weatherService;
    private final RagService ragService;
    private final TaskStateManager taskManager;

    public ExecutorService(LlmService llmService, WeatherService weatherService,
                           RagService ragService, TaskStateManager taskManager) {
        this.llmService = llmService;
        this.weatherService = weatherService;
        this.ragService = ragService;
        this.taskManager = taskManager;
    }

    /** 执行当前任务的全部子任务,返回最终方案文本;无任务或阶段不对返回 null */
    public String execute(String userId) {
        return execute(userId, null);
    }

    /**
     * 执行当前任务的全部子任务,返回最终方案文本。
     *
     * @param progress 步骤进度回调(可为 null),每完成一步通知用户,避免长时间无反馈
     */
    public String execute(String userId, Consumer<String> progress) {
        TaskState state = taskManager.get(userId);
        if (state == null) {
            log.warn("无可执行任务,userId = {}", userId);
            return null;
        }
        if (state.getPhase() == TaskState.Phase.DONE) {
            return state.getResult("final");
        }
        if (state.getPhase() != TaskState.Phase.EXECUTING) {
            log.warn("任务不在执行阶段,userId = {}, phase = {}", userId, state.getPhase());
            return null;
        }
        String city = extractCity(state.getGoal());
        state.setResult("city", city);
        state.setResult("days", extractDays(state.getGoal()));
        log.info("识别目的地: {} {}", city, state.getResult("days"));

        // 子任务兜底(规划失败时用默认三步)
        if (state.getSubtasks().isEmpty()) {
            state.setSubtasks(defaultSubtasks(city));
        }

        // 检索知识,供各子任务与汇总参考
        if (progress != null) {
            progress.accept("正在检索" + city + "景点/美食/住宿信息...");
        }
        String knowledge = retrieveKnowledge(city);
        state.setResult("knowledge", knowledge);
        if (progress != null) {
            progress.accept("✓ 信息检索完成");
        }

        // 逐子任务执行
        List<TaskState.StepResult> completed = new ArrayList<>();
        for (TaskState.SubTask sub : state.getSubtasks()) {
            if (progress != null) {
                progress.accept("正在执行:" + sub.title() + "...");
            }
            TaskState.StepResult stepResult = executeStep(state, sub, completed, knowledge);
            completed.add(stepResult);
            state.addStepResult(stepResult);
            if (progress != null) {
                progress.accept("✓ " + sub.title() + " 完成");
            }
        }

        // 最终方案:plan 子任务已生成(用汇总器 prompt);缺失时拼接所有结果兜底
        String finalDoc = state.getResult("final");
        if (finalDoc == null || finalDoc.isBlank()) {
            if (progress != null) {
                progress.accept("正在汇总生成完整方案...");
            }
            finalDoc = synthesize(state, completed, knowledge);
            state.setResult("final", finalDoc);
        }
        state.setPhase(TaskState.Phase.DONE);
        log.info("任务执行完成,userId = {}, 子任务数 = {}", userId, completed.size());
        return finalDoc;
    }

    /** 按 toolHint 分派单个子任务 */
    private TaskState.StepResult executeStep(TaskState state, TaskState.SubTask sub,
                                             List<TaskState.StepResult> previous, String knowledge) {
        String hint = sub.toolHint() == null ? "" : sub.toolHint();
        switch (hint) {
            case "get_weather" -> {
                return new TaskState.StepResult(sub.id(), sub.title(),
                        weatherService.getWeatherNow(state.getResult("city")));
            }
            case "search" -> {
                return new TaskState.StepResult(sub.id(), sub.title(),
                        knowledge == null ? "未检索到参考信息" : knowledge);
            }
            default -> {
                // plan 子任务用汇总器 prompt 生成完整方案(结合前步天气/检索结果与画像)
                String system = "plan".equals(hint) ? SYNTHESIZER_SYSTEM : EXECUTOR_SYSTEM;
                String userMessage = buildStepPrompt(state, sub, previous);
                String output = llmService.ask(system, userMessage, false);
                String text = output == null ? "本子任务未生成有效结果,请结合其他步骤综合判断。" : output;
                if ("plan".equals(hint)) {
                    state.setResult("final", text);
                }
                return new TaskState.StepResult(sub.id(), sub.title(), text);
            }
        }
    }

    private String buildStepPrompt(TaskState state, TaskState.SubTask sub, List<TaskState.StepResult> previous) {
        StringBuilder sb = new StringBuilder();
        sb.append("用户目标:").append(state.getGoal()).append('\n');
        sb.append("用户画像:").append(buildProfileText(state)).append('\n');
        sb.append("当前子任务 #").append(sub.id()).append(": ").append(sub.title()).append('\n');
        sb.append("子任务说明:").append(sub.description() == null ? "" : sub.description()).append('\n');
        if (!previous.isEmpty()) {
            sb.append("\n已完成步骤结果:\n");
            for (TaskState.StepResult r : previous) {
                sb.append("- [").append(r.id()).append("] ").append(r.title()).append(": ")
                        .append(truncate(r.output(), 400)).append('\n');
            }
        }
        sb.append("\n请完成当前子任务并输出结果。");
        return sb.toString();
    }

    /** 汇总器:把所有子任务结果交给 LLM 合成一份完整方案 */
    private String synthesize(TaskState state, List<TaskState.StepResult> steps, String knowledge) {
        StringBuilder sb = new StringBuilder("用户目标:").append(state.getGoal())
                .append("\n用户画像:").append(buildProfileText(state))
                .append("\n\n各子任务结果:\n");
        for (TaskState.StepResult step : steps) {
            sb.append("## ").append(step.title()).append('\n').append(step.output()).append("\n\n");
        }
        String raw = llmService.ask(SYNTHESIZER_SYSTEM, sb.toString(), false);
        return raw == null || raw.isBlank() ? sb.toString().trim() : raw.trim();
    }

    /** 检索目的地景点/美食/住宿知识(限制总长度,避免 Prompt 过大拖慢方案生成) */
    private String retrieveKnowledge(String city) {
        StringBuilder sb = new StringBuilder();
        appendIfPresent(sb, "景点", ragService.retrieve(city + "景点"));
        appendIfPresent(sb, "美食", ragService.retrieve(city + "美食"));
        appendIfPresent(sb, "住宿", ragService.retrieve(city + "住宿"));
        if (sb.length() == 0) {
            return "知识库未覆盖该城市,请基于常识规划";
        }
        return sb.length() > 2000 ? sb.substring(0, 2000) + "..." : sb.toString();
    }

    private List<TaskState.SubTask> defaultSubtasks(String city) {
        String c = city == null || city.isBlank() ? "目的地" : city;
        List<TaskState.SubTask> list = new ArrayList<>();
        list.add(new TaskState.SubTask(1, "查询" + c + "天气", "查询目的地实时天气", "get_weather"));
        list.add(new TaskState.SubTask(2, "检索" + c + "景点美食住宿", "检索当地参考信息", "search"));
        list.add(new TaskState.SubTask(3, "生成完整出行方案", "结合天气、参考知识与画像生成完整方案", "plan"));
        return list;
    }

    private void appendIfPresent(StringBuilder sb, String tag, String content) {
        if (content != null && !content.isBlank()) {
            sb.append("【").append(tag).append("】").append(content).append("\n");
        }
    }

    private String buildProfileText(TaskState state) {
        StringBuilder sb = new StringBuilder();
        state.getProfile().forEach((k, v) -> sb.append(k).append(": ").append(v).append("; "));
        return sb.toString().isEmpty() ? "未指定" : sb.toString();
    }

    private String truncate(String text, int max) {
        if (text == null) {
            return "";
        }
        return text.length() <= max ? text : text.substring(0, max) + "...";
    }

    /** 从用户目标中提取城市,未匹配到已知城市时默认上海 */
    private String extractCity(String goal) {
        if (goal != null) {
            for (String city : CITIES) {
                if (goal.contains(city)) {
                    return city;
                }
            }
        }
        return "上海";
    }

    /** 从用户目标中提取游玩天数,默认三日 */
    private String extractDays(String goal) {
        if (goal != null) {
            if (goal.contains("一日")) return "一日";
            if (goal.contains("两日") || goal.contains("二日")) return "两日";
            if (goal.contains("三日")) return "三日";
            if (goal.contains("四日")) return "四日";
            if (goal.contains("五日")) return "五日";
        }
        return "三日";
    }

    /** 支持检索的热门城市(知识库覆盖),其余城市靠 LLM 常识兜底 */
    private static final List<String> CITIES = List.of("上海", "北京", "广州", "成都", "西安", "杭州", "重庆");
}
