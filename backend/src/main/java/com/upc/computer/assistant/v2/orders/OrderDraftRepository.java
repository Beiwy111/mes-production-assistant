package com.upc.computer.assistant.v2.orders;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import java.sql.*;
import java.time.LocalDateTime;
import java.util.*;

/** Separate additive table; row lock and original order insert share the Spring transaction. */
@Repository
public class OrderDraftRepository {
    private final JdbcTemplate jdbc;private final ObjectMapper json;
    public OrderDraftRepository(JdbcTemplate jdbc,ObjectMapper json){this.jdbc=jdbc;this.json=json;}
    public static class Draft {
        public String id,sessionId,role,status="COLLECTING",fingerprint="",lastRequest="",lastHash="";
        public long owner;public int version;
        public LocalDateTime expiresAt;
        public Map<String,Object> fields=new LinkedHashMap<>(),result=new LinkedHashMap<>();
    }
    public boolean ready(){try{jdbc.queryForList("SELECT id FROM assistant_order_draft_v2 LIMIT 1");return true;}catch(Exception e){return false;}}
    public Draft bySession(long owner,String session){return one("SELECT * FROM assistant_order_draft_v2 WHERE owner_id=? AND session_id=?",owner,session);}
    public Draft lock(String id,long owner){return one("SELECT * FROM assistant_order_draft_v2 WHERE id=? AND owner_id=? FOR UPDATE",id,owner);}
    private Draft one(String sql,Object...args){List<Draft> rows=jdbc.query(sql,(rs,n)->read(rs),args);return rows.isEmpty()?null:rows.get(0);}
    public void insert(Draft d){jdbc.update("INSERT IGNORE INTO assistant_order_draft_v2 (id,owner_id,session_id,owner_role,status,version,fields_json,result_json,product_fingerprint,expires_at,last_request,last_hash) VALUES (?,?,?,?,?,?,?,?,?,?,?,?)",
            d.id,d.owner,d.sessionId,d.role,d.status,d.version,encode(d.fields),encode(d.result),d.fingerprint,Timestamp.valueOf(d.expiresAt),d.lastRequest,d.lastHash);}
    public void save(Draft d){jdbc.update("UPDATE assistant_order_draft_v2 SET status=?,version=?,fields_json=?,result_json=?,product_fingerprint=?,expires_at=?,last_request=?,last_hash=?,updated_at=CURRENT_TIMESTAMP WHERE id=? AND owner_id=?",
            d.status,d.version,encode(d.fields),encode(d.result),d.fingerprint,Timestamp.valueOf(d.expiresAt),d.lastRequest,d.lastHash,d.id,d.owner);}
    private Draft read(ResultSet r)throws SQLException {
        Draft d=new Draft();d.id=r.getString("id");d.owner=r.getLong("owner_id");d.sessionId=r.getString("session_id");d.role=r.getString("owner_role");d.status=r.getString("status");d.version=r.getInt("version");
        d.fingerprint=r.getString("product_fingerprint");d.expiresAt=r.getTimestamp("expires_at").toLocalDateTime();d.lastRequest=r.getString("last_request");d.lastHash=r.getString("last_hash");
        try{d.fields=json.readValue(r.getString("fields_json"),new TypeReference<>(){});d.result=json.readValue(r.getString("result_json"),new TypeReference<>(){});}catch(Exception e){throw new SQLException("Invalid order draft data");}return d;
    }
    private String encode(Object value){try{return json.writeValueAsString(value);}catch(Exception e){throw new IllegalStateException("Invalid draft data");}}
}
