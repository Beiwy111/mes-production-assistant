package com.upc.computer.assistant.v2.chat;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Getter
@Setter
@Component
@ConfigurationProperties("mes.workbench-chat")
public class WorkbenchChatProperties {
    private final Endpoint api = new Endpoint();
    private final Endpoint local = new Endpoint();
    private String knowledgeKey = "";

    public Endpoint endpoint(ChatMode.Source source) {
        return source == ChatMode.Source.LOCAL ? local : api;
    }

    @Getter
    @Setter
    public static class Endpoint {
        private String baseUrl = "";
        private String model = "";
        private String apiKey = "";
    }
}
