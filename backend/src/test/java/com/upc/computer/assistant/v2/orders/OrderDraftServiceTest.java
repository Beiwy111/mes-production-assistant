package com.upc.computer.assistant.v2.orders;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.upc.computer.common.BusinessException;
import com.upc.computer.dto.LoginResponse;
import com.upc.computer.entity.Material;
import org.junit.jupiter.api.*;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;
import java.time.*;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class OrderDraftServiceTest {
    OrderDraftRepository repository;OrderProductCatalog catalog;OrderIntakeClient intake;OrderBusinessWriter writer;OrderDraftService service;
    OrderDraftRepository.Draft draft;LoginResponse user;Material product;
    ObjectMapper json=new ObjectMapper();
    @BeforeEach void setup() {
        repository=mock(OrderDraftRepository.class);catalog=mock(OrderProductCatalog.class);intake=mock(OrderIntakeClient.class);writer=mock(OrderBusinessWriter.class);
        PlatformTransactionManager manager=mock(PlatformTransactionManager.class);when(manager.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
        service=new OrderDraftService(repository,catalog,intake,writer,json,manager);
        user=new LoginResponse();user.setUserId(5L);user.setUsername("operator");user.setRoleCode("ADMIN");
        draft=new OrderDraftRepository.Draft();draft.id="12345678-1234-1234-1234-123456789012";draft.sessionId="session";draft.owner=5;draft.role="ADMIN";draft.version=2;draft.expiresAt=LocalDateTime.now().plusHours(23);draft.fingerprint="product-v1";
        draft.fields.putAll(Map.of("materialId","7","quantity","100","requiredDeliveryDate",LocalDate.now().plusDays(10).toString(),"customerName","甲公司","receiverName","李先生","receiverPhone","13800138000","receiverAddress","测试市测试区一号"));
        draft.status="READY";
        product=new Material();product.setMaterialId(7L);product.setMaterialName("显示器A");
        when(repository.lock(draft.id,5L)).thenReturn(draft);when(catalog.require(any())).thenReturn(product);when(catalog.requireForUpdate(any())).thenReturn(product);when(catalog.fingerprint(product)).thenReturn("product-v1");
        when(catalog.products()).thenReturn(List.of(Map.of("materialId",7L,"materialCode","PRD-007","materialName","显示器A","specification","27寸","unit","台")));
        when(writer.create(any(),anyMap(),any(),anyString())).thenReturn(Map.of("orderNo","TEST-NOT-PERSISTED","status","PENDING"));
    }
    @Test void explicitConfirmationIsIdempotentAndUsesOriginalBusinessWriter() {
        Map<String,Object> first=service.confirm(user,draft.id,2,"confirm-1");
        service.confirm(user,draft.id,2,"confirm-retry");
        assertThat(((Map<?,?>)first.get("orderDraft")).containsKey("result")).isTrue();assertThat(draft.status).isEqualTo("SUBMITTED");
        verify(writer,times(1)).create(eq(user),anyMap(),eq(product),eq(draft.id));
    }
    @Test void staleVersionCannotConfirmOrModify() {
        assertThatThrownBy(()->service.confirm(user,draft.id,1,"old-confirm")).isInstanceOf(BusinessException.class).hasMessageContaining("版本");
        assertThatThrownBy(()->service.revise(user,draft.id,1,"old-edit",Map.of("quantity","200"))).hasMessageContaining("版本");
        verifyNoInteractions(writer);
    }
    @Test void changedProductOrMissingInformationCannotSubmit() {
        when(catalog.fingerprint(product)).thenReturn("changed");
        assertThatThrownBy(()->service.confirm(user,draft.id,2,"c1")).hasMessageContaining("产品信息");
        draft.fields.remove("receiverAddress");assertThatThrownBy(()->service.confirm(user,draft.id,2,"c2")).hasMessageContaining("尚未齐全");verifyNoInteractions(writer);
    }
    @Test void anotherOwnerRoleAndExpiredDraftCannotSubmit() {
        LoginResponse other=new LoginResponse();other.setUserId(6L);other.setRoleCode("ADMIN");
        assertThatThrownBy(()->service.confirm(other,draft.id,2,"c1")).hasMessageContaining("无权访问");
        user.setRoleCode("ORDER");assertThatThrownBy(()->service.confirm(user,draft.id,2,"c2")).hasMessageContaining("归属或角色");
        user.setRoleCode("ADMIN");draft.expiresAt=LocalDateTime.now().minusDays(2);assertThatThrownBy(()->service.confirm(user,draft.id,2,"c3")).hasMessageContaining("过期");verifyNoInteractions(writer);
    }
    @Test void correctionInvalidatesEarlierConfirmationAndCancelDoesNotWriteOrder() {
        service.revise(user,draft.id,2,"edit-1",Map.of("quantity","200"));assertThat(draft.version).isEqualTo(3);assertThat(draft.fields).containsEntry("quantity","200");
        assertThatThrownBy(()->service.confirm(user,draft.id,2,"old")).hasMessageContaining("版本");
        service.cancel(user,draft.id,3,"cancel");assertThat(draft.status).isEqualTo("CANCELLED");verifyNoInteractions(writer);
    }
    @Test void queryDoesNotConsumeDraftAndNaturalConfirmationDoesNotExecute() throws Exception {
        when(repository.bySession(5,"session")).thenReturn(draft);
        when(intake.interpret(anyString(),eq("先查库存"),anyMap())).thenReturn(json.readTree("{\"action\":\"QUERY\"}"));
        assertThat(service.turn(user,"Bearer test","session","q1","先查库存")).containsEntry("handled",false);
        when(intake.interpret(anyString(),eq("确认下单"),anyMap())).thenReturn(json.readTree("{\"action\":\"ORDER\",\"fields\":{},\"clearFields\":[],\"question\":\"\"}"));
        service.turn(user,"Bearer test","session","q2","确认下单");verifyNoInteractions(writer);assertThat(draft.status).isEqualTo("READY");
    }
    @Test void selectedModelIsUsedBeforeAnyDraftWrite() throws Exception {
        var mode = com.upc.computer.assistant.v2.chat.ChatMode.parse("LOCAL", true);
        when(intake.interpret(eq(mode), eq("查订单"), anyMap())).thenReturn(json.readTree("{\"action\":\"QUERY\"}"));
        assertThat(service.turn(user, "Bearer test", "session", "q-mode", "查订单", mode)).containsEntry("handled", false);
        verify(intake).interpret(eq(mode), eq("查订单"), anyMap());
        verify(intake, never()).interpret(anyString(), anyString(), anyMap());
        verifyNoInteractions(writer);
    }
    @Test void newOrderMergesQuantityThenProductWithoutSubmitting() throws Exception {
        when(intake.interpret(anyString(),anyString(),anyMap())).thenReturn(json.readTree("{\"action\":\"ORDER\",\"fields\":{\"quantity\":\"100\",\"productQuery\":\"显示器\"},\"clearFields\":[]}"));
        OrderDraftRepository.Draft fresh=new OrderDraftRepository.Draft();fresh.id=UUID.nameUUIDFromBytes("5:new-session".getBytes(java.nio.charset.StandardCharsets.UTF_8)).toString();fresh.owner=5;fresh.role="ADMIN";fresh.sessionId="new-session";fresh.expiresAt=LocalDateTime.now().plusHours(23);
        when(repository.lock(fresh.id,5)).thenReturn(fresh);
        var response=service.turn(user,"Bearer test","new-session","new1","我想下单100台显示器");
        assertThat(response.get("handled")).isEqualTo(true);assertThat(fresh.fields).containsEntry("quantity","100").doesNotContainKey("materialId");assertThat(fresh.status).isEqualTo("COLLECTING");verifyNoInteractions(writer);
    }
    @Test void customerCannotRebindCustomerThroughRevision() {
        user.setRoleCode("CUSTOMER");user.setCustomerName("甲公司");draft.role="CUSTOMER";
        assertThatThrownBy(()->service.revise(user,draft.id,2,"spoof",Map.of("customerName","乙公司"))).hasMessageContaining("不能代其他客户");verifyNoInteractions(writer);
    }
}
