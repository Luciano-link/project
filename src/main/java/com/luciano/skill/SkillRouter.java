package com.luciano.skill;

import org.springframework.stereotype.Component;

import java.util.Comparator;
import java.util.List;

/**
 * 技能路由:按优先级降序匹配第一个命中的技能。
 * Spring 会自动收集所有 Skill 实现注入进来,按 priority 排序(高优先先匹配)。
 */
@Component
public class SkillRouter {

    private final List<Skill> skills;

    public SkillRouter(List<Skill> skills) {
        this.skills = skills.stream()
                .sorted(Comparator.comparingInt(Skill::priority).reversed())
                .toList();
    }

    /** 匹配命中的技能,未命中返回 null */
    public Skill match(String text) {
        for (Skill skill : skills) {
            if (skill.match(text)) {
                return skill;
            }
        }
        return null;
    }
}
