package com.upc.computer.assistant.v2.orders;

import com.upc.computer.common.BusinessException;
import org.junit.jupiter.api.Test;
import java.time.LocalDate;
import java.util.*;
import static org.assertj.core.api.Assertions.*;

class OrderDraftRulesTest {
    @Test void hundredIsQuantityAndDoesNotMakeAnOrderReady() {
        Map<String,Object> fields=new LinkedHashMap<>();
        OrderDraftRules.merge(fields,Map.of("quantity","100"),List.of(),false,LocalDate.of(2026,11,14));
        assertThat(fields.get("quantity")).isEqualTo("100");
        assertThat(OrderDraftRules.missing(fields)).extracting(x->x.get("field"))
                .contains("materialId","requiredDeliveryDate","receiverAddress","customerName").doesNotContain("quantity");
    }
    @Test void rejectInvalidQuantityDateAndIdentityFields() {
        for (Map<String,Object> patch : List.<Map<String,Object>>of(Map.of("quantity","0"),Map.of("quantity","1.5"),Map.of("requiredDeliveryDate","昨天"),Map.of("requiredDeliveryDate","2026-11-13"),Map.of("ownerUserId",22))) {
            assertThatThrownBy(()->OrderDraftRules.merge(new LinkedHashMap<>(),patch,List.of(),false,LocalDate.of(2026,11,14))).isInstanceOf(BusinessException.class);
        }
    }
    @Test void customerCannotChangeBoundIdentityAndChangingModelClearsSelection() {
        Map<String,Object> fields=new LinkedHashMap<>(Map.of("customerName","甲公司","materialId",7L,"productQuery","旧型号"));
        assertThatThrownBy(()->OrderDraftRules.merge(fields,Map.of("customerName","乙公司"),List.of(),true,LocalDate.now())).isInstanceOf(BusinessException.class);
        OrderDraftRules.merge(fields,Map.of("productQuery","新型号"),List.of(),true,LocalDate.now());
        assertThat(fields).doesNotContainKey("materialId").containsEntry("customerName","甲公司");
    }
    @Test void explicitReturnToOriginalQueryClearsManuallySelectedDifferentProduct() {
        Map<String,Object> fields=new LinkedHashMap<>(Map.of("productQuery","型号A","materialId","9"));
        OrderDraftRules.merge(fields,Map.of("productQuery","型号A"),List.of(),false,LocalDate.now());
        assertThat(fields).doesNotContainKey("materialId");
    }
}
