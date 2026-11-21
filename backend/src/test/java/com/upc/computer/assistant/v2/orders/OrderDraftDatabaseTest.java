package com.upc.computer.assistant.v2.orders;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.upc.computer.dto.LoginResponse;
import com.upc.computer.entity.*;
import com.upc.computer.mapper.*;
import com.upc.computer.service.*;
import com.upc.computer.service.impl.CustomerPortalServiceImpl;
import org.apache.ibatis.session.*;
import org.apache.ibatis.mapping.Environment;
import org.mybatis.spring.SqlSessionTemplate;
import org.mybatis.spring.transaction.SpringManagedTransactionFactory;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.support.TransactionTemplate;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

/** Opt-in local DB tests: real business writes always roll back; concurrency uses assistant-only fixtures. */
@EnabledIfEnvironmentVariable(named="MES_ORDER_DB_TEST",matches="1")
class OrderDraftDatabaseTest {
    JdbcTemplate jdbc;DataSourceTransactionManager manager;OrderDraftRepository repo;OrderProductCatalog catalog;SqlSessionTemplate sql;LoginResponse user;
    @BeforeEach void setup()throws Exception {
        Map<String,String> env=new HashMap<>();for(String line:Files.readAllLines(Path.of(System.getenv("MES_ORDER_CONFIG_PATH")))){if(!line.isBlank()&&!line.startsWith("#")){int split=line.indexOf('=');if(split>0)env.put(line.substring(0,split),line.substring(split+1));}}
        DriverManagerDataSource ds=new DriverManagerDataSource(env.get("MES_AGENT_JDBC_URL"),env.get("MES_AGENT_JDBC_USER"),env.get("MES_AGENT_JDBC_PASSWORD"));
        jdbc=new JdbcTemplate(ds);manager=new DataSourceTransactionManager(ds);repo=new OrderDraftRepository(jdbc,new ObjectMapper());
        Configuration cfg=new Configuration(new Environment("order-test",new SpringManagedTransactionFactory(),ds));cfg.setMapUnderscoreToCamelCase(true);
        for(Class<?> type:List.of(MaterialMapper.class,CustomerOrderMapper.class,CustomerOrderItemMapper.class,CustomerPortalMapper.class,UserMapper.class))cfg.addMapper(type);
        sql=new SqlSessionTemplate(new SqlSessionFactoryBuilder().build(cfg));catalog=new OrderProductCatalog(sql.getMapper(MaterialMapper.class));
        user=new LoginResponse();user.setUserId(jdbc.queryForObject("SELECT MIN(user_id) FROM `user` WHERE status=1",Long.class));user.setRoleCode("CUSTOMER");user.setUsername("rollback-test");user.setCustomerName("助手自动验证_仅事务回滚");
    }
    OrderDraftRepository.Draft fixture() {
        var product=catalog.products().get(0);var d=new OrderDraftRepository.Draft();d.id=UUID.randomUUID().toString();d.owner=user.getUserId();d.sessionId="rollback-"+UUID.randomUUID();d.role=user.getRoleCode();d.version=1;d.status="READY";d.expiresAt=LocalDateTime.now().plusHours(1);
        d.fields.putAll(Map.of("materialId",product.get("materialId").toString(),"quantity","1","requiredDeliveryDate",LocalDate.now().plusDays(10).toString(),"customerName",user.getCustomerName(),"receiverName","回滚验证","receiverPhone","13800138000","receiverAddress","仅事务验证，不发货"));d.fingerprint=catalog.fingerprint(catalog.require(d.fields.get("materialId")));return d;
    }
    OrderDraftService service(OrderBusinessWriter writer){return new OrderDraftService(repo,catalog,mock(OrderIntakeClient.class),writer,new ObjectMapper(),manager);}
    @Test void realCustomerOrderAndDraftResultShareRollback() {
        CustomerPortalServiceImpl portal=new CustomerPortalServiceImpl();AuthService auth=mock(AuthService.class);when(auth.getCurrentUser(user.getUserId())).thenReturn(sql.getMapper(UserMapper.class).getUserById(user.getUserId()));
        ReflectionTestUtils.setField(portal,"authService",auth);ReflectionTestUtils.setField(portal,"materialMapper",sql.getMapper(MaterialMapper.class));ReflectionTestUtils.setField(portal,"customerOrderMapper",sql.getMapper(CustomerOrderMapper.class));ReflectionTestUtils.setField(portal,"customerOrderItemMapper",sql.getMapper(CustomerOrderItemMapper.class));ReflectionTestUtils.setField(portal,"customerPortalMapper",sql.getMapper(CustomerPortalMapper.class));
        OrderDraftService service=service(new OrderBusinessWriter(portal,mock(MesWorkflowService.class)));var draft=fixture();AtomicReference<String> number=new AtomicReference<>();
        new TransactionTemplate(manager).execute(status->{repo.insert(draft);var envelope=service.confirm(user,draft.id,1,"rollback-confirm");
            var view=(Map<?,?>)envelope.get("orderDraft");number.set(((Map<?,?>)view.get("result")).get("orderNo").toString());
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM customer_order WHERE order_no=?",Integer.class,number.get())).isEqualTo(1);
            assertThat(repo.lock(draft.id,user.getUserId()).status).isEqualTo("SUBMITTED");status.setRollbackOnly();return null;});
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM customer_order WHERE order_no=?",Integer.class,number.get())).isZero();
        assertThat(repo.bySession(user.getUserId(),draft.sessionId)).isNull();
    }
    @Test void concurrentConfirmationsCreateOnlyOnceAcrossRowLocks()throws Exception {
        var d=fixture();new TransactionTemplate(manager).execute(s->{repo.insert(d);return null;});
        OrderBusinessWriter writer=mock(OrderBusinessWriter.class);AtomicInteger calls=new AtomicInteger();
        when(writer.create(any(),anyMap(),any(),anyString())).thenAnswer(a->{calls.incrementAndGet();Thread.sleep(150);return Map.of("orderNo","SIMULATED-NO-BUSINESS-WRITE","status","PENDING");});
        var service=service(writer);ExecutorService pool=Executors.newFixedThreadPool(2);
        try {
            var first=pool.submit(()->service.confirm(user,d.id,1,"concurrent-a"));var second=pool.submit(()->service.confirm(user,d.id,1,"concurrent-b"));
            assertThat(first.get(20,TimeUnit.SECONDS).get("handled")).isEqualTo(true);assertThat(second.get(20,TimeUnit.SECONDS).get("handled")).isEqualTo(true);assertThat(calls.get()).isEqualTo(1);
        }finally{pool.shutdownNow();jdbc.update("DELETE FROM assistant_order_draft_v2 WHERE id=? AND session_id=?",d.id,d.sessionId);}
    }
    @Test void administratorUsesExistingMesOrderServiceWithExactProductAndRollsBack() {
        user.setRoleCode("ADMIN");user.setUsername(sql.getMapper(UserMapper.class).getUserById(user.getUserId()).getUsername());
        MesWorkflowService mes=new MesWorkflowService();
        ReflectionTestUtils.setField(mes,"materialMapper",sql.getMapper(MaterialMapper.class));ReflectionTestUtils.setField(mes,"userMapper",sql.getMapper(UserMapper.class));
        ReflectionTestUtils.setField(mes,"customerOrderMapper",sql.getMapper(CustomerOrderMapper.class));ReflectionTestUtils.setField(mes,"customerOrderItemMapper",sql.getMapper(CustomerOrderItemMapper.class));
        MesRuntimeStore runtime=spy(new MesRuntimeStore());doReturn(new MesRuntimeStore.MesRuntimeState()).when(runtime).load();doNothing().when(runtime).save(any());
        ReflectionTestUtils.setField(mes,"mesRuntimeStore",runtime);ReflectionTestUtils.setField(mes,"mesSnapshotService",mock(MesSnapshotService.class));
        var d=fixture();var selected=catalog.products().get(catalog.products().size()-1);d.fields.put("materialId",selected.get("materialId").toString());d.fingerprint=catalog.fingerprint(catalog.require(d.fields.get("materialId")));
        var service=service(new OrderBusinessWriter(mock(CustomerPortalService.class),mes));AtomicReference<String> number=new AtomicReference<>();
        new TransactionTemplate(manager).execute(status->{repo.insert(d);var response=service.confirm(user,d.id,1,"admin-rollback");
            number.set(((Map<?,?>)((Map<?,?>)response.get("orderDraft")).get("result")).get("orderNo").toString());
            Long actual=jdbc.queryForObject("SELECT i.material_id FROM customer_order_item i JOIN customer_order o ON o.order_id=i.order_id WHERE o.order_no=?",Long.class,number.get());
            assertThat(actual.toString()).isEqualTo(selected.get("materialId").toString());status.setRollbackOnly();return null;});
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM customer_order WHERE order_no=?",Integer.class,number.get())).isZero();assertThat(repo.bySession(user.getUserId(),d.sessionId)).isNull();
    }
}
