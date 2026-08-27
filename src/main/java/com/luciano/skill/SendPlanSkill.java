package com.luciano.skill;

import com.luciano.agent.TaskState;
import com.luciano.agent.TaskStateManager;
import com.luciano.mail.MailService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 方案发送技能。
 * 用户说"把方案发到 xxx@qq.com"时,把最近一次 Agent 规划生成的最终方案通过邮件发出。
 * 方案来源:TaskState 中的最终方案(需先完成一次规划)。
 */
@Component
public class SendPlanSkill implements Skill {

    private static final Logger log = LoggerFactory.getLogger(SendPlanSkill.class);

    private static final Pattern EMAIL = Pattern.compile("[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}");

    private final TaskStateManager taskManager;
    private final MailService mailService;

    public SendPlanSkill(TaskStateManager taskManager, MailService mailService) {
        this.taskManager = taskManager;
        this.mailService = mailService;
    }

    @Override
    public String name() {
        return "send_plan";
    }

    @Override
    public boolean match(String text) {
        // 只在"发方案/规划/攻略"语境命中,避免拦截普通"发邮件给xxx"这类自定义邮件请求
        boolean planContext = text.contains("发方案") || text.contains("把方案")
                || text.contains("方案发") || text.contains("规划发");
        boolean mailIntent = text.contains("发邮箱") || text.contains("发到") || text.contains("发我")
                || text.contains("发邮件");
        return (planContext || (mailIntent && (text.contains("方案") || text.contains("规划") || text.contains("攻略"))))
                && EMAIL.matcher(text).find();
    }

    @Override
    public String execute(String userId, String text) {
        Matcher matcher = EMAIL.matcher(text);
        String email = matcher.find() ? matcher.group() : null;
        if (email == null) {
            return "请提供收件邮箱,如:把方案发到 example@qq.com";
        }
        TaskState state = taskManager.get(userId);
        String plan = state == null ? null : state.getResult("final");
        if (plan == null || plan.isBlank()) {
            return "还没有可发送的方案,请先让我规划一次(如:帮我制定上海三日游规划)。";
        }
        String err = mailService.sendText(email, "出行规划方案", plan);
        log.info("方案发送结果: to = {}, err = {}", email, err);
        return err == null ? "方案已发送到 " + email + ",请注意查收。" : "发送失败:" + err;
    }
}
