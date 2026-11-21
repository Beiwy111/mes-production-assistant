package com.upc.computer.assistant.v2.runtime;

import com.upc.computer.assistant.v2.mapper.delivery.AiDeliveryReadMapper;
import com.upc.computer.assistant.v2.mapper.business.AiBusinessReadMapper;
import com.upc.computer.assistant.v2.tools.delivery.*;
import org.apache.ibatis.session.*;
import java.sql.*;
import java.time.Clock;
import java.util.Map;
import java.util.function.Function;

/** Explicit JDBC transaction boundary; Spring transaction annotations are not needed or used. */
public final class ReadOnlyDeliveryAccess implements Function<Function<AiDeliveryQueryService,Map<String,Object>>,Map<String,Object>> {
    private final SqlSessionFactory factory;
    private final Clock clock;
    private final DeliveryEvidenceRegistry registry;

    public ReadOnlyDeliveryAccess(SqlSessionFactory factory, Clock clock) {
        this.factory=factory;this.clock=clock;this.registry=new DeliveryEvidenceRegistry(clock);
    }

    @Override public Map<String,Object> apply(Function<AiDeliveryQueryService,Map<String,Object>> operation) {
        // Force rollback even when MyBatis considers a SELECT-only session "not dirty".
        try (SqlSession session=factory.openSession(false)) {
            try {
                Connection connection=session.getConnection();
                connection.setReadOnly(true);
                connection.setTransactionIsolation(Connection.TRANSACTION_REPEATABLE_READ);
                connection.setAutoCommit(false);
                if (!connection.isReadOnly() || connection.getAutoCommit()
                        || connection.getTransactionIsolation()!=Connection.TRANSACTION_REPEATABLE_READ)
                    throw new IllegalStateException("Read-only transaction unavailable");
                return operation.apply(new AiDeliveryQueryService(session.getMapper(AiDeliveryReadMapper.class),session.getMapper(AiBusinessReadMapper.class),registry,clock));
            } catch (SQLException failure) {
                throw new IllegalStateException("Read-only transaction unavailable");
            } finally {
                session.rollback(true);
            }
        }
    }
}
