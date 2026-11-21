package com.upc.computer.assistant;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AssistantTextFormatterTest {

    @Test
    void convertsMarkdownHeadingsTablesAndEmphasisToPlainText() {
        String markdown = """
                ### 订单结论
                **订单最多的客户**是星辰俱乐部。

                | 客户 | 订单数 |
                |---|---:|
                | 星辰俱乐部 | 6 |
                - 建议优先跟进交期
                """;

        String text = AssistantTextFormatter.toPlainText(markdown);

        assertTrue(text.contains("订单结论"));
        assertTrue(text.contains("客户 | 订单数"));
        assertTrue(text.contains("星辰俱乐部 | 6"));
        assertTrue(text.contains("- 建议优先跟进交期"));
        assertFalse(text.contains("###"));
        assertFalse(text.contains("**"));
        assertFalse(text.contains("|---"));
    }

    @Test
    void streamingFormatterHandlesMarkdownMarkersSplitAcrossChunks() {
        StringBuilder emitted = new StringBuilder();
        AssistantTextFormatter.Stream stream = new AssistantTextFormatter.Stream(emitted::append);
        stream.accept("### 订单");
        stream.accept("结论\n**星辰");
        stream.accept("俱乐部**最多");

        String answer = stream.finish();

        assertEquals("订单结论\n星辰俱乐部最多", answer);
        assertEquals(answer, emitted.toString());
    }
}
