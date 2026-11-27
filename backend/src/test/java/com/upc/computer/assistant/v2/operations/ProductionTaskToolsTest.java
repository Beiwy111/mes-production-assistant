package com.upc.computer.assistant.v2.operations;
import com.upc.computer.ai.action.AgentActionCatalog;
import com.upc.computer.dto.LoginResponse;
import com.upc.computer.mapper.*;
import com.upc.computer.service.*;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
class ProductionTaskToolsTest {
    private final CustomerOrderMapper orders=mock(CustomerOrderMapper.class);
    private final ProductionTaskTools tools=new ProductionTaskTools(new AgentActionCatalog(),orders,mock(ProductionPlanMapper.class),mock(EquipmentMapper.class),mock(MesPlannerSchedulingService.class),mock(MesDispatchRecommendService.class));
    private LoginResponse user(String role){var u=new LoginResponse();u.setUserId(1L);u.setRoleCode(role);return u;}
    @Test void rolesReceiveOnlyTheirAllowedToolsAndCustomerCannotReadProductionOrders(){
        assertThat(tools.catalog(user("PLANNER"))).extracting(t->t.get("tool")).contains("orders","plans","planContext","preview");
        assertThat(tools.catalog(user("MANAGER"))).extracting(t->t.get("tool")).contains("plans","resources","preview").doesNotContain("orders","orderContext");
        assertThat(tools.catalog(user("CUSTOMER"))).isEmpty();
        assertThatThrownBy(()->tools.read("orders",Map.of(),user("CUSTOMER"))).hasMessageContaining("无权");
        verifyNoInteractions(orders);
    }
    @Test void extraArgsAndArbitrarySqlAreRejectedBeforeMapperAccess(){
        assertThatThrownBy(()->tools.read("orders",Map.of("sql","select * from users"),user("ADMIN"))).hasMessageContaining("参数");
        assertThatThrownBy(()->tools.read("executeSql",Map.of(),user("ADMIN"))).hasMessageContaining("白名单");
        verifyNoInteractions(orders);
    }
}
