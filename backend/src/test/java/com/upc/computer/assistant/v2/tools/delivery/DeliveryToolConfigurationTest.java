package com.upc.computer.assistant.v2.tools.delivery;

import com.upc.computer.assistant.v2.config.DeliveryToolConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import static org.assertj.core.api.Assertions.assertThat;

class DeliveryToolConfigurationTest {
    @Test void bothFlagsRegisterOnlyDedicatedSelectMapperWithoutConnectingToDatabase() {
        var configuration = new org.apache.ibatis.session.Configuration();
        configuration.setEnvironment(new org.apache.ibatis.mapping.Environment("test", new org.apache.ibatis.transaction.jdbc.JdbcTransactionFactory(), org.mockito.Mockito.mock(javax.sql.DataSource.class)));
        new ApplicationContextRunner().withUserConfiguration(DeliveryToolConfiguration.class)
                .withPropertyValues("assistant.v2.enabled=true", "assistant.v2.tools.delivery.enabled=true")
                .withBean(com.upc.computer.assistant.v2.security.AssistantSessionResolver.class, () -> org.mockito.Mockito.mock(com.upc.computer.assistant.v2.security.AssistantSessionResolver.class))
                .withBean(com.upc.computer.service.AuthService.class, () -> org.mockito.Mockito.mock(com.upc.computer.service.AuthService.class))
                .withBean(com.upc.computer.mapper.RoleMapper.class, () -> org.mockito.Mockito.mock(com.upc.computer.mapper.RoleMapper.class))
                .withBean(org.apache.ibatis.session.SqlSessionFactory.class, () -> new org.apache.ibatis.session.defaults.DefaultSqlSessionFactory(configuration))
                .run(ctx -> {
                    assertThat(ctx).hasNotFailed(); assertThat(ctx).hasSingleBean(AiDeliveryQueryService.class);
                    assertThat(ctx).hasSingleBean(com.upc.computer.assistant.v2.mapper.delivery.AiDeliveryReadMapper.class);
                    assertThat(configuration.getMapperRegistry().getMappers()).containsExactly(com.upc.computer.assistant.v2.mapper.delivery.AiDeliveryReadMapper.class);
                });
    }
    @Test void eitherDisabledFlagLeavesToolsAndMapperAbsentWithoutDatabase() {
        for (String[] flags : new String[][] {{}, {"assistant.v2.enabled=true"}, {"assistant.v2.tools.delivery.enabled=true"}}) {
            new ApplicationContextRunner().withUserConfiguration(DeliveryToolConfiguration.class).withPropertyValues(flags).run(ctx -> {
                assertThat(ctx).hasNotFailed(); assertThat(ctx).doesNotHaveBean(AiDeliveryQueryService.class);
                assertThat(ctx).doesNotHaveBean("aiDeliveryReadMapper");
            });
        }
    }
}
