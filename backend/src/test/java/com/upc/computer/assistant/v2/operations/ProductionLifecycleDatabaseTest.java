package com.upc.computer.assistant.v2.operations;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.upc.computer.ai.action.*;
import com.upc.computer.assistant.v2.orders.OrderSessionGuard;
import com.upc.computer.common.RedisUtil;
import com.upc.computer.dto.LoginResponse;
import com.upc.computer.entity.*;
import com.upc.computer.mapper.*;
import com.upc.computer.service.*;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.*;
import org.apache.ibatis.transaction.jdbc.JdbcTransactionFactory;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.util.ReflectionTestUtils;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Real MES writes in an isolated H2 database. Solver/resource inputs and Redis/audit are test fixtures. */
class ProductionLifecycleDatabaseTest {
    @Test void confirmPlanPublishSubmitAndDispatchPersistsRowsAndOperatorReceipt() { runLifecycle(false); }
    @Test void draftDispatchRequiresConfirmationAndDeliversToEachOperatorEvenAfterCacheLoss() { runLifecycle(true); }
    private void runLifecycle(boolean combined) {
        var ds=new JdbcDataSource();ds.setURL("jdbc:h2:mem:"+UUID.randomUUID()+";MODE=MySQL;DB_CLOSE_DELAY=-1");
        var db=new JdbcTemplate(ds);
        var config=new Configuration(new Environment("task-lifecycle",new JdbcTransactionFactory(),ds));
        config.setMapUnderscoreToCamelCase(true);
        Map<Class<?>,String> entities=Map.of(CustomerOrder.class,"orderId",CustomerOrderItem.class,"orderItemId",
            ProductionPlan.class,"planId",ProductionPlanItem.class,"planItemId",ProductionPlanSchedule.class,"scheduleId",
            ProductionPlanHistory.class,"historyId",WorkOrder.class,"workOrderId",DispatchTask.class,"dispatchId",ProcessStep.class,"stepId");
        entities.forEach((type,id)->table(db,type,id));
        List<Class<?>> mappers=List.of(CustomerOrderMapper.class,CustomerOrderItemMapper.class,ProductionPlanMapper.class,
            ProductionPlanItemMapper.class,ProductionPlanScheduleMapper.class,ProductionPlanHistoryMapper.class,
            WorkOrderMapper.class,DispatchTaskMapper.class,ProcessStepMapper.class);
        mappers.forEach(config::addMapper);
        try(var sql=new SqlSessionFactoryBuilder().build(config).openSession(true)){
            var orders=sql.getMapper(CustomerOrderMapper.class);var planMapper=sql.getMapper(ProductionPlanMapper.class);
            var items=sql.getMapper(CustomerOrderItemMapper.class);var dispatches=sql.getMapper(DispatchTaskMapper.class);
            var workOrders=sql.getMapper(WorkOrderMapper.class);
            var order=new CustomerOrder();order.setOrderNo("CO_TEST_1");order.setAuditStatus("PLAN_PENDING");
            order.setRequiredDeliveryDate(LocalDate.of(2027,3,30));orders.insertCustomerOrder(order);
            var item=new CustomerOrderItem();item.setOrderId(order.getOrderId());item.setMaterialId(7L);
            item.setProductName("隔离测试显示器");item.setQuantity(BigDecimal.TEN);items.insertOrderItem(item);
            var step=new ProcessStep();step.setStepId(5L);step.setStepName("主板装配");
            sql.getMapper(ProcessStepMapper.class).insertStep(step);
            var step2=new ProcessStep();step2.setStepName("电源板装配");sql.getMapper(ProcessStepMapper.class).insertStep(step2);
            var second=new User();second.setUserId(5L);second.setUsername("huang_operator");second.setRealName("黄师傅");
            var operator=new User();operator.setUserId(4L);operator.setUsername("li_operator");operator.setRealName("李师傅");
            var admin=new User();admin.setUserId(1L);admin.setUsername("admin");admin.setRealName("管理员");
            var users=mock(UserMapper.class);when(users.userList()).thenReturn(new ArrayList<>(List.of(operator,second,admin)));
            when(users.getUserByUsername("li_operator")).thenReturn(operator);when(users.getUserByUsername("admin")).thenReturn(admin);
            when(users.getUserById(4L)).thenReturn(operator);
            when(users.getUserByUsername("huang_operator")).thenReturn(second);when(users.getUserById(5L)).thenReturn(second);
            var scheduling=spy(new MesPlannerSchedulingService());var workflow=new MesWorkflowService();
            Map<String,Object> bindings=new LinkedHashMap<>();bindings.put("customerOrderMapper",orders);bindings.put("customerOrderItemMapper",items);
            bindings.put("productionPlanMapper",planMapper);bindings.put("productionPlanItemMapper",sql.getMapper(ProductionPlanItemMapper.class));bindings.put("userMapper",users);
            bindings.forEach((name,value)->{ReflectionTestUtils.setField(scheduling,name,value);ReflectionTestUtils.setField(workflow,name,value);});
            ReflectionTestUtils.setField(scheduling,"planScheduleMapper",sql.getMapper(ProductionPlanScheduleMapper.class));
            ReflectionTestUtils.setField(scheduling,"planHistoryMapper",sql.getMapper(ProductionPlanHistoryMapper.class));
            doReturn(Map.of("conflicts",List.of(),"canSubmit",true)).when(scheduling).validatePlan(anyMap());
            var schedule=Map.<String,Object>of("stepId",5,"stepName","主板装配","plannedQuantity",10,
                "plannedStart","2027-03-01 08:00:00","plannedEnd","2027-03-02 08:00:00","equipmentCode","EQ_TEST");
            doAnswer(i->Map.of("plannedQty",10,"schemes",List.of(Map.of("key", "COST","schedules",List.of(schedule),"finishDate","2027-03-02","canSubmit",true),
                Map.of("key","DELIVERY","schedules",List.of(schedule),"finishDate","2027-03-02","canSubmit",true))))
                .when(scheduling).compareSchemes(eq("CO_TEST_1"),any(),any(),eq(10));
            var runtime=mock(MesRuntimeStore.class);var runtimeState=new MesRuntimeStore.MesRuntimeState();
            when(runtime.load()).thenReturn(runtimeState);when(runtime.getExtra(eq(runtimeState),anyString())).thenCallRealMethod();
            var recommendation=mock(MesDispatchRecommendService.class);
            var rows=List.of(Map.<String,Object>of("recommendedOperator","li_operator","processStep","主板装配","planQty",10,"equipmentCode","EQ_TEST"),
                Map.<String,Object>of("recommendedOperator","huang_operator","processStep","电源板装配","planQty",10,"equipmentCode","EQ_TEST2"));
            when(recommendation.previewAssignments(anyString(),anyMap(),anySet(),anyBoolean())).thenReturn(Map.of("recommendations",rows,"canSubmit",true));
            when(recommendation.generateRecommendations(anyString())).thenReturn(Map.of("recommendations",rows,"canSubmit",true));
            when(recommendation.validateRecommendations(anyString(),anyList())).thenReturn(Map.of("canSubmit",true,"hasDanger",false));
            var equipment=mock(EquipmentMapper.class);var machine=new Equipment();machine.setEquipmentId(9L);machine.setEquipmentCode("EQ_TEST");machine.setEquipmentName("测试工位");
            var machine2=new Equipment();machine2.setEquipmentId(10L);machine2.setEquipmentCode("EQ_TEST2");machine2.setEquipmentName("测试工位2");
            when(equipment.equipmentList()).thenReturn(new ArrayList<>(List.of(machine,machine2)));
            Map<String,Object> more=new LinkedHashMap<>();more.put("plannerSchedulingService",scheduling);more.put("workOrderMapper",workOrders);
            more.put("dispatchTaskMapper",dispatches);more.put("processStepMapper",sql.getMapper(ProcessStepMapper.class));more.put("equipmentMapper",equipment);
            more.put("mesRuntimeStore",runtime);more.put("dispatchRecommendService",recommendation);more.put("processRouteMapper",mock(ProcessRouteMapper.class));
            more.put("bomMapper",mock(BomMapper.class));more.put("materialMapper",mock(MaterialMapper.class));more.put("mesSnapshotService",mock(MesSnapshotService.class));
            more.forEach((name,value)->ReflectionTestUtils.setField(workflow,name,value));
            var catalog=new AgentActionCatalog();var repository=mock(AgentPlanRepository.class);var stored=new HashMap<String,AgentActionPlan>();
            doAnswer(i->{AgentActionPlan p=i.getArgument(0);stored.put(p.planId(),p);return null;}).when(repository).save(any());
            when(repository.find(anyString())).thenAnswer(i->stored.get(i.getArgument(0)));
            var state=mock(AgentActionStateService.class);when(state.fingerprint(any(),anyMap(),any())).thenReturn("isolated-test-snapshot");
            var audit=mock(AgentAuditService.class);
            var execution=new AgentActionExecutionService(catalog,workflow,null,null,null,null,null,null,null,null,null,null,null,new ObjectMapper(),null);
            var confirmation=new AgentActionPlanService(catalog,repository,state,execution,audit);
            var selection=new ProductionDispatchSelection(recommendation,dispatches,workOrders,planMapper);
            var operations=new ProductionOperationService(catalog,confirmation,orders,planMapper,dispatches,users,scheduling,selection);
            var user=new LoginResponse();user.setUserId(1L);user.setUsername("admin");user.setRoleCode("ADMIN");
            var json=new ObjectMapper().findAndRegisterModules();var redis=mock(RedisUtil.class);var cache=new HashMap<String,Object>();
            when(redis.getJson(anyString(),eq(Map.class))).thenAnswer(i->cache.get(i.getArgument(0)));
            doAnswer(i->{cache.put(i.getArgument(0),json.convertValue(i.getArgument(1),Map.class));return null;}).when(redis).setJson(anyString(),any(),eq(86400L));
            var guard=mock(OrderSessionGuard.class);when(guard.requireActive("auth")).thenReturn(user);
            var model=mock(ProductionTaskModel.class);
            var tools=new ProductionTaskTools(catalog,orders,planMapper,equipment,scheduling,recommendation);
            try(var task=new TaskFixture(new ProductionTaskService(model,tools,operations,confirmation,redis,guard,json))){
            when(model.plan(anyString(),anyMap(),any())).thenReturn(json.valueToTree(Map.of("calls",List.of(Map.of("tool","orders","args",Map.of())))),
                json.valueToTree(Map.of("calls",List.of(),"reply","请选择 CO_TEST_1","goal","选订单、调整计划并派工")));
            assertThat(task.run("列出可计划订单").get("message")).isEqualTo("请选择 CO_TEST_1");
            var fields=new LinkedHashMap<String,Object>(Map.of("orderId","CO_TEST_1","plannedQty","10","planStart","2027-03-01","planEnd","2027-03-30","schedulingMode","DELIVERY"));
            when(model.plan(anyString(),anyMap(),any())).thenReturn(previewCall(json,"saveProductionPlan",fields));
            var initial=task.run("选择 CO_TEST_1，按交期优先安排");
            String oldId=(String)((Map<?,?>)initial.get("confirmation")).get("proposalId");
            fields.put("schedulingMode","COST");
            when(model.plan(anyString(),anyMap(),any())).thenReturn(previewCall(json,"saveProductionPlan",fields));
            var proposal=task.run("改为成本优先，其他条件不变");
            assertThatThrownBy(()->task.service.decide("lifecycle",oldId,"APPROVE","auth")).hasMessageContaining("被替换");
            assertThat(planMapper.planList()).isEmpty(); // Merely preparing a proposal must never write a plan.
            var saved=task.service.decide("lifecycle",(String)((Map<?,?>)proposal.get("confirmation")).get("proposalId"),"APPROVE","auth");
            String planNo=(String)((Map<?,?>)saved.get("result")).get("planId");
            assertThat(planMapper.planList()).singleElement().satisfies(p->assertThat(p.getPlanStatus()).isEqualTo("DRAFT"));
            assertThat(orders.getCustomerOrderById(order.getOrderId()).getAuditStatus()).isEqualTo("PLANNED");
            assertThat(sql.getMapper(ProductionPlanScheduleMapper.class).listByPlanId(planMapper.planList().get(0).getPlanId())).hasSize(1);
            for(String action:combined ? List.of("confirmSmartDispatch") : List.of("publishPlan","submitPlanToManager","confirmSmartDispatch")){
                doAnswer(i->{
                    var input=(Map<?,?>)i.getArgument(1);var stateData=(Map<?,?>)input.get("state");
                    assertThat(((Map<?,?>)stateData.get("anchors")).get("planId")).isEqualTo(planNo);
                    return previewCall(json,action,Map.of("planId",planNo));
                }).when(model).plan(anyString(),anyMap(),any());
                var next=task.run("继续刚才的任务");
                String id=(String)((Map<?,?>)next.get("confirmation")).get("proposalId");
                if(combined) {
                    assertThat(planMapper.planList().get(0).getPlanStatus()).isEqualTo("DRAFT");
                    assertThat(dispatches.dispatchList()).isEmpty();
                    assertThat(((Map<?,?>)((Map<?,?>)next.get("confirmation")).get("params")).get("preparePlan")).isEqualTo(true);
                }
                var result=task.service.decide("lifecycle",id,"APPROVE","auth");
                assertThat(result.get("status")).isEqualTo("EXECUTED");
                task.service.decide("lifecycle",id,"APPROVE","auth"); // Retried confirmations must be idempotent.
            }
            assertThat(workOrders.workOrderList()).hasSize(1);
            assertThat(dispatches.dispatchList()).hasSize(2).allSatisfy(d->assertThat(d.getStatus()).isEqualTo("ASSIGNED"));
            assertThat(dispatches.dispatchList().stream().map(DispatchTask::getOperatorId)).containsExactlyInAnyOrder(4L,5L);
            assertThat(workflow.listOperatorNotices("li_operator")).hasSize(1);
            assertThat(workflow.listOperatorNotices("huang_operator")).hasSize(1);
            runtimeState.getOperatorNotices().clear();
            assertThat(workflow.listOperatorNotices("li_operator")).singleElement().satisfies(n->assertThat(n.get("targetUsername")).isEqualTo("li_operator"));
            assertThat(workflow.listOperatorNotices("huang_operator")).singleElement().satisfies(n->assertThat(n.get("targetUsername")).isEqualTo("huang_operator"));
            assertThat(workflow.listOperatorNotices("another_operator")).isEmpty();
            verify(audit,times(combined?2:4)).record(eq(user),any(),eq("AI_PLAN_EXECUTED"),anyString(),anyMap(),eq(true),isNull());
            }
        }
    }
    private static com.fasterxml.jackson.databind.JsonNode previewCall(ObjectMapper json,String action,Map<String,Object> fields){
        return json.valueToTree(Map.of("calls",List.of(Map.of("tool","preview","args",Map.of("actionCode",action,"fields",fields)))));
    }
    private record TaskFixture(ProductionTaskService service) implements AutoCloseable {
        Map<String,Object> run(String text){return service.run(new ProductionTaskService.Turn(text,"lifecycle",null,null,List.of()),"auth",e->{});}
        public void close(){service.close();}
    }
    private static String snake(String value){return value.replaceAll("([a-z0-9])([A-Z])","$1_$2").toLowerCase(Locale.ROOT);}
    private static void table(JdbcTemplate db,Class<?> entity,String id){
        var columns=new ArrayList<String>();
        for(var field:entity.getDeclaredFields()){
            if(java.lang.reflect.Modifier.isStatic(field.getModifiers()))continue;
            var type=field.getType();String sqlType=type==Long.class?"BIGINT":type==Integer.class?"INT":type==BigDecimal.class?"DECIMAL(18,4)":type==LocalDate.class?"DATE":type==LocalDateTime.class?"TIMESTAMP":"VARCHAR(10000)";
            columns.add(snake(field.getName())+" "+sqlType+(field.getName().equals(id)?" AUTO_INCREMENT PRIMARY KEY":""));
        }
        db.execute("CREATE TABLE "+snake(entity.getSimpleName())+" ("+String.join(",",columns)+")");
    }
}
