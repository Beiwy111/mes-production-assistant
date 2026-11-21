package com.upc.computer.assistant.v2.orders;

import com.upc.computer.dto.*;
import com.upc.computer.entity.Material;
import com.upc.computer.service.*;
import com.upc.computer.common.BusinessException;
import org.springframework.stereotype.Component;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.*;

/** Called inside the draft confirmation transaction only. Reuses original business services. */
@Component
public class OrderBusinessWriter {
    private final CustomerPortalService customers;private final MesWorkflowService mes;
    public OrderBusinessWriter(CustomerPortalService customers,MesWorkflowService mes){this.customers=customers;this.mes=mes;}
    public Map<String,Object> create(LoginResponse session,Map<String,Object> f,Material product,String draftId) {
        validateBusinessRemark(f,draftId,"CUSTOMER".equals(session.getRoleCode()));
        Map<String,Object> result;
        String remark="助手订单草稿："+draftId+"；"+Objects.toString(f.get("remark"),"");
        if("CUSTOMER".equals(session.getRoleCode())) {
            CustomerCreateOrderRequest request=new CustomerCreateOrderRequest();request.setMaterialId(product.getMaterialId());request.setProductName(product.getMaterialName());request.setSpecification(product.getSpecification());
            request.setQuantity(new BigDecimal(f.get("quantity").toString()));request.setUnit(product.getUnit());request.setRequiredDeliveryDate(LocalDate.parse(f.get("requiredDeliveryDate").toString()));
            request.setReceiverName(f.get("receiverName").toString());request.setReceiverPhone(f.get("receiverPhone").toString());request.setReceiverAddress(f.get("receiverAddress").toString());request.setRemark(remark);
            result=new LinkedHashMap<>(customers.createOrder(session,request));
        } else {
            Map<String,Object> payload=new LinkedHashMap<>(f);payload.put("materialId",product.getMaterialId());payload.put("productModel",product.getMaterialName());payload.put("panelType",Objects.toString(product.getSpecification(),""));payload.put("deliveryDate",f.get("requiredDeliveryDate"));
            payload.put("remark",remark+"；收货："+f.get("receiverName")+" "+f.get("receiverPhone")+" "+f.get("receiverAddress"));
            MesActionRequest request=new MesActionRequest();request.setAction("createOrder");request.setOperator(session.getUsername());request.setRoleKey(session.getRoleCode().toLowerCase(Locale.ROOT));request.setPayload(payload);
            Object created=mes.execute(request);
            if(!(created instanceof Map<?,?> map)||map.get("id")==null)throw new IllegalStateException("Order creation returned no identifier");
            result=new LinkedHashMap<>();result.put("orderNo",map.get("id"));
        }
        result.put("status","PENDING");result.put("message","订单已提交，等待订单审核员审核");return result;
    }
    static void validateBusinessRemark(Map<String,Object> f,String id,boolean customer) {
        String remark="助手订单草稿："+id+"；"+Objects.toString(f.get("remark"),"");
        String recipient=Objects.toString(f.get("receiverName"),"")+" "+Objects.toString(f.get("receiverPhone"),"")+" "+Objects.toString(f.get("receiverAddress"),"");
        String composed=customer?"客户门户提交；收货："+recipient+"；"+remark:remark+"；收货："+recipient;
        if(composed.length()>500)throw new BusinessException(400,"收货信息和备注合计过长，超出现有订单备注500字限制，请缩短地址或备注后保存");
    }
}
