package com.upc.computer.assistant.v2.tools.delivery;
import com.upc.computer.assistant.v2.mapper.delivery.AiDeliveryReadMapper;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.mapping.SqlCommandType;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class DeliveryReadMapperTest {
    @Test void everyStatementParsesAsBoundSelectWithoutDatabase() {
        var configuration = new Configuration(); configuration.addMapper(AiDeliveryReadMapper.class);
        for (var method : AiDeliveryReadMapper.class.getDeclaredMethods()) {
            var statement = configuration.getMappedStatement(AiDeliveryReadMapper.class.getName()+"."+method.getName());
            assertEquals(SqlCommandType.SELECT, statement.getSqlCommandType());
            var sql = statement.getBoundSql(Map.of("id",10L,"number","O-10")).getSql();
            assertTrue(sql.contains("?")); assertFalse(sql.contains("${"));
        }
    }
}
