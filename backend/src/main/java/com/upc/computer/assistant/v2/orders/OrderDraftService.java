package com.upc.computer.assistant.v2.orders;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.*;
import com.upc.computer.common.BusinessException;
import com.upc.computer.assistant.v2.chat.ChatMode;
import com.upc.computer.dto.LoginResponse;
import com.upc.computer.entity.Material;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import java.time.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import static com.upc.computer.assistant.v2.orders.OrderDraftRepository.Draft;

@Service
public class OrderDraftService {
    private final OrderDraftRepository repository;private final OrderProductCatalog catalog;
    private final OrderIntakeClient intake;private final OrderBusinessWriter writer;private final ObjectMapper json;
    private final TransactionTemplate tx;
    private final Clock clock;
    public OrderDraftService(OrderDraftRepository repository,OrderProductCatalog catalog,OrderIntakeClient intake,OrderBusinessWriter writer,ObjectMapper json,PlatformTransactionManager manager) {
        this.repository=repository;this.catalog=catalog;this.intake=intake;this.writer=writer;this.json=json;this.tx=new TransactionTemplate(manager);this.clock=Clock.system(ZoneId.of("Asia/Shanghai"));
    }
    public Map<String,Object> status(LoginResponse user){return Map.of("available",repository.ready(),"roles",List.of("ADMIN","ORDER","CUSTOMER"),"canQuery","ADMIN".equals(user.getRoleCode()),"message","下单流程需要确认后才提交业务订单");}

    public Map<String,Object> turn(LoginResponse user,String authorization,String sessionId,String requestId,String text) {
        return turn(user, authorization, sessionId, requestId, text, null);
    }

    public Map<String,Object> turn(LoginResponse user,String authorization,String sessionId,String requestId,String text,ChatMode mode) {
        identifier(sessionId);identifier(requestId);
        if(text==null||text.isBlank()||text.length()>2000)throw bad("问题须为1至2000个字符");
        Draft before=repository.bySession(user.getUserId(),sessionId);
        String hash=OrderProductCatalog.hash(mode == null ? text : mode.modelSource() + "\n" + mode.ragEnabled() + "\n" + text);
        if(before!=null) {
            owner(before,user);
            if(requestId.equals(before.lastRequest)) {sameHash(before,hash);return envelope(before,"已恢复本轮订单草稿。",steps("ORDER","恢复草稿","读取已保存的结果，没有重复执行"));}
        }
        Map<String,Object> context=new LinkedHashMap<>();context.put("currentDate",LocalDate.now(clock).toString());context.put("dateTimezone","Asia/Shanghai");
        context.put("draft",before==null?Map.of():Map.of("status",before.status,"fields",before.fields));
        context.put("customerIdentityLocked","CUSTOMER".equals(user.getRoleCode()));
        JsonNode parsed = mode == null ? intake.interpret(authorization, text, context) : intake.interpret(mode, text, context);
        String action=parsed.path("action").asText();
        if(!Set.of("ORDER","QUERY","CANCEL").contains(action))throw bad("未能识别需求，请说明要下单还是查询");
        if(action.equals("QUERY"))return Map.of("handled",false,"reply","","steps",List.of());
        if(before!=null&&Set.of("SUBMITTED","CANCELLED").contains(before.status))return envelope(before,"这份草稿已结束。如需再下一张订单，请点击新对话。",steps("ORDER","读取订单结果","未重复提交订单"));
        if(action.equals("CANCEL")) {
            if(before==null)return Map.of("handled",true,"reply","本对话没有待办理的订单草稿。","steps",steps("ORDER","检查草稿","未找到待办理草稿"));
            return cancel(user,before.id,before.version,requestId);
        }
        if(!parsed.path("fields").isObject()||!parsed.path("clearFields").isArray())throw bad("订单信息格式无效，请重新描述");
        Map<String,Object> patch=json.convertValue(parsed.path("fields"),new TypeReference<>(){});
        List<String> clear=new ArrayList<>();for(JsonNode value:parsed.path("clearFields")){if(!value.isTextual())throw bad("清除字段格式无效");clear.add(value.asText());}
        if(patch.containsKey("materialId"))throw bad("请从候选产品中选择型号");
        // Identity is derived from the account. A model cannot reassign a customer's order.
        if("CUSTOMER".equals(user.getRoleCode())){patch.remove("customerName");clear.remove("customerName");}
        return tx.execute(ignored->{
            String id=UUID.nameUUIDFromBytes((user.getUserId()+":"+sessionId).getBytes(StandardCharsets.UTF_8)).toString();
            if(before==null) {
                Draft fresh=new Draft();fresh.id=id;fresh.owner=user.getUserId();fresh.sessionId=sessionId;fresh.role=user.getRoleCode();fresh.expiresAt=LocalDateTime.now(clock).plusHours(24);
                if("CUSTOMER".equals(user.getRoleCode()))fresh.fields.put("customerName",boundCustomer(user));
                repository.insert(fresh);
            }else id=before.id;
            Draft d=ownedLock(user,id);editable(d);
            if(requestId.equals(d.lastRequest)){sameHash(d,hash);return envelope(d,"已恢复本轮草稿。",steps("ORDER","恢复草稿","没有重复处理"));}
            version(d,before==null?0:before.version);
            OrderDraftRules.merge(d.fields,patch,clear,"CUSTOMER".equals(user.getRoleCode()),LocalDate.now(clock));
            refreshCustomer(d,user);resolveExactProduct(d);
            update(d,requestId,hash);
            return envelope(d,prompt(d),List.of(step("SUP","识别下单需求","Dify总协调完成本轮意图判断"),step("ORDER","整理订单需求","Dify订单Agent提取本轮补充或修改的信息"),step("SERVICE","校验并保存草稿",d.status.equals("READY")?"业务服务完成产品及必填信息校验，等待你确认提交":"业务服务已保存草稿，还有信息需要补充，尚未创建订单")));
        });
    }

