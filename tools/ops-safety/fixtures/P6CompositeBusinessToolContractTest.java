public class P6CompositeBusinessToolContractTest {
 public static void main(String[] args) throws Exception {
  Class<?> c;
  try {c=Class.forName("P6CompositeBusinessTool");}catch(ClassNotFoundException e){throw new AssertionError("RED_BUSINESS_CONSUMER_MISSING");}
  var method=c.getDeclaredMethod("configuration",int.class,long.class);method.setAccessible(true);
  var json=new com.fasterxml.jackson.databind.ObjectMapper();
  var a=json.readTree(method.invoke(null,0,37L).toString());
  if(a.path("expectedVersion").asLong()!=37||a.path("devices").size()!=2||!a.path("operatingMode").asText().equals("DISPATCH_SERVICE"))throw new AssertionError("A_CONFIGURATION");
  var b=json.readTree(method.invoke(null,1,91L).toString());
  if(b.path("devices").size()!=1||!b.path("devices").get(0).path("terminalCode").asText().equals("SYN0003")||!b.path("operatingMode").asText().equals("SAFETY_MONITOR_ONLY"))throw new AssertionError("B_CONFIGURATION");
  var response=c.getDeclaredMethod("response",int.class,byte[].class);response.setAccessible(true);
  if(response.invoke(null,204,new byte[0])==null)throw new AssertionError("PASSWORD_204");
  String secret="DO_NOT_EXPORT_password_token_url_phone";
  P6CompositeBusinessTool.step(P6CompositeBusinessTool.Step.PREVIEW_BEFORE);
  var wrapped=json.readTree(P6CompositeBusinessTool.failure(new java.io.IOException(secret,new java.sql.SQLException(secret,"23505"))));
  if(!wrapped.path("ExceptionKind").asText().equals("SQL")||!wrapped.path("SqlState").asText().equals("23505")||wrapped.path("HttpStatus").asInt()!=0)throw new AssertionError("SQL_CAUSE_DIAGNOSTIC");
  var timeout=json.readTree(P6CompositeBusinessTool.failure(new java.net.http.HttpTimeoutException(secret)));
  if(!timeout.path("ExceptionKind").asText().equals("TIMEOUT")||timeout.toString().contains(secret))throw new AssertionError("TIMEOUT_DIAGNOSTIC");
  var unknown=json.readTree(P6CompositeBusinessTool.failure(new RuntimeException(secret)));
  if(!unknown.path("ExceptionKind").asText().equals("OTHER")||unknown.toString().contains(secret))throw new AssertionError("UNKNOWN_DIAGNOSTIC");
  var x=new RuntimeException(secret);var y=new RuntimeException(secret);x.initCause(y);y.initCause(x);
  if(P6CompositeBusinessTool.failure(x).contains(secret))throw new AssertionError("CYCLIC_CAUSE_LEAK");
  System.out.println("BUSINESS_CONTRACT=PASS COUNT=7");
 }
}
