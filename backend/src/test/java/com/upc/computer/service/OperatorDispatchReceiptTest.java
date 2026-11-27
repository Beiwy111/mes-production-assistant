package com.upc.computer.service;

import com.upc.computer.common.BusinessException;
import com.upc.computer.dto.MesActionRequest;
import com.upc.computer.entity.*;
import com.upc.computer.mapper.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.AfterEach;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.*;
import org.apache.ibatis.transaction.jdbc.JdbcTransactionFactory;
import org.h2.jdbcx.JdbcDataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.util.ReflectionTestUtils;
import java.math.BigDecimal;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class OperatorDispatchReceiptTest {
    private SqlSession sql;

    @AfterEach void closeDatabase() { if (sql != null) sql.close(); }

    private DispatchTaskMapper dispatchDatabase() {
        var ds = new JdbcDataSource();
        ds.setURL("jdbc:h2:mem:" + UUID.randomUUID() + ";MODE=MySQL;DB_CLOSE_DELAY=-1");
        var db = new JdbcTemplate(ds);
        db.execute("CREATE TABLE production_plan (plan_id bigint primary key,plan_no varchar(60))");
        db.execute("CREATE TABLE work_order (work_order_id bigint primary key,plan_id bigint)");
        db.execute("CREATE TABLE process_step (step_id bigint primary key,step_name varchar(60))");
        db.execute("""
                CREATE TABLE dispatch_task (dispatch_id bigint auto_increment primary key,dispatch_no varchar(60),
                    work_order_id bigint,step_id bigint,operator_id bigint,equipment_id bigint,assigned_quantity decimal,
                    accepted_quantity decimal,completed_quantity decimal,assigned_by bigint,assigned_at datetime,
                    accepted_at datetime,status varchar(20),remark varchar(500),created_at datetime,updated_at datetime)
                """);
        db.execute("""
                CREATE TABLE work_report (report_id bigint auto_increment primary key,report_no varchar(60),
                    work_order_id bigint,dispatch_id bigint,step_id bigint,operator_id bigint,equipment_id bigint,
                    report_date date,start_time datetime,end_time datetime,completed_quantity decimal,
                    qualified_quantity decimal,unqualified_quantity decimal,work_hours decimal(10,1),
                    report_status varchar(20),confirmed_by bigint,confirmed_at datetime,remark varchar(500),
                    created_at datetime,updated_at datetime)
                """);
        db.update("INSERT INTO production_plan VALUES(2,'PP123')");
        db.update("INSERT INTO work_order VALUES(3,2)");
        db.update("INSERT INTO process_step VALUES(5,'主板装配')");
        var config = new Configuration(new Environment("dispatch-test",new JdbcTransactionFactory(),ds));
        config.setMapUnderscoreToCamelCase(true);
        config.addMapper(DispatchTaskMapper.class);
        config.addMapper(ProductionPlanMapper.class);
        config.addMapper(WorkReportMapper.class);
        sql = new SqlSessionFactoryBuilder().build(config).openSession(true);
        return sql.getMapper(DispatchTaskMapper.class);
    }

    @Test void confirmedSmartDispatchAppearsInAssigneesTasksAndInboxThenAccepts() {
        var service = new MesWorkflowService();
        var plans = mock(ProductionPlanMapper.class);
        var workOrders = mock(WorkOrderMapper.class);
        var dispatches = dispatchDatabase();
        var users = mock(UserMapper.class);
        var steps = mock(ProcessStepMapper.class);
        var recommendations = mock(MesDispatchRecommendService.class);
        var runtime = mock(MesRuntimeStore.class);
        ReflectionTestUtils.setField(service,"productionPlanMapper",plans);
        ReflectionTestUtils.setField(service,"workOrderMapper",workOrders);
        ReflectionTestUtils.setField(service,"dispatchTaskMapper",dispatches);
        var reports = sql.getMapper(WorkReportMapper.class);
        ReflectionTestUtils.setField(service,"workReportMapper",reports);
        ReflectionTestUtils.setField(service,"workOrderProgressService",mock(WorkOrderProgressService.class));
        ReflectionTestUtils.setField(service,"userMapper",users);
        ReflectionTestUtils.setField(service,"processStepMapper",steps);
        ReflectionTestUtils.setField(service,"equipmentMapper",mock(EquipmentMapper.class));
        ReflectionTestUtils.setField(service,"mesSnapshotService",mock(MesSnapshotService.class));
        ReflectionTestUtils.setField(service,"dispatchRecommendService",recommendations);
        ReflectionTestUtils.setField(service,"mesRuntimeStore",runtime);
        var state = new MesRuntimeStore.MesRuntimeState();
        when(runtime.load()).thenReturn(state);
        when(runtime.getExtra(eq(state),anyString())).thenCallRealMethod();
        var plan = new ProductionPlan();
        plan.setPlanId(2L);
        plan.setPlanNo("PP123");
        plan.setPlanStatus("SUBMITTED");
        when(plans.planList()).thenReturn(new ArrayList<>(List.of(plan)));
        when(plans.lockPlanForDispatch("PP123")).thenAnswer(call -> sql.getMapper(ProductionPlanMapper.class).lockPlanForDispatch("PP123"));
        var wo = new WorkOrder();
        wo.setWorkOrderId(3L);
        wo.setWorkOrderNo("WO234");
        wo.setPlanId(2L);
        wo.setStatus("RELEASED");
        when(workOrders.workOrderList()).thenReturn(new ArrayList<>(List.of(wo)));
        when(workOrders.getWorkOrderById(3L)).thenReturn(wo);
        var user = new User();
        user.setUserId(4L);
        user.setUsername("li_operator");
        user.setRealName("李师傅");
        when(users.getUserByUsername("li_operator")).thenReturn(user);
        when(users.getUserById(4L)).thenReturn(user);
        var step = new ProcessStep();
        step.setStepId(5L);
        step.setStepName("主板装配");
        when(steps.stepList()).thenReturn(new ArrayList<>(List.of(step)));
        when(steps.getStepById(5L)).thenReturn(step);
        var rows = List.of(Map.of("recommendedOperator","li_operator","processStep","主板装配","planQty",10));
        when(recommendations.generateRecommendations("PP123")).thenReturn(Map.of("recommendations",rows));
        when(recommendations.validateRecommendations(eq("PP123"),anyList())).thenReturn(Map.of("hasDanger",false,"canSubmit",true));
        var reviewed = request("confirmSmartDispatch","manager","manager",Map.of("planId","PP123","recommendations",rows));
        var result = (Map<?,?>) service.executeReviewedDispatch(reviewed);
        assertThat(result.get("count")).isEqualTo(1);
        assertThat(dispatches.hasDispatchForStep("PP123","主板装配")).isTrue();
        assertThatThrownBy(() -> service.executeReviewedDispatch(reviewed)).hasMessageContaining("已有派工");
        var tasks = dispatches.dispatchList();
        assertThat(tasks).hasSize(1);
        var task = tasks.get(0);
        assertThat(task.getOperatorId()).isEqualTo(4L);
        assertThat(task.getStatus()).isEqualTo("ASSIGNED");
        assertThat(task.getAssignedQuantity()).isEqualByComparingTo(BigDecimal.TEN);
        assertThat(service.listOperatorNotices("li_operator")).singleElement().satisfies(notice ->
                assertThat(notice).containsEntry("dispatchNo",task.getDispatchNo()).containsEntry("link","/production/my-dispatch"));
        assertThat(service.listOperatorNotices("wu_operator")).isEmpty();
        service.remindDispatch(task.getDispatchNo(),"li_operator","manager");
        assertThat(service.listOperatorNotices("li_operator")).hasSize(2);
        assertThatThrownBy(() -> service.remindDispatch(task.getDispatchNo(),"wu_operator","manager"))
                .isInstanceOf(BusinessException.class);
        var target = Map.<String,Object>of("dispatchId",task.getDispatchNo());
        assertThatThrownBy(() -> service.execute(request("acceptDispatch","wu_operator","operator",target)))
                .hasMessageContaining("无权");
        service.execute(request("acceptDispatch","li_operator","operator",target));
        assertThat(dispatches.getDispatchById(task.getDispatchId()).getStatus()).isEqualTo("ACCEPTED");
        state.getIssueTasks().add(Map.of("workOrderId","WO234","materialCode","MAT-1","requiredQty",10,
                "issuedQty",10,"status","已完成"));
        assertThatThrownBy(() -> service.execute(request("startDispatch","li_operator","operator",target)))
                .hasMessageContaining("领料");
        var picked = (Map<?,?>) service.execute(request("pickAllMaterials","li_operator","operator",target));
        assertThat(picked.get("allDone")).isEqualTo(true);
        service.execute(request("startDispatch","li_operator","operator",target));
        assertThat(dispatches.getDispatchById(task.getDispatchId()).getStatus()).isEqualTo("PRODUCING");
        service.execute(request("submitReport","li_operator","operator",Map.of("dispatchId",task.getDispatchNo(),
                "reportQty",10,"startTime","2026-01-10 08:30:00","endTime","2026-01-10 10:00:00")));
        assertThat(reports.reportList()).singleElement().satisfies(report -> {
            assertThat(report.getCompletedQuantity()).isEqualByComparingTo(BigDecimal.TEN);
            assertThat(report.getOperatorId()).isEqualTo(4L);
        });
        assertThat(dispatches.getDispatchById(task.getDispatchId()).getStatus()).isEqualTo("COMPLETED");
        assertThatThrownBy(() -> service.remindDispatch(task.getDispatchNo(),"li_operator","manager"))
                .isInstanceOf(BusinessException.class);
    }

    private MesActionRequest request(String action,String username,String role,Map<String,Object> params) {
        var request = new MesActionRequest();
        request.setAction(action);
        request.setRoleKey(role);
        request.setOperator(username);
        request.setPayload(params);
        return request;
    }
}
