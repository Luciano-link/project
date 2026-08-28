package com.luciano.skill;

import com.luciano.agent.TaskState;
import com.luciano.agent.TaskStateManager;
import com.luciano.llm.PdfService;
import com.luciano.wechat.PdfResultStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * 方案 PDF 生成技能。
 * 用户回复"生成PDF/导出PDF/打印版"时,把最近一次 Agent 规划生成的完整方案
 * 生成 PDF 文件,存入待发送缓存,由消息路由发送给用户。
 */
@Component
public class SendPdfSkill implements Skill {

    private static final Logger log = LoggerFactory.getLogger(SendPdfSkill.class);

    private final TaskStateManager taskManager;
    private final PdfService pdfService;

    public SendPdfSkill(TaskStateManager taskManager, PdfService pdfService) {
        this.taskManager = taskManager;
        this.pdfService = pdfService;
    }

    @Override
    public String name() {
        return "send_pdf";
    }

    @Override
    public boolean match(String text) {
        return text.contains("生成PDF") || text.contains("导出PDF") || text.contains("生成pdf")
                || text.contains("导出pdf") || text.contains("打印版") || text.contains("pdf版本")
                || text.contains("PDF版本");
    }

    @Override
    public String execute(String userId, String text) {
        TaskState state = taskManager.get(userId);
        String plan = state == null ? null : state.getResult("final");
        if (plan == null || plan.isBlank()) {
            return "还没有可导出的方案,请先让我规划一次(如:帮我制定上海三日游规划)。";
        }
        byte[] pdf = pdfService.createTextPdf("出行方案", plan);
        if (pdf == null) {
            return "PDF 生成失败,请稍后再试。";
        }
        PdfResultStore.put(userId, pdf);
        log.info("PDF 已生成,待发送,userId = {}, {} bytes", userId, pdf.length);
        return "PDF 已生成,正在发送,请注意查收~";
    }
}
