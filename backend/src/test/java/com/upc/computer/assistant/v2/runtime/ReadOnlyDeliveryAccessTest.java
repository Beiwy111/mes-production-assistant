package com.upc.computer.assistant.v2.runtime;

import com.upc.computer.assistant.v2.mapper.delivery.AiDeliveryReadMapper;
import com.upc.computer.assistant.v2.tools.delivery.*;
import org.apache.ibatis.session.*;
import org.junit.jupiter.api.Test;
import java.sql.Connection;
import java.time.Clock;
import java.util.*;
import java.util.function.Function;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ReadOnlyDeliveryAccessTest {
    Function<Function<AiDeliveryQueryService,Map<String,Object>>,Map<String,Object>> access(SqlSessionFactory factory) throws Exception {
        return new ReadOnlyDeliveryAccess(factory,Clock.systemUTC());
    }
    @Test void readsRunOnlyAfterReadOnlyRepeatableReadAndRollbackThenClose() throws Exception {
        var factory=mock(SqlSessionFactory.class);
        var session=mock(SqlSession.class);
        var connection=mock(Connection.class);
        var mapper=mock(AiDeliveryReadMapper.class);
        when(factory.openSession(false)).thenReturn(session);
        when(session.getConnection()).thenReturn(connection);
        when(connection.isReadOnly()).thenReturn(true);
        when(connection.getTransactionIsolation()).thenReturn(Connection.TRANSACTION_REPEATABLE_READ);
        when(session.getMapper(AiDeliveryReadMapper.class)).thenReturn(mapper);
        when(mapper.resolve(10L,null)).thenReturn(List.of(Map.of("order_id",10L)));
        when(mapper.order(10L)).thenReturn(List.of(Map.of("order_id",10L)));
        var access=access(factory);
        assertEquals("PARTIAL",access.apply(service->service.resolve(-1,"10",null,null,null)).get("status"));
        var ordered=inOrder(factory,session,connection,mapper);
        ordered.verify(factory).openSession(false);
        ordered.verify(session).getConnection();
        ordered.verify(connection).setReadOnly(true);
        ordered.verify(connection).setTransactionIsolation(Connection.TRANSACTION_REPEATABLE_READ);
        ordered.verify(connection).setAutoCommit(false);
        ordered.verify(mapper).resolve(10L,null);
        ordered.verify(session).rollback(true);
        ordered.verify(session).close();
        verify(connection,never()).commit();
        verify(session,never()).commit();
    }
    @Test void readOnlyRefusalPreventsAnyMapperReadAndStillRollsBack() throws Exception {
        var factory=mock(SqlSessionFactory.class);var session=mock(SqlSession.class);var connection=mock(Connection.class);
        when(factory.openSession(false)).thenReturn(session);when(session.getConnection()).thenReturn(connection);
        when(connection.isReadOnly()).thenReturn(false);
        var access=access(factory);
        assertThrows(RuntimeException.class,()->access.apply(service->service.resolve(-1,"10",null,null,null)));
        verify(session,never()).getMapper(any());
        verify(session).rollback(true);verify(session).close();
    }
    @Test void readFailureRollsBackAndClosesWithoutCommit() throws Exception {
        var factory=mock(SqlSessionFactory.class);var session=mock(SqlSession.class);var connection=mock(Connection.class);
        when(factory.openSession(false)).thenReturn(session);when(session.getConnection()).thenReturn(connection);
        when(connection.isReadOnly()).thenReturn(true);
        when(connection.getTransactionIsolation()).thenReturn(Connection.TRANSACTION_REPEATABLE_READ);
        var access=access(factory);
        assertThrows(IllegalArgumentException.class,()->access.apply(service->{throw new IllegalArgumentException("failure");}));
        verify(session).rollback(true);verify(session).close();verify(session,never()).commit();
    }
}
