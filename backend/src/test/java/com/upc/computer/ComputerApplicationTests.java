package com.upc.computer;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest
@org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable(named = "MES_LIVE_INTEGRATION", matches = "1")
class ComputerApplicationTests {

    @Test
    void contextLoads() {
    }

}
