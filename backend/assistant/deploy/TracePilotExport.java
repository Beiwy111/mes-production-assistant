// Local operator CLI, from repository root; explicit allowlisted order only. Output stays under ignored rag/data.
import com.upc.computer.assistant.v2.runtime.Stage5RuntimeMain;
import com.upc.computer.assistant.v2.mapper.graphexport.GraphExportMapper;
import com.upc.computer.assistant.v2.tools.graphexport.GraphExportService;
import java.nio.file.*;
import java.util.*;
import java.time.*;
import java.sql.Connection;
import com.fasterxml.jackson.databind.ObjectMapper;
class TracePilotExport {
 public static void main(String[] args)throws Exception{
  Path root=Path.of("backend/assistant");var env=new HashMap<String,String>();
  for(String l:Files.readAllLines(root.resolve("deploy/.env.agents.local"))){var a=l.split("=",2);if(a.length==2&&a[0].equals("MES_AGENT_ALLOWED_ORDER_IDS"))env.put(a[0],a[1]);}
  for(String l:Files.readAllLines(root.resolve("deploy/.env.trace-read.local"))){var a=l.split("=",2);
   if(a.length!=2||!Set.of("MES_AGENT_JDBC_URL","MES_AGENT_JDBC_USER","MES_AGENT_JDBC_PASSWORD").contains(a[0])||env.putIfAbsent(a[0],a[1])!=null)throw new IllegalArgumentException("Invalid dedicated trace reader");}
  if(!"mes_asst_ro_trace".equals(env.get("MES_AGENT_JDBC_USER")))throw new IllegalArgumentException("Dedicated trace SELECT account required");
  // Optional maintenance-only override, independent of the interactive agent allowlist.
  Path graphOrders=root.resolve("deploy/.env.graph-orders.local");
  String allowed=env.get("MES_AGENT_ALLOWED_ORDER_IDS");
  if(Files.exists(graphOrders)){
   String entry=Files.readString(graphOrders).strip();
   if(!entry.matches("MES_GRAPH_EXPORT_ORDER_IDS=[1-9][0-9]*(,[1-9][0-9]*)*"))throw new IllegalArgumentException("Invalid graph export allowlist");
   allowed=entry.substring(entry.indexOf('=')+1);
  }
  long order=Long.parseLong(args[0]);if(allowed==null||!Arrays.asList(allowed.split(",")).contains(""+order))throw new IllegalArgumentException("Order not allowlisted");
  var factory=Stage5RuntimeMain.createFactory(env);factory.getConfiguration().addMapper(GraphExportMapper.class);
  try(var session=factory.openSession(false)){var c=session.getConnection();c.setReadOnly(true);c.setTransactionIsolation(Connection.TRANSACTION_REPEATABLE_READ);c.setAutoCommit(false);if(!c.isReadOnly())throw new IllegalStateException();
  var packet=new GraphExportService(session.getMapper(GraphExportMapper.class),Clock.systemUTC()).export(order);if(!packet.complete())throw new IllegalStateException("Incomplete projection");Files.write(root.resolve("rag/data/chain-order-"+order+".json"),new ObjectMapper().writeValueAsBytes(packet));session.rollback(true);}
 }
}