    public Map<String,Object> get(LoginResponse user,String id) {
        return tx.execute(ignored->{Draft d=ownedLock(user,id);return envelope(d,prompt(d),steps("ORDER","读取草稿","已从服务端读取最新版本"));});
    }
    public Map<String,Object> revise(LoginResponse user,String id,int version,String requestId,Map<String,Object> fields) {
        identifier(requestId);String hash=OrderProductCatalog.hash("revise:"+stable(fields));
        return tx.execute(ignored->{
            Draft d=ownedLock(user,id);editable(d);
            if(requestId.equals(d.lastRequest)){sameHash(d,hash);return envelope(d,prompt(d),steps("ORDER","恢复修改结果","没有重复修改"));}
            version(d,version);OrderDraftRules.merge(d.fields,fields,List.of(),"CUSTOMER".equals(user.getRoleCode()),LocalDate.now(clock));
            refreshCustomer(d,user);resolveExactProduct(d);update(d,requestId,hash);
            return envelope(d,prompt(d),steps("ORDER","校验并保存修改","旧确认版本已失效，请核对当前草稿"));
        });
    }
    public Map<String,Object> cancel(LoginResponse user,String id,int version,String requestId) {
        identifier(requestId);
        return tx.execute(ignored->{Draft d=ownedLock(user,id);
            if(d.status.equals("CANCELLED"))return envelope(d,"草稿已取消。",steps("ORDER","草稿已取消","没有创建业务订单"));
            editable(d);version(d,version);d.status="CANCELLED";d.version++;d.lastRequest=requestId;d.lastHash=OrderProductCatalog.hash("cancel");repository.save(d);
            return envelope(d,"已取消这份订单草稿，没有创建业务订单。",steps("ORDER","取消草稿","仅结束助手草稿，不删除业务订单"));});
    }
    public Map<String,Object> confirm(LoginResponse user,String id,int version,String requestId) {
        identifier(requestId);
        return tx.execute(ignored->{
            Draft d=ownedLock(user,id);
            // A committed result is authoritative even when the first response was lost.
            if(d.status.equals("SUBMITTED"))return envelope(d,"这份草稿已经提交成功，返回原订单结果。",steps("ORDER","恢复提交结果","未重复创建订单"));
            editable(d);version(d,version);
            Map<String,Object> checked=new LinkedHashMap<>();OrderDraftRules.merge(checked,d.fields,List.of(),false,LocalDate.now(clock));
            if(!OrderDraftRules.missing(checked).isEmpty())throw bad("订单信息尚未齐全，请先补充必填信息");
            if("CUSTOMER".equals(user.getRoleCode())&&!boundCustomer(user).equals(d.fields.get("customerName")))throw new BusinessException(409,"账号绑定客户已变化，请重新建立订单草稿");
            Material product=catalog.requireForUpdate(d.fields.get("materialId"));
            if(!d.fingerprint.equals(catalog.fingerprint(product)))throw new BusinessException(409,"产品信息在确认前发生变化，请重新选择产品并核对草稿");
            d.result=writer.create(user,d.fields,product,d.id);
            if(d.result==null||!d.result.containsKey("orderNo"))throw new IllegalStateException("Order writer did not return order number");
            d.status="SUBMITTED";d.version++;d.lastRequest=requestId;d.lastHash=OrderProductCatalog.hash("confirm:"+version);repository.save(d);
            return envelope(d,"订单已提交，等待订单审核员审核。",List.of(step("SERVICE","复核权限与草稿","确认当前用户、草稿版本、产品状态及必填信息"),step("SERVICE","创建业务订单","通过现有订单服务创建订单并保存提交结果"),step("SERVICE","返回办理结果","已取得真实订单编号；后续仍走原订单审核流程")));
        });
    }
    private void update(Draft d,String request,String hash){
        OrderBusinessWriter.validateBusinessRemark(d.fields,d.id,d.role.equals("CUSTOMER"));
        d.fingerprint=d.fields.containsKey("materialId")?catalog.fingerprint(catalog.require(d.fields.get("materialId"))):"";
        d.status=OrderDraftRules.missing(d.fields).isEmpty()?"READY":"COLLECTING";d.version++;d.lastRequest=request;d.lastHash=hash;d.expiresAt=LocalDateTime.now(clock).plusHours(24);repository.save(d);
    }
    private void resolveExactProduct(Draft d) {
        if(d.fields.containsKey("materialId")){catalog.require(d.fields.get("materialId"));return;}
        String q=Objects.toString(d.fields.get("productQuery"),"");if(q.isBlank())return;
        var exact=catalog.products().stream().filter(p->q.equalsIgnoreCase(p.get("materialCode").toString())||q.equalsIgnoreCase(p.get("materialName").toString())).toList();
        if(exact.size()==1)d.fields.put("materialId",exact.get(0).get("materialId").toString());
    }
    private void refreshCustomer(Draft d,LoginResponse user){if("CUSTOMER".equals(user.getRoleCode()))d.fields.put("customerName",boundCustomer(user));}
    private String boundCustomer(LoginResponse user){String name=user.getCustomerName();if(name==null||name.isBlank())throw bad("客户账号未绑定企业名称，请联系管理员");return name.trim();}
    private Draft ownedLock(LoginResponse user,String id){if(id==null||!id.matches("[a-f0-9-]{36}"))throw bad("草稿编号无效");Draft d=repository.lock(id,user.getUserId());if(d==null)throw new BusinessException(404,"草稿不存在或无权访问");owner(d,user);return d;}
    private void owner(Draft d,LoginResponse user){if(d.owner!=user.getUserId()||!d.role.equals(user.getRoleCode()))throw new BusinessException(403,"草稿归属或角色已变化，请重新开始");}
    private void editable(Draft d){if(!Set.of("COLLECTING","READY").contains(d.status))throw new BusinessException(409,"这份草稿已结束，请开始新对话");if(d.expiresAt.isBefore(LocalDateTime.now(clock)))throw new BusinessException(410,"草稿已过期，请开始新对话");}
    private void version(Draft d,int expected){if(d.version!=expected)throw new BusinessException(409,"草稿已被修改，请刷新并核对最新版本");}
    private void sameHash(Draft d,String hash){if(!hash.equals(d.lastHash))throw new BusinessException(409,"请求编号已被用于其他内容，请重新提交本轮修改");}
    private String prompt(Draft d){
        if(d.status.equals("SUBMITTED"))return "订单已提交，等待订单审核员审核。";
        if(d.status.equals("CANCELLED"))return "这份草稿已取消，如需下单请开始新对话。";
        if(d.expiresAt.isBefore(LocalDateTime.now(clock)))return "草稿已过期，请开始新对话。";
        var missing=OrderDraftRules.missing(d.fields);
        if(missing.isEmpty())return "订单信息已齐全。请核对下面的型号、数量、交期和收货信息，点击“确认提交”后才会创建订单。";
        String known=d.fields.containsKey("quantity")?"已记录数量"+d.fields.get("quantity")+"台。":"";
        return known+"请补充"+String.join("、",missing.stream().limit(2).map(m->m.get("label")).toList())+"。你可以直接回复，也可以在下方草稿中填写。";
    }
    private Map<String,Object> envelope(Draft d,String reply,List<Map<String,Object>> steps){
        Map<String,Object> fields=new LinkedHashMap<>(d.fields);List<Map<String,Object>> products=catalog.products();
        products.stream().filter(p->p.get("materialId").toString().equals(Objects.toString(fields.get("materialId"),""))).findFirst().ifPresent(p->{fields.put("productName",p.get("materialName"));fields.put("specification",p.get("specification"));fields.put("unit",p.get("unit"));});
        Map<String,Object> view=new LinkedHashMap<>();view.put("id",d.id);view.put("version",d.version);view.put("status",Set.of("COLLECTING","READY").contains(d.status)&&d.expiresAt.isBefore(LocalDateTime.now(clock))?"EXPIRED":d.status);
        view.put("fields",fields);view.put("products",products);view.put("missing",OrderDraftRules.missing(d.fields));view.put("expiresAt",d.expiresAt.atZone(clock.getZone()).toOffsetDateTime().toString());view.put("customerIdentityLocked",d.role.equals("CUSTOMER"));
        view.put("priceNotice","金额沿用现有订单服务口径，提交后由订单审核员核定；此处不作正式销售报价或交期承诺。");if(!d.result.isEmpty())view.put("result",d.result);
        return Map.of("handled",true,"reply",reply,"orderDraft",view,"steps",steps);
    }
    private Map<String,Object> step(String role,String label,String detail){return Map.of("agentId",role,"label",label,"status","COMPLETED","detail",detail);}
    private List<Map<String,Object>> steps(String role,String label,String detail){return List.of(step("SERVICE",label,detail));}
    private String stable(Map<String,Object> value){try{return json.writeValueAsString(new TreeMap<>(value));}catch(Exception e){throw bad("字段格式错误");}}
    private void identifier(String value){if(value==null||!value.matches("[A-Za-z0-9_-]{1,80}"))throw bad("会话或请求编号无效");}
    private BusinessException bad(String message){return new BusinessException(400,message);}
}
