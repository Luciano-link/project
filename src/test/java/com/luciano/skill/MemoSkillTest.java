package com.luciano.skill;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MemoSkillTest {

    private MemoSkill memoSkill;

    @BeforeEach
    void setUp() {
        memoSkill = new MemoSkill();
    }

    @Test
    void match_travelPackingList_doesNotTriggerMemo() {
        assertFalse(memoSkill.match("帮我做北京三日游,列一下打包清单"));
    }

    @Test
    void match_travelWithRemember_stillTriggersAdd() {
        assertTrue(memoSkill.match("旅行前记一下带护照"));
    }

    @Test
    void match_myTodo_stillTriggersQuery() {
        assertTrue(memoSkill.match("我的待办"));
    }

    @Test
    void match_plainRemember_triggersAdd() {
        assertTrue(memoSkill.match("记一下明天开会"));
    }
}
