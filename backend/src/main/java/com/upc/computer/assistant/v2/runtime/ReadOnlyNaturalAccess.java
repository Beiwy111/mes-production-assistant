package com.upc.computer.assistant.v2.runtime;

import com.upc.computer.assistant.v2.mapper.delivery.AiDeliveryReadMapper;
import com.upc.computer.assistant.v2.mapper.business.AiBusinessReadMapper;
import com.upc.computer.assistant.v2.tools.delivery.*;
import org.apache.ibatis.session.*;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import java.sql.*;
import java.time.Clock;
import java.util.Map;
import java.util.function.Function;

/** Fresh ADMIN check and business reads share an explicit read-only transaction, always rolled back. */
public final class ReadOnlyNaturalAccess implements NaturalLanguageRuntime.Access {
    private final SqlSessionFactory factory;
    private final Clock clock;
    private final DeliveryEvidenceRegistry registry;
    public ReadOnlyNaturalAccess(SqlSessionFactory factory,Clock clock) {
        this.factory=factory; this.clock=clock; registry=new DeliveryEvidenceRegistry(clock);
    }
    @Override public Map<String,Object> apply(long owner,Function<NaturalLanguageRuntime.Data,Map<String,Object>> operation) {
        try(SqlSession session=factory.openSession(false)) {
            try {
                Connection connection=session.getConnection();
                connection.setReadOnly(true);
                connection.setTransactionIsolation(Connection.TRANSACTION_REPEATABLE_READ);
                connection.setAutoCommit(false);
                if(!connection.isReadOnly() || connection.getAutoCommit() || connection.getTransactionIsolation()!=Connection.TRANSACTION_REPEATABLE_READ)
                    throw new IllegalStateException("Read-only transaction unavailable");
                var policy=AssistantReadPolicy.load(session.getMapper(WorkbenchIdentityReadMapper.class),owner);
                return operation.apply(new NaturalLanguageRuntime.Data(session.getMapper(NaturalBusinessReadMapper.class),
                    new AiDeliveryQueryService(session.getMapper(AiDeliveryReadMapper.class),session.getMapper(AiBusinessReadMapper.class),registry,clock),policy));
            } catch(SQLException unavailable) { throw new IllegalStateException("Read-only transaction unavailable"); }
            finally { session.rollback(true); }
        }
    }
}
