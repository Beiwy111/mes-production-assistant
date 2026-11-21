package com.upc.computer.assistant.v2.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.upc.computer.assistant.v2.agent.DifyWorkflowClient;
import com.upc.computer.assistant.v2.application.AssistantFacade;
import com.upc.computer.assistant.v2.runtime.AssistantRunRegistry;
import com.upc.computer.assistant.v2.security.AssistantSessionResolver;
import com.upc.computer.common.JwtUtil;
import com.upc.computer.service.AuthService;
import jakarta.servlet.AsyncEvent;
import jakarta.servlet.AsyncListener;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.AsyncHandlerInterceptor;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
import java.time.Clock;

@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(prefix = "assistant.v2", name = "enabled", havingValue = "true")
public class AssistantV2AccessConfiguration implements WebMvcConfigurer {
    private final AssistantV2Properties settings;
    public AssistantV2AccessConfiguration(AssistantV2Properties settings) { this.settings = settings; }
    @Bean public DifyWorkflowClient difyWorkflowClient(ObjectMapper mapper) { return new DifyWorkflowClient(settings, mapper); }
    @Bean public AssistantSessionResolver assistantSessionResolver(JwtUtil jwt, AuthService auth) { return new AssistantSessionResolver(jwt, auth); }
    @Bean public AssistantRunRegistry assistantRunRegistry() { return new AssistantRunRegistry(settings.getRuntime().getRetention(), Clock.systemUTC()); }
    @Bean public AssistantFacade assistantFacade(DifyWorkflowClient client, AssistantSessionResolver users, AssistantRunRegistry runs, ObjectMapper mapper) {
        return new AssistantFacade(client, users, runs, mapper);
    }

    @Override public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(new AsyncHandlerInterceptor() {
            @Override public void afterConcurrentHandlingStarted(HttpServletRequest request, HttpServletResponse response, Object handler) {
                if (!(request.getAttribute(AssistantFacade.RUN_ATTRIBUTE) instanceof AssistantRunRegistry.Run run)
                        || !request.isAsyncStarted()) return;
                var async = request.getAsyncContext();
                async.setTimeout(settings.getRuntime().getRunTimeout().plusSeconds(10).toMillis());
                async.addListener(new AsyncListener() {
                    @Override public void onComplete(AsyncEvent event) { run.cancel(); }
                    @Override public void onTimeout(AsyncEvent event) { run.cancel(); }
                    @Override public void onError(AsyncEvent event) { run.cancel(); }
                    @Override public void onStartAsync(AsyncEvent event) { }
                });
            }
        }).addPathPatterns("/assistant/interpret/stream", "/afterSales/assistant/interpret/stream");
    }
}
