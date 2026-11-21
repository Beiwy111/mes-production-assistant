package com.upc.computer.assistant.v2.tools.graphexport;
import com.upc.computer.assistant.v2.config.GraphExportConfiguration;
import com.upc.computer.assistant.v2.mapper.graphexport.GraphExportMapper;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class GraphExportConfigurationTest {
    @Test void defaultOffAndAssistantFlagDoNotRegisterExporterOrMapper() {
        for (String[] flags : new String[][] {{}, {"assistant.v2.enabled=true"}, {"assistant.v2.graph-export.enabled=false"}})
            new ApplicationContextRunner().withUserConfiguration(GraphExportConfiguration.class).withPropertyValues(flags).run(ctx -> {
                assertThat(ctx).hasNotFailed(); assertThat(ctx).doesNotHaveBean(GraphExportService.class); assertThat(ctx).doesNotHaveBean(GraphExportMapper.class);
            });
    }
    @Test void independentGateValidatesCredentialAndScopeWithoutConnectingToDatabase() {
        var source = mock(javax.sql.DataSource.class);
        var configuration = new org.apache.ibatis.session.Configuration();
        configuration.setEnvironment(new org.apache.ibatis.mapping.Environment("test", new org.apache.ibatis.transaction.jdbc.JdbcTransactionFactory(), source));
        new ApplicationContextRunner().withUserConfiguration(GraphExportConfiguration.class)
            .withPropertyValues("assistant.v2.enabled=false", "assistant.v2.graph-export.enabled=true", "assistant.v2.graph-export.bearer-key=" + GraphExportAccessTest.KEY, "assistant.v2.graph-export.allowed-order-ids=1")
            .withBean(org.apache.ibatis.session.SqlSessionFactory.class, () -> new org.apache.ibatis.session.defaults.DefaultSqlSessionFactory(configuration))
            .run(ctx -> { assertThat(ctx).hasNotFailed(); assertThat(ctx).hasSingleBean(GraphExportService.class); assertThat(ctx).hasSingleBean(GraphExportMapper.class);
                assertThat(configuration.getMapperRegistry().getMappers()).containsExactly(GraphExportMapper.class); });
        verifyNoInteractions(source);
    }
    @Test void enablingWithoutMaintenanceSecretFailsClosed() {
        new ApplicationContextRunner().withUserConfiguration(GraphExportConfiguration.class).withPropertyValues("assistant.v2.graph-export.enabled=true")
            .withBean(GraphExportMapper.class, () -> mock(GraphExportMapper.class))
            .run(ctx -> assertThat(ctx).hasFailed());
    }
}
