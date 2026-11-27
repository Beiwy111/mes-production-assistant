package com.upc.computer.assistant.v2.runtime;
import com.fasterxml.jackson.databind.*;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
class TaskToolStreamTest {
    final ObjectMapper json=new ObjectMapper();
    JsonNode chunk(Map<String,Object> delta,String finish){var choice=new HashMap<String,Object>();choice.put("delta",delta);if(finish!=null)choice.put("finish_reason",finish);return json.valueToTree(Map.of("choices",List.of(choice)));}
    @Test void nativeAnswerStreamsBeforeCompletion()throws Exception{
        var s=new TaskToolStream(json);
        assertThat(s.accept(chunk(Map.of("content","已查到 **"),null))).isEqualTo("已查到 **");
        assertThat(s.accept(chunk(Map.of("content","两张订单**。"),"stop"))).isEqualTo("两张订单**。");
        assertThat(s.finish("安排生产").path("reply").asText()).isEqualTo("已查到 **两张订单**。");
    }
    @Test void fragmentedNativeArgumentsNeverAppearInAnswer()throws Exception{
        var s=new TaskToolStream(json);
        assertThat(s.accept(chunk(Map.of("tool_calls",List.of(Map.of("index",0,"function",Map.of("name","preview","arguments","{\"actionCode\":\"saveProductionPlan\",")))),null))).isEmpty();
        assertThat(s.accept(chunk(Map.of("tool_calls",List.of(Map.of("index",0,"function",Map.of("arguments","\"fields\":{\"orderId\":\"CO123\"}}")))),"tool_calls"))).isEmpty();
        assertThat(s.finish("生产").path("calls").path(0).path("args").path("fields").path("orderId").asText()).isEqualTo("CO123");
    }
    @Test void truncationCannotBeTreatedAsACompletePlan()throws Exception{
        var s=new TaskToolStream(json);s.accept(chunk(Map.of("content","未完成"),"length"));
        assertThatThrownBy(()->s.finish("生产")).isInstanceOf(java.io.IOException.class);
    }
    @Test void responseDecisionCannotBeMixedWithUnexecutedTools()throws Exception{
        var mixed=json.valueToTree(Map.of("calls",List.of(Map.of("tool","respond","args",Map.of()),Map.of("tool","orders","args",Map.of()))));
        assertThatThrownBy(()->TaskToolStream.readyToRespond(mixed)).isInstanceOf(java.io.IOException.class);
        assertThat(TaskToolStream.readyToRespond(json.valueToTree(Map.of("calls",List.of(Map.of("tool","respond","args",Map.of())))))).isTrue();
        assertThat(TaskToolStream.readyToRespond(json.valueToTree(Map.of("calls",List.of(Map.of("tool","orders","args",Map.of())))))).isFalse();
    }
}
