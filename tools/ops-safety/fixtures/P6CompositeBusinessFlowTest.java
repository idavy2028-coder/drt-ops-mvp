import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.sql.*;
import java.util.*;
import java.util.concurrent.*;
import java.lang.reflect.Proxy;
import com.sun.net.httpserver.HttpServer;
import com.fasterxml.jackson.databind.*;

/** Runs the real main/prepare flow. Only HTTP service and JDBC are test dependencies. */
public class P6CompositeBusinessFlowTest {
 static final ObjectMapper JSON=new ObjectMapper();
 static final String SECRET="DO_NOT_EXPORT_password_token_url_phone";
 static final String TOKEN="synthetic.payload.signature";
 static final String[] IDS={"00000000-0000-0000-0000-000000000001","00000000-0000-0000-0000-000000000002","00000000-0000-0000-0000-000000000003"};
 static void require(boolean ok,String code){if(!ok)throw new AssertionError(code);}
 record Call(String method,String path,String data){}
 static List<Call> calls(){
  var q=new ArrayList<Call>();
  q.add(new Call("POST","/api/auth/login","{\"accessToken\":\""+TOKEN+"\"}"));
  q.add(new Call("POST","/api/auth/password",null));
  q.add(q.get(0));
  for(String id:IDS)q.add(new Call("POST","/api/vehicles","{\"id\":\""+id+"\"}"));
  for(int i=1;i<=4;i++){
   String p="/api/terminals/SYN000"+i;
   q.add(new Call("POST","/api/terminals","{}"));q.add(new Call("GET",p,"{\"version\":1}"));
   q.add(new Call("POST",p+"/bind","{}"));
   q.add(new Call("POST",p+"/capability-verifications","{}"));q.add(q.get(q.size()-1));
  }
  for(String id:IDS){String p="/api/onboard-systems/"+id;q.add(new Call("GET",p,"{\"version\":1}"));q.add(new Call("POST",p+"/configuration/preview","{}"));q.add(new Call("POST",p+"/configuration","{}"));}
  return q;
 }
 static Object jdbc(Class<?> type,java.lang.reflect.InvocationHandler handler){return Proxy.newProxyInstance(type.getClassLoader(),new Class<?>[]{type},handler);}
 static void installDriver(String mode)throws Exception {
  DriverManager.registerDriver(new Driver(){
   int queries;
   public boolean acceptsURL(String u){return u.startsWith("jdbc:postgresql://127.0.0.1:");}
   public Connection connect(String u,Properties p)throws SQLException {
    if(!acceptsURL(u))return null;
    require(u.endsWith("/composite_live")&&p.getProperty("user").equals("composite"),"DB_TARGET_CHANGED");
    if(mode.startsWith("sql"))throw new SQLException(SECRET,mode.equals("sql")?"23505":"TOKEN");
    return (Connection)jdbc(Connection.class,(proxy,m,a)->switch(m.getName()){
     case "close","setReadOnly","setAutoCommit","setTransactionIsolation","rollback"->null;
     case "createStatement"->jdbc(Statement.class,(sp,sm,sa)->switch(sm.getName()){
      case "close","setQueryTimeout"->null;
      case "executeQuery"->{String sql=(String)sa[0];boolean counts=sql.startsWith("SELECT (SELECT count(*)");boolean lease=sql.startsWith("SELECT count(*) FROM jt_terminal_session_leases");
       require(counts||lease||sql.startsWith("SELECT row_to_json(t)::text FROM "),"UNEXPECTED_SQL");
       int sequence=queries++;int[] row={0};
       yield jdbc(ResultSet.class,(rp,rm,ra)->switch(rm.getName()){
        case "close"->null;case "next"->row[0]++==0;
        case "getInt"->lease?0:(mode.equals("counts")?99:new int[]{3,4,3,4}[(int)ra[0]-1]);
        case "getString"->mode.equals("preview")&&sequence>=10?"changed": "stable";
        default->throw new AssertionError("UNEXPECTED_RESULT_CALL");});}
      default->throw new AssertionError("UNEXPECTED_STATEMENT_CALL");});
     default->throw new AssertionError("UNEXPECTED_CONNECTION_CALL");});
   }
   public DriverPropertyInfo[] getPropertyInfo(String u,Properties p){return new DriverPropertyInfo[0];}
   public int getMajorVersion(){return 1;}public int getMinorVersion(){return 0;}public boolean jdbcCompliant(){return false;}
   public java.util.logging.Logger getParentLogger(){return java.util.logging.Logger.getGlobal();}
  });
 }
 static void run(Path suite,String mode,String expectedStep,String expectedKind,int expectedHttp,String expectedSql,String expectedAssertion)throws Exception {
  String id=UUID.randomUUID().toString().replace("-","");Path root=suite.resolve(".tmp/p6iso/native-"+id);Files.createDirectories(root.resolve("secrets"));
  var queue=calls();var seen=new ArrayList<String>();var handlerFailure=new java.util.concurrent.atomic.AtomicBoolean();
  HttpServer server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
  server.createContext("/",exchange->{try{
   int index=seen.size();Call call=queue.get(index);String path=exchange.getRequestURI().getPath();
   require(call.method.equals(exchange.getRequestMethod())&&call.path.equals(path),"HTTP_SEQUENCE_CHANGED");
   String input=new String(exchange.getRequestBody().readAllBytes(),StandardCharsets.UTF_8);
   if(index>0)require(exchange.getRequestHeaders().getFirst("Authorization").equals("Bearer "+TOKEN),"MISSING_AUTH");
   JsonNode payload=input.isEmpty()?JSON.nullNode():JSON.readTree(input);
   if(index==0||index==2)require(payload.path("username").asText().equals("rehearsal-admin")&&payload.path("password").asText().equals((index==0?"I":"R").repeat(40)),"LOGIN_PAYLOAD_CHANGED");
   if(index==1)require(payload.path("currentPassword").asText().equals("I".repeat(40))&&payload.path("newPassword").asText().equals("R".repeat(40)),"ROTATION_CHANGED");
   if(path.equals("/api/vehicles"))require(payload.path("plateNumber").asText().equals("SYN-"+(char)('A'+index-3))&&payload.path("vehicleType").asText().equals("Microbus")&&payload.path("capacity").asInt()==12&&payload.path("currentStatus").asText().equals("IDLE")&&!payload.path("dispatchable").asBoolean()&&payload.path("fleetName").asText().equals("SYNTHETIC-REHEARSAL"),"VEHICLE_PAYLOAD_CHANGED");
   if(path.endsWith("/bind")){int terminal=(index-6)/5;require(payload.path("vehicleId").asText().equals(IDS[terminal<2?0:terminal-1])&&payload.path("expectedVersion").asInt()==1,"BINDING_CHANGED");}
   if(path.endsWith("/configuration")||path.endsWith("/configuration/preview")){int vehicle=(index-26)/3;require(payload.path("devices").size()==(vehicle==0?2:1)&&payload.path("expectedVersion").asInt()==1&&payload.path("operatingMode").asText().equals(vehicle==1?"SAFETY_MONITOR_ONLY":"DISPATCH_SERVICE"),"CONFIGURATION_CHANGED");}
   seen.add(path);int status=call.data==null?204:200;String body=call.data==null?"":"{\"data\":"+call.data+"}";
   if((mode.equals("login")&&index==0)||(mode.equals("rotate")&&index==1)){status=403;body=SECRET;}
   if(mode.equals("json")&&index==3)body=SECRET;
   if(mode.equals("version")&&index==7)body="{\"data\":{}}";
   byte[] bytes=body.getBytes(StandardCharsets.UTF_8);exchange.sendResponseHeaders(status,status==204?-1:bytes.length);if(status!=204)exchange.getResponseBody().write(bytes);
  }catch(Throwable t){handlerFailure.set(true);}finally{exchange.close();}});server.start();
  Properties marker=new Properties();marker.setProperty("SchemaVersion","1");marker.setProperty("RunId",id);marker.setProperty("OwnerNonce","a".repeat(64));marker.setProperty("RunDirectory",root.toString());marker.setProperty("PgPort","45101");
  try(var out=Files.newOutputStream(root.resolve("owner.properties"))){marker.store(out,"test only");}
  if(mode.equals("write"))Files.writeString(root.resolve("wire-stage.properties"),SECRET);
  Process child=null;
  try {
   var pb=new ProcessBuilder(Path.of(System.getProperty("java.home"),"bin/java.exe").toString(),"-cp",System.getProperty("java.class.path"),P6CompositeBusinessFlowTest.class.getName(),"child",mode);
   pb.directory(root.toFile());var env=pb.environment();env.clear();env.put("SystemRoot",System.getenv("SystemRoot"));
   env.put("P6_REHEARSAL_RUN_ID",id);env.put("P6_REHEARSAL_OWNER_NONCE",mode.equals("owner")?"b".repeat(64):"a".repeat(64));
   env.put("P6_REHEARSAL_API_PORT",""+server.getAddress().getPort());env.put("P6_REHEARSAL_GATEWAY_TCP_PORT","45103");env.put("P6_REHEARSAL_DB_PASSWORD","Q".repeat(40));env.put("P6_REHEARSAL_BOOTSTRAP_PASSWORD","I".repeat(40));env.put("P6_REHEARSAL_ROTATED_PASSWORD","R".repeat(40));env.put("P6_REHEARSAL_BUSINESS_ACTION",mode.equals("lease")?"LEASE_RELEASE":"PREPARE");
   child=pb.start();final Process held=child;
   var stdout=CompletableFuture.supplyAsync(()->{try{return held.getInputStream().readNBytes(8193);}catch(IOException e){throw new UncheckedIOException(e);}});
   var stderr=CompletableFuture.supplyAsync(()->{try{return held.getErrorStream().readNBytes(8193);}catch(IOException e){throw new UncheckedIOException(e);}});
   require(child.waitFor(30,TimeUnit.SECONDS),"CHILD_TIMEOUT");String text=new String(stdout.get(3,TimeUnit.SECONDS),StandardCharsets.UTF_8);
   require(stderr.get(3,TimeUnit.SECONDS).length==0&&!text.contains(SECRET)&&!text.contains(root.toString())&&!text.contains(TOKEN),"DIAGNOSTIC_LEAK");
   require(!handlerFailure.get(),"HTTP_CONTRACT_FAILED");
   if(expectedStep==null){require(child.exitValue()==0&&text.trim().equals("P6_BUSINESS_STATUS=PASS"),"SUCCESS_FLOW_CHANGED");
    if(!mode.equals("lease")){require(seen.size()==35&&Files.exists(root.resolve("wire.properties"))&&Files.exists(root.resolve("secrets/api-token.txt")),"INCOMPLETE_FLOW");JsonNode evidence=JSON.readTree(Files.readString(root.resolve("business-evidence.json")));require(evidence.path("PreviewComparisons").asInt()==3&&evidence.path("PreviewStable").asBoolean(),"EVIDENCE_CHANGED");}
   }else{require(child.exitValue()==1&&text.length()<=1024&&text.startsWith("{"),"STRUCTURED_FAILURE_MISSING_"+mode);JsonNode n=JSON.readTree(text);
    require(n.size()==8&&n.path("SchemaVersion").asInt()==1&&n.path("Status").asText().equals("FAIL"),"FAILURE_SHAPE");
    require(n.path("Code").asText().equals(expectedKind.equals("ASSERTION")?"REHEARSAL_BUSINESS_ASSERTION_FAILED":"REHEARSAL_BUSINESS_FAILED"),"HELPER_ERROR_CODE_CHANGED");
    require(n.path("Step").asText().equals(expectedStep)&&n.path("ExceptionKind").asText().equals(expectedKind)&&n.path("HttpStatus").asInt()==expectedHttp&&n.path("SqlState").asText().equals(expectedSql)&&n.path("AssertionId").asText().equals(expectedAssertion),"FAILURE_FIELDS_"+mode);
    require(!Files.exists(root.resolve("business-evidence.json")),"FAILED_FLOW_MARKED_COMPLETE");
   }
  }finally{if(child!=null&&child.isAlive()){child.destroyForcibly();require(child.waitFor(5,TimeUnit.SECONDS),"CHILD_RETAINED");}server.stop(0);}
 }
 public static void main(String[] args)throws Exception {
  if(args.length==2&&args[0].equals("child")){installDriver(args[1]);P6CompositeBusinessTool.main(new String[0]);return;}
  Path suite=Path.of(args[0]).toAbsolutePath();
  run(suite,"login","LOGIN","ASSERTION",403,"NONE","B010");
  run(suite,"rotate","ROTATE_PASSWORD","ASSERTION",403,"NONE","B010");
  run(suite,"json","CREATE_VEHICLE","JSON",200,"NONE","NONE");
  run(suite,"version","GET_TERMINAL","ASSERTION",200,"NONE","B016");
  run(suite,"sql","PREVIEW_BEFORE","SQL",0,"23505","NONE");
  run(suite,"sql_unknown","PREVIEW_BEFORE","SQL",0,"OTHER","NONE");
  run(suite,"preview","PREVIEW_COMPARE","ASSERTION",0,"NONE","B018");
  run(suite,"counts","VERIFY_COUNTS","ASSERTION",0,"NONE","B019");
  run(suite,"write","WRITE_WIRE","IO",0,"NONE","NONE");
  run(suite,"owner","OWNER_VALIDATE","ASSERTION",0,"NONE","B004");
  run(suite,"success",null,null,0,null,null);run(suite,"lease",null,null,0,null,null);
  System.out.println("BUSINESS_FLOW_TESTS=PASS COUNT=12 REAL_HELPER=true DEPENDENCIES=TEST_HTTP_JDBC");
 }
}
