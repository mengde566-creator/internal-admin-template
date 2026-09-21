package com.internaladmin.module.agent.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 入口可理解性门禁的判据真值表。
 *
 * 边界：本类只验证 {@link AgentConversationService#hasServiceableClue(String)} 这一条判据，
 * 不代替真实 HTTP/SSE 层面的场景测试；门禁的整体行为由场景用例覆盖。
 */
class AgentInputClueGateTest {

    @Test
    @DisplayName("汉字、含数字或含分隔符的词元都视为可识别线索")
    void recognizableClues() {
        assertTrue(AgentConversationService.hasServiceableClue("SCNPILOT-944cd481 还有多少"));
        assertTrue(AgentConversationService.hasServiceableClue("查询物品 ITEM-6204 的当前库存"));
        assertTrue(AgentConversationService.hasServiceableClue("6204"), "裸数字编码必须被视为线索");
        assertTrue(AgentConversationService.hasServiceableClue("A100"));
        assertTrue(AgentConversationService.hasServiceableClue("A-100/02"));
        assertTrue(AgentConversationService.hasServiceableClue("hello 6204"));
    }

    @Test
    @DisplayName("纯符号与纯字母乱码没有线索，不进入模型与工具链")
    void unintelligibleInputs() {
        assertFalse(AgentConversationService.hasServiceableClue("￥%……&*（）——+"));
        assertFalse(AgentConversationService.hasServiceableClue("asdkjfhalksdjf ？？？ zxcvbnm"));
    }

    @Test
    @DisplayName("空白输入交回既有入口校验，不在此处判定")
    void blankInputIsNotJudged() {
        assertTrue(AgentConversationService.hasServiceableClue(null));
        assertTrue(AgentConversationService.hasServiceableClue("   "));
    }

    @Test
    @DisplayName("已知限制：纯英文且不含数字与分隔符会被判为无线索")
    void pureEnglishIsAKnownLimitation() {
        assertFalse(AgentConversationService.hasServiceableClue("show me the stock"),
                "该行为是已记录的已知限制：用户会收到受控补充提示；改变它需要业务适配器声明各自线索规则");
    }
}
