import java.io.*;
import java.net.*;
import java.net.http.*;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.*;
import java.time.*;
import java.util.*;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;

/** 固定合成业务数据接线。无调用者SQL/URL/终端清单，秘密仅env及本轮私有目录。 */
public final class P6CompositeBusinessTool {
 static final ObjectMapper JSON=new ObjectMapper();
 static final String[] TABLES={"onboard_systems","onboard_system_runtime_state","onboard_device_memberships","onboard_device_capabilities","onboard_device_protocol_profiles","onboard_device_role_assignments","jt_terminals","vehicles","audit_logs","vehicle_location_events"};
 static final String REASON="isolated synthetic rehearsal";
 enum Step {START,ACTION,OWNER_READ,OWNER_VALIDATE,ENV_VALIDATE,PREPARE_CREDENTIALS,LOGIN,ROTATE_PASSWORD,RELOGIN,CREATE_VEHICLE,CREATE_TERMINAL,GET_TERMINAL,BIND_TERMINAL,VERIFY_CAPABILITY,GET_SYSTEM,PREVIEW_BEFORE,PREVIEW_REQUEST,PREVIEW_AFTER,PREVIEW_COMPARE,APPLY_CONFIGURATION,VERIFY_COUNTS,WRITE_WIRE,WRITE_TOKEN,WRITE_EVIDENCE,LEASE_RELEASE,COMPLETE}
 static final Set<String> SQL_STATES=Set.of("08001","08003","08004","08006","08007","08P01","22001","22003","22007","22P02","23502","23503","23505","23514","28000","28P01","3D000","3F000","40001","40P01","42501","42601","42703","42704","42883","42P01","53300","53400","57014","57P01","57P02","57P03","58030","XX000");
 static Step currentStep=Step.START;
 static int httpStatus;
 static void step(Step value){currentStep=value;httpStatus=0;}
 static final class BusinessAssertion extends IllegalStateException {
  final String id;
  BusinessAssertion(String id){super("REHEARSAL_BUSINESS_ASSERTION_FAILED");this.id=id;}
 }
 static String failure(Throwable error){
  String kind="OTHER",state="NONE",assertion="NONE",code="REHEARSAL_BUSINESS_FAILED";
  Throwable cursor=error;var seen=Collections.newSetFromMap(new IdentityHashMap<Throwable,Boolean>());
  for(int i=0;cursor!=null&&i<8&&seen.add(cursor);i++,cursor=cursor.getCause()){
   if(cursor instanceof BusinessAssertion a){kind="ASSERTION";assertion=a.id.matches("B00[1-9]|B01[0-9]|B02[01]")?a.id:"NONE";code="REHEARSAL_BUSINESS_ASSERTION_FAILED";break;}
   if(cursor instanceof SQLException s){kind="SQL";state=SQL_STATES.contains(s.getSQLState()==null?"":s.getSQLState())?s.getSQLState():"OTHER";break;}
   if(cursor instanceof com.fasterxml.jackson.core.JsonProcessingException){kind="JSON";break;}
   if(cursor instanceof HttpTimeoutException||cursor instanceof java.net.SocketTimeoutException)kind="TIMEOUT";
   else if(cursor instanceof IOException&&kind.equals("OTHER"))kind="IO";
   else if(cursor instanceof IllegalArgumentException&&kind.equals("OTHER"))kind="ARGUMENT";
  }
  // Only locally defined enums, identifiers and bounded numeric values enter the output.
  // Never serialize exception messages, class names, URLs, SQL, request/response bodies or causes.
  return "{\"SchemaVersion\":1,\"Status\":\"FAIL\",\"Code\":\""+code+"\",\"Step\":\""+currentStep.name()+"\",\"ExceptionKind\":\""+kind+"\",\"HttpStatus\":"+httpStatus+",\"SqlState\":\""+state+"\",\"AssertionId\":\""+assertion+"\"}";
 }
 final Map<String,String> env=System.getenv(); final Path root=Path.of("").toAbsolutePath().normalize();
 final Properties owner=new Properties(); String token="",base,jdbc,password,run;
 final HttpClient http=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).followRedirects(HttpClient.Redirect.NEVER).proxy(new ProxySelector(){public List<Proxy> select(URI u){return List.of(Proxy.NO_PROXY);}public void connectFailed(URI u,SocketAddress a,IOException e){}}).build();
 P6CompositeBusinessTool() throws Exception {
  step(Step.OWNER_READ);try(var in=Files.newInputStream(root.resolve("owner.properties"))){owner.load(in);}
  step(Step.OWNER_VALIDATE);run=env.get("P6_REHEARSAL_RUN_ID");
  check(run!=null&&run.matches("[a-f0-9]{32}")&&root.getFileName().toString().equals("native-"+run)&&root.getParent().getFileName().toString().equals("p6iso")&&root.getParent().getParent().getFileName().toString().equals(".tmp"),"B002");
  for(Path p=root;p!=null;p=p.getParent())check(!Files.isSymbolicLink(p)&&!Files.readAttributes(p,java.nio.file.attribute.BasicFileAttributes.class,LinkOption.NOFOLLOW_LINKS).isOther(),"B003");
  check(root.toString().equals(owner.getProperty("RunDirectory"))&&run.equals(owner.getProperty("RunId"))&&owner.getProperty("OwnerNonce").equals(env.get("P6_REHEARSAL_OWNER_NONCE")),"B004");
  step(Step.ENV_VALIDATE);int pg=Integer.parseInt(owner.getProperty("PgPort")),api=Integer.parseInt(env.get("P6_REHEARSAL_API_PORT"));check(pg>=1024&&api>=1024&&pg<=65535&&api<=65535&&pg!=api,"B005");
  base="http://127.0.0.1:"+api;jdbc="jdbc:postgresql://127.0.0.1:"+pg+"/composite_live";password=env.get("P6_REHEARSAL_DB_PASSWORD");check(password!=null&&password.matches("[A-Za-z0-9_-]{32,128}"),"B006");
 }
 static void check(boolean ok,String id){if(!ok)throw new BusinessAssertion(id);}
 Connection db() throws Exception {Properties p=new Properties();p.setProperty("user","composite");p.setProperty("password",password);p.setProperty("connectTimeout","3");p.setProperty("socketTimeout","10");return DriverManager.getConnection(jdbc,p);}
 JsonNode request(String method,String path,JsonNode body)throws Exception {
  check(path.startsWith("/api/"),"B007");var b=HttpRequest.newBuilder(URI.create(base+path)).timeout(Duration.ofSeconds(5)).header("Accept","application/json");
  if(!token.isEmpty())b.header("Authorization","Bearer "+token);
  if(method.equals("POST"))b.header("Content-Type","application/json").POST(HttpRequest.BodyPublishers.ofString(body.toString()));else b.GET();
  var r=http.send(b.build(),info->{httpStatus=info.statusCode();return new LimitedBody();});return response(r.statusCode(),r.body());
 }
 static JsonNode response(int status,byte[] bytes)throws Exception {httpStatus=status>=100&&status<=599?status:0;check(bytes.length<=65536&&status>=200&&status<300,"B010");if(status==204){check(bytes.length==0,"B011");return com.fasterxml.jackson.databind.node.NullNode.instance;}JsonNode n=JSON.readTree(bytes);check(n!=null&&n.has("data"),"B012");return n.get("data");}
 static final class LimitedBody implements HttpResponse.BodySubscriber<byte[]> {
  final HttpResponse.BodySubscriber<byte[]> delegate=HttpResponse.BodySubscribers.ofByteArray();java.util.concurrent.Flow.Subscription subscription;long size;
  public java.util.concurrent.CompletionStage<byte[]> getBody(){return delegate.getBody();}
  public void onSubscribe(java.util.concurrent.Flow.Subscription s){subscription=s;delegate.onSubscribe(s);}
  public void onNext(List<java.nio.ByteBuffer> buffers){for(var b:buffers)size+=b.remaining();if(size>65536){subscription.cancel();delegate.onError(new IOException("HTTP_LIMIT"));}else delegate.onNext(buffers);}
  public void onError(Throwable e){delegate.onError(e);}public void onComplete(){delegate.onComplete();}
 }
 static ObjectNode object(){return JSON.createObjectNode();}
 JsonNode get(String path)throws Exception{return request("GET",path,null);}
 JsonNode post(String path,JsonNode body)throws Exception{return request("POST",path,body);}
 Map<String,String> snapshot()throws Exception {
  Map<String,String> result=new TreeMap<>();
  try(var c=db()){c.setReadOnly(true);c.setAutoCommit(false);c.setTransactionIsolation(Connection.TRANSACTION_REPEATABLE_READ);
   for(String table:TABLES){var hash=MessageDigest.getInstance("SHA-256");long count=0;
    try(var s=c.createStatement()){s.setQueryTimeout(5);try(var r=s.executeQuery("SELECT row_to_json(t)::text FROM "+table+" t ORDER BY row_to_json(t)::text COLLATE \"C\"")){while(r.next()){byte[] bytes=r.getString(1).getBytes(StandardCharsets.UTF_8);hash.update(java.nio.ByteBuffer.allocate(4).putInt(bytes.length).array());hash.update(bytes);count++;}}}
    result.put(table,count+":"+HexFormat.of().formatHex(hash.digest()));
   }c.rollback();
  }return result;
 }
 static ObjectNode configuration(int vehicle,long version){
  ObjectNode n=object().put("expectedVersion",version).put("operatingMode",vehicle==1?"SAFETY_MONITOR_ONLY":"DISPATCH_SERVICE").put("reason",REASON);ArrayNode devices=n.putArray("devices");
  int first=vehicle==0?0:vehicle+1,last=vehicle==0?1:first;
  for(int i=first;i<=last;i++){boolean video=i==1||i==2;ObjectNode d=devices.addObject().put("terminalCode",String.format("SYN%04d",i+1)).put("networkMode","DIRECT_CELLULAR");
   ArrayNode roles=d.putArray("roles");roles.add(video?"VIDEO":"DISPATCH").add(i==1?"LOCATION_BACKUP":"LOCATION_PRIMARY");if(i!=1)roles.add("WAN_UPLINK");
   d.putObject("protocolProfiles").put("transportProfile","JT808_2013").put("businessProfile",video?"NONE":"VENDOR_DISPATCH").put("safetyProfile","NONE").put("mediaProfile",video?"JT1078_2016":"NONE").put("activePositionIntervalSeconds",10).put("idlePositionIntervalSeconds",60);
  }return n;
 }
 void prepare()throws Exception {
  step(Step.PREPARE_CREDENTIALS);String initial=env.get("P6_REHEARSAL_BOOTSTRAP_PASSWORD"),rotated=env.get("P6_REHEARSAL_ROTATED_PASSWORD");check(initial!=null&&rotated!=null&&!initial.equals(rotated),"B013");
  step(Step.LOGIN);token=post("/api/auth/login",object().put("username","rehearsal-admin").put("password",initial)).path("accessToken").asText();check(!token.isEmpty(),"B014");
  step(Step.ROTATE_PASSWORD);post("/api/auth/password",object().put("currentPassword",initial).put("newPassword",rotated));
  step(Step.RELOGIN);token=post("/api/auth/login",object().put("username","rehearsal-admin").put("password",rotated)).path("accessToken").asText();check(token.matches("[A-Za-z0-9_-]+\\.[A-Za-z0-9_-]+\\.[A-Za-z0-9_-]+"),"B015");
  List<String> vehicles=new ArrayList<>();
  for(char name='A';name<='C';name++){step(Step.CREATE_VEHICLE);JsonNode v=post("/api/vehicles",object().put("plateNumber","SYN-"+name).put("vehicleType","Microbus").put("capacity",12).put("currentStatus","IDLE").put("lng",116.318).put("lat",39.929).put("fleetName","SYNTHETIC-REHEARSAL").put("dispatchable",false).put("reason",REASON));String id=v.path("id").asText();UUID.fromString(id);vehicles.add(id);}
  for(int i=0;i<4;i++){String code=String.format("SYN%04d",i+1),path="/api/terminals/"+code;
   step(Step.CREATE_TERMINAL);post("/api/terminals",object().put("terminalPhone","13990000000"+(i+1)).put("terminalCode",code).put("manufacturerId","SIMMF").put("model","SIM-MODEL").put("protocolVersion","JT808_2013").put("sourceCoordinateSystem","WGS84").put("reason",REASON));
   step(Step.GET_TERMINAL);JsonNode terminal=get(path);check(terminal.path("version").isIntegralNumber(),"B016");step(Step.BIND_TERMINAL);post(path+"/bind",object().put("vehicleId",vehicles.get(i<2?0:i-1)).put("expectedVersion",terminal.path("version").asLong()).put("reason",REASON));
   for(String capability:List.of("JT808_LOCATION",i==1||i==2?"VIDEO":"VENDOR_DISPATCH")){ObjectNode b=object().put("capability",capability).put("reason","synthetic capability fixture only").put("evidenceRef","synthetic-rehearsal:"+run+":terminal-0"+(i+1)+":"+capability);b.putNull("expectedVersion");step(Step.VERIFY_CAPABILITY);post(path+"/capability-verifications",b);}
  }
  for(int i=0;i<3;i++){String path="/api/onboard-systems/"+vehicles.get(i);step(Step.GET_SYSTEM);JsonNode system=get(path);check(system.path("version").isIntegralNumber(),"B017");ObjectNode body=configuration(i,system.path("version").asLong());step(Step.PREVIEW_BEFORE);var before=snapshot();step(Step.PREVIEW_REQUEST);post(path+"/configuration/preview",body);step(Step.PREVIEW_AFTER);var after=snapshot();step(Step.PREVIEW_COMPARE);check(before.equals(after),"B018");step(Step.APPLY_CONFIGURATION);post(path+"/configuration",body);}
  step(Step.VERIFY_COUNTS);try(var c=db();var s=c.createStatement()){s.setQueryTimeout(5);try(var r=s.executeQuery("SELECT (SELECT count(*) FROM vehicles WHERE fleet_name='SYNTHETIC-REHEARSAL'),(SELECT count(*) FROM jt_terminals),(SELECT count(*) FROM onboard_systems WHERE status='ACTIVE'),(SELECT count(*) FROM onboard_device_memberships WHERE status='ACTIVE' AND valid_to IS NULL)")){check(r.next()&&r.getInt(1)==3&&r.getInt(2)==4&&r.getInt(3)==3&&r.getInt(4)==4,"B019");}}
  step(Step.WRITE_WIRE);Properties wire=new Properties();for(String key:List.of("SchemaVersion","RunId","OwnerNonce","RunDirectory"))wire.setProperty(key,owner.getProperty(key));wire.setProperty("CreatedAt",Instant.now().toString());wire.setProperty("ApiPort",env.get("P6_REHEARSAL_API_PORT"));wire.setProperty("GatewayTcpPort",env.get("P6_REHEARSAL_GATEWAY_TCP_PORT"));wire.setProperty("GatewayInstance","rehearsal-"+run);for(int i=0;i<3;i++)wire.setProperty("Vehicle"+(char)('A'+i)+"Id",vehicles.get(i));
  try(var out=Files.newOutputStream(root.resolve("wire-stage.properties"),StandardOpenOption.CREATE_NEW)){wire.store(out,"synthetic only");}Files.move(root.resolve("wire-stage.properties"),root.resolve("wire.properties"),StandardCopyOption.ATOMIC_MOVE);
  step(Step.WRITE_TOKEN);Files.writeString(root.resolve("secrets/api-token.txt"),token,StandardOpenOption.CREATE_NEW);
  step(Step.WRITE_EVIDENCE);Files.writeString(root.resolve("business-evidence.json"),"{\"Vehicles\":3,\"Terminals\":4,\"Systems\":3,\"Memberships\":4,\"PreviewComparisons\":3,\"TablesPerComparison\":10,\"PreviewStable\":true,\"PasswordRotatedAndRelogin\":true}",StandardOpenOption.CREATE_NEW);
 }
 void released()throws Exception {step(Step.LEASE_RELEASE);long end=System.nanoTime()+Duration.ofSeconds(190).toNanos();while(true){try(var c=db();var s=c.createStatement()){s.setQueryTimeout(5);try(var r=s.executeQuery("SELECT count(*) FROM jt_terminal_session_leases l JOIN jt_terminals t ON t.id=l.terminal_id WHERE t.terminal_code IN ('SYN0001','SYN0002','SYN0003','SYN0004') AND l.released_at IS NULL AND l.expires_at > now()")){check(r.next(),"B020");if(r.getInt(1)==0)return;}}check(System.nanoTime()<end,"B021");Thread.sleep(500);}}
 public static void main(String[] args){PrintStream out=System.out;System.setOut(new PrintStream(OutputStream.nullOutputStream()));System.setErr(new PrintStream(OutputStream.nullOutputStream()));try{step(Step.START);check(args.length==0,"B001");var tool=new P6CompositeBusinessTool();step(Step.ACTION);switch(tool.env.getOrDefault("P6_REHEARSAL_BUSINESS_ACTION","")){case "PREPARE"->tool.prepare();case "LEASE_RELEASE"->tool.released();default->throw new IllegalStateException();}step(Step.COMPLETE);out.println("P6_BUSINESS_STATUS=PASS");}catch(Throwable e){out.println(failure(e));System.exit(1);}}
}
