package com.luciano.skill;

/**
 * 技能接口。
 * Skill 与工具(Function Calling)的定位不同:工具由 LLM 语义判断是否调用,
 * Skill 由关键词/规则直接命中,命中即执行、不进 LLM,零成本秒回。
 */
public interface Skill {

    /** 技能名,用于日志 */
    String name();

    /** 是否命中该技能(关键词匹配) */
    boolean match(String text);

    /** 执行技能,返回回复文本 */
    String execute(String userId, String text);

    /** 匹配优先级,数值大优先(多个技能触发词可能重叠时用于控制顺序),默认 0 */
    default int priority() {
        return 0;
    }
}
