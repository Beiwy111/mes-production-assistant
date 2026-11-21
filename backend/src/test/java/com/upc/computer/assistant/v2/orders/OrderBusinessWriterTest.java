package com.upc.computer.assistant.v2.orders;
import com.upc.computer.dto.*;
import com.upc.computer.entity.Material;
import com.upc.computer.service.*;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import static org.assertj.core.api.Assertions.*;

class OrderBusinessWriterTest {
    @Test void combinedReceiptAndRemarkMustFitOriginalSchema() {
        assertThatThrownBy(()->OrderBusinessWriter.validateBusinessRemark(Map.of("receiverAddress","地".repeat(400),"remark","注".repeat(100)),UUID.randomUUID().toString(),true)).hasMessageContaining("500字");
    }
    @Test void adminUsesMesWithExactProductAndCustomerUsesOwnPortal() {
        CustomerPortalService customers=mock(CustomerPortalService.class);MesWorkflowService mes=mock(MesWorkflowService.class);
        when(mes.execute(any())).thenReturn(Map.of("id","CO-TEST"));when(customers.createOrder(any(),any())).thenReturn(Map.of("orderNo","CUSTOMER-TEST"));
        OrderBusinessWriter writer=new OrderBusinessWriter(customers,mes);Material product=new Material();product.setMaterialId(99L);product.setMaterialName("指定型号");product.setUnit("台");product.setSpecification("27寸");
        LoginResponse user=new LoginResponse();user.setUsername("admin");user.setRoleCode("ADMIN");
        Map<String,Object> fields=Map.of("quantity","100","requiredDeliveryDate","2099-01-01","customerName","公司","receiverName","张","receiverPhone","13800138000","receiverAddress","详细地址");
        writer.create(user,fields,product,"draft-id");
        verify(mes).execute(argThat(r->r.getAction().equals("createOrder")&&r.getPayload().get("materialId").equals(99L)&&r.getOperator().equals("admin")));
        verifyNoInteractions(customers);user.setRoleCode("CUSTOMER");writer.create(user,fields,product,"other-draft");
        verify(customers).createOrder(eq(user),argThat(r->r.getMaterialId().equals(99L)&&r.getReceiverAddress().equals("详细地址")));
    }
}
