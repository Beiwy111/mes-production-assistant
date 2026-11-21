package com.upc.computer.assistant.v2;

import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.builder.annotation.MapperAnnotationBuilder;
import org.apache.ibatis.session.Configuration;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.assertj.core.api.Assertions.*;

class AiBusinessReadMapperTest {
    @Test void allDomainStatementsAreFixedBoundedSelectsWithBoundOrderIdAndNoPii() throws Exception {
        Class<?> type=Class.forName("com.upc.computer.assistant.v2.mapper.business.AiBusinessReadMapper");
        Configuration configuration=new Configuration();
        new MapperAnnotationBuilder(configuration,type).parse();
        int count=0;
        for(var method:type.getDeclaredMethods()) {
            if(method.isDefault() || method.isSynthetic()) continue;
            Select select=method.getAnnotation(Select.class);
            assertThat(select).as(method.getName()).isNotNull();
            String sql=String.join(" ",select.value()).toLowerCase(Locale.ROOT);
            assertThat(sql).startsWith("select ").contains("#{id}","order by ","limit 1001")
                .doesNotContain("${","select *","contact_phone","customer_name","inspector_id","maintainer_id","reported_by","cost_amount","problem_description");
            assertThat(configuration.getMappedStatement(type.getName()+"."+method.getName()).getBoundSql(1L).getParameterMappings()).isNotEmpty();
            count++;
        }
        assertThat(count).isGreaterThanOrEqualTo(12);
    }
    @Test void workOrderBoundaryRequiresConsistentOrderLinePlanAndMaterial() throws Exception {
        Class<?> type=Class.forName("com.upc.computer.assistant.v2.mapper.business.AiBusinessReadMapper");
        String sql=(String)type.getField("WORK_ORDERS").get(null);
        assertThat(sql).contains("source_order_id=#{id}","i.order_id=#{id}","w.material_id=i.material_id","pi.material_id=i.material_id","w.plan_id=pi.plan_id");
    }
    @Test void actualConsumptionDoesNotDependOnCurrentBomMembership() throws Exception {
        Class<?> type=Class.forName("com.upc.computer.assistant.v2.mapper.business.AiBusinessReadMapper");
        var method=Arrays.stream(type.getDeclaredMethods()).filter(m->m.getName().equals("consumption")).findFirst();
        assertThat(method).isPresent();
        String sql=String.join(" ",method.get().getAnnotation(Select.class).value());
        assertThat(sql).contains("related_work_order_id IN").doesNotContain("JOIN bom", "child_material_id");
    }
}
