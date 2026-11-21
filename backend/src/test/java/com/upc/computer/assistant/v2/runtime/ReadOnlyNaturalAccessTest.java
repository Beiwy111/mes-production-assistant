package com.upc.computer.assistant.v2.runtime;

import org.apache.ibatis.session.*;
import org.junit.jupiter.api.Test;
import java.sql.Connection;
import java.time.Clock;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ReadOnlyNaturalAccessTest {
    @Test void rechecksActiveIdentityAndMenuPolicyInsideReadOnlyTransactionAndAlwaysRollsBack() throws Exception {
        SqlSessionFactory factory=mock(SqlSessionFactory.class);SqlSession session=mock(SqlSession.class);Connection connection=mock(Connection.class);
        WorkbenchIdentityReadMapper identity=mock(WorkbenchIdentityReadMapper.class);
        when(factory.openSession(false)).thenReturn(session);when(session.getConnection()).thenReturn(connection);
        when(connection.isReadOnly()).thenReturn(true);when(connection.getTransactionIsolation()).thenReturn(Connection.TRANSACTION_REPEATABLE_READ);
        when(session.getMapper(WorkbenchIdentityReadMapper.class)).thenReturn(identity);when(identity.identity(42L)).thenReturn(Map.of("role_code","ADMIN"),null);
        var access=new ReadOnlyNaturalAccess(factory,Clock.systemUTC());assertEquals(Map.of("ok",true),access.apply(42L,d->Map.of("ok",true)));
        assertThrows(RuntimeException.class,()->access.apply(42L,d->{fail("Revoked owner must not read business data");return Map.of();}));
        var order=inOrder(connection,identity,session);order.verify(connection).setReadOnly(true);order.verify(connection).setTransactionIsolation(Connection.TRANSACTION_REPEATABLE_READ);order.verify(connection).setAutoCommit(false);order.verify(identity).identity(42L);order.verify(session).rollback(true);order.verify(session).close();
        verify(session,times(2)).rollback(true);verify(session,never()).commit();verify(connection,never()).commit();
    }
    @Test void refusesUnsafeConnectionBeforeAnyMapperOrOperation() throws Exception {
        SqlSessionFactory factory=mock(SqlSessionFactory.class);SqlSession session=mock(SqlSession.class);Connection connection=mock(Connection.class);
        when(factory.openSession(false)).thenReturn(session);when(session.getConnection()).thenReturn(connection);
        var access=new ReadOnlyNaturalAccess(factory,Clock.systemUTC());assertThrows(RuntimeException.class,()->access.apply(42L,d->Map.of()));
        verify(session,never()).getMapper(any());verify(session).rollback(true);verify(session).close();
    }
}
