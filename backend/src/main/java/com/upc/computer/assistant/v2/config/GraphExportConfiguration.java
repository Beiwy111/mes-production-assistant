package com.upc.computer.assistant.v2.config;

import com.upc.computer.assistant.v2.mapper.graphexport.GraphExportMapper;
import com.upc.computer.assistant.v2.security.GraphExportAccess;
import com.upc.computer.assistant.v2.tools.graphexport.GraphExportService;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import java.time.Clock;
import java.util.Arrays;
import java.util.stream.Collectors;

@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(prefix = "assistant.v2.graph-export", name = "enabled", havingValue = "true")
@MapperScan(basePackageClasses = GraphExportMapper.class)
@EnableTransactionManagement
public class GraphExportConfiguration {
    @Bean public GraphExportAccess graphExportAccess(
            @Value("${assistant.v2.graph-export.bearer-key:}") String key,
            @Value("${assistant.v2.graph-export.allowed-order-ids:}") String ids) {
        return new GraphExportAccess(key, Arrays.stream(ids.split(",")).map(String::trim).filter(v -> !v.isEmpty()).collect(Collectors.toSet()));
    }
    @Bean public GraphExportService graphExportService(GraphExportMapper mapper, GraphExportAccess access) {
        return new GraphExportService(mapper, Clock.systemUTC());
    }
}
