package com.luciano.agent;

import org.springframework.stereotype.Service;

/**
 * 长任务启动前的轻量澄清:仅当完全无法推断城市/天数时追问。
 */
@Service
public class ClarifyService {

    private static final String[] CITY_HINTS = {
            "北京", "上海", "广州", "深圳", "杭州", "成都", "西安", "南京", "武汉", "重庆",
            "苏州", "厦门", "青岛", "大连", "三亚", "长沙", "昆明", "天津"
    };

    /**
     * @return 需要用户补充信息时返回追问文案;否则 null 表示可直接规划
     */
    public String needClarification(String goal) {
        if (goal == null || goal.isBlank()) {
            return "请告诉我你的出行目标,例如「帮我做一份上海三日游完整出行方案」。";
        }
        boolean hasCity = containsCity(goal);
        boolean hasDays = goal.contains("日游") || goal.contains("天") || goal.contains("夜");
        if (!hasCity && !hasDays) {
            return "为了给你一份完整方案,请补充:目的地城市 + 出行天数(例如「杭州三日游」)。";
        }
        if (!hasCity) {
            return "请告诉我目的地是哪个城市,我再为你规划完整出行方案。";
        }
        return null;
    }

    private boolean containsCity(String text) {
        for (String city : CITY_HINTS) {
            if (text.contains(city)) {
                return true;
            }
        }
        return false;
    }
}
