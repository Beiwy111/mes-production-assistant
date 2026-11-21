package com.upc.computer.assistant.v2.config;

import com.upc.computer.assistant.v2.mapper.delivery.AiDeliveryReadMapper;
import com.upc.computer.assistant.v2.security.*;
import com.upc.computer.assistant.v2.tools.delivery.*;
import com.upc.computer.mapper.RoleMapper;
import com.upc.computer.service.AuthService;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.*;
import java.time.Clock;

@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(prefix = "assistant.v2", name = {"enabled", "tools.delivery.enabled"}, havingValue = "true")
@MapperScan(basePackageClasses = AiDeliveryReadMapper.class)
public class DeliveryToolConfiguration {
    @Bean public DeliveryEvidenceRegistry deliveryEvidenceRegistry() { return new DeliveryEvidenceRegistry(Clock.systemUTC()); }
    @Bean public DeliveryToolAccess deliveryToolAccess(AssistantSessionResolver sessions, AuthService auth, RoleMapper roles) { return new DeliveryToolAccess(sessions, auth, roles); }
    @Bean public AiDeliveryQueryService aiDeliveryQueryService(AiDeliveryReadMapper mapper, DeliveryEvidenceRegistry registry) { return new AiDeliveryQueryService(mapper, registry, Clock.systemUTC()); }
}
