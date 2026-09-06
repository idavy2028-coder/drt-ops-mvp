import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.net.*;
import java.io.*;
import java.util.concurrent.*;
import com.sun.net.httpserver.HttpServer;
import java.sql.*;

/** fake仅代替网络/数据库，真实run控制顺序、验证、失败停止与文件发布。 */
public final class P6CompositeWireHarnessContractTest {
    static int total, passed;
    static final Instant START=Instant.now().minusSeconds(2);
    static final List<UUID> VEHICLES=List.of(id(11),id(12),id(13));
    static UUID id(int n) { return new UUID(1,n); }
    static void check(boolean value,String code) { if(!value) throw new AssertionError(code); }
    interface Test { void run() throws Exception; }
    static void test(String name,Test body) {
        total++;
        try { body.run(); passed++; } catch(Throwable error) {
            String code=error instanceof AssertionError?error.getMessage():error instanceof P6CompositeWireHarness.SafeFailure?error.getMessage():"CONTRACT_FAILED_"+error.getClass().getSimpleName();
            if(error instanceof ExecutionException&&error.getCause() instanceof AssertionError)code=error.getCause().getMessage();
            System.out.println("FAIL="+name+":"+(code.matches("[A-Za-z0-9_]+")?code:"CONTRACT_FAILED"));
        }
    }
    static class Fake implements P6CompositeWireHarness.Boundary {
        final List<String> events=new ArrayList<>();
        final List<P6CompositeWireHarness.Mapping> mappings=new ArrayList<>();
        final List<P6CompositeWireHarness.Lease> leases=new ArrayList<>();
        final boolean[] open=new boolean[4];
        String fail="", bad="";
        Fake() {
            for(int i=0;i<4;i++) {
                int v=i<2?0:i-1;
                mappings.add(new P6CompositeWireHarness.Mapping(id(i+1),VEHICLES.get(v),id(21+v)));
                leases.add(new P6CompositeWireHarness.Lease(id(31+i),"rehearsal-test",START.plusSeconds(1),Instant.now().plusSeconds(90),false));
            }
        }
        void event(String value) { events.add(value); if(value.equals(fail)) throw new IllegalStateException("SYNTHETIC_SECRET_MUST_NOT_ESCAPE"); }
        public List<P6CompositeWireHarness.Mapping> expected() { event("expected"); return List.copyOf(mappings); }
        public P6CompositeWireHarness.Wire terminal(int i) {
            event("new"+i);
            return new P6CompositeWireHarness.Wire() {
                int last,serial;
                public void connect() { event("connect"+i);open[i]=true; }
                public int send(int message) {last=message;event("send"+i+":"+message);return ++serial;}
                public P6CompositeWireHarness.Reply reply() {
                    event("reply"+i+":"+last);
                    return new P6CompositeWireHarness.Reply(last==256?0x8100:0x8001,last,
                        bad.equals("serial")?serial+1:serial,bad.equals("result")?1:0);
                }
                public boolean live() {event("live"+i);return open[i]&&!bad.equals("closed");}
                public void close() {open[i]=false;event("close"+i);}
            };
        }
        final int[] gets=new int[4];
        P6CompositeWireHarness.TerminalState state(boolean active) {
            return new P6CompositeWireHarness.TerminalState(active?"ACTIVE":"PENDING",!bad.equals("registration"),73,START.plusSeconds(1),START.plusSeconds(1));
        }
        public P6CompositeWireHarness.TerminalState terminalState(int i) {event("get"+i);return state(gets[i]++>0);}
        public P6CompositeWireHarness.TerminalState activate(int i,long version) {check(version==73,"VERSION_NOT_READ");event("activate"+i);return state(true);}
        public void onboard(int i,P6CompositeWireHarness.Mapping mapping) {check(Arrays.equals(open,new boolean[]{true,true,true,true}),"SOCKETS_CLOSED_EARLY");event("onboard"+i);}
        public P6CompositeWireHarness.Evidence evidence() {event("evidence");return new P6CompositeWireHarness.Evidence(List.copyOf(mappings),List.copyOf(leases));}
    }
    static Path directory() throws Exception {return Files.createTempDirectory(Path.of(System.getProperty("wire.test.root")),"case-");}
    static P6CompositeWireHarness.Outcome run(Fake f,Path directory) {
        return P6CompositeWireHarness.run(f,VEHICLES,"rehearsal-test",START,directory);
    }
    static List<String> sequence() {
        List<String> e=new ArrayList<>(List.of("expected"));
        for(int i=0;i<4;i++) {e.add("new"+i);e.add("connect"+i);}
        for(int i=0;i<4;i++) e.addAll(List.of("send"+i+":256","reply"+i+":256","get"+i,"activate"+i,"send"+i+":258","reply"+i+":258","send"+i+":2","reply"+i+":2"));
        for(int i=0;i<4;i++) e.addAll(List.of("get"+i,"onboard"+i));
        e.add("evidence");
        for(int i=0;i<4;i++) e.add("live"+i);
        return e;
    }
    static Map<String,String> environment(Path p) {
        Map<String,String> e=new HashMap<>();
        e.put("P6_REHEARSAL_RUN_ID","1".repeat(32));e.put("P6_REHEARSAL_OWNER_NONCE","a".repeat(64));
        e.put("P6_REHEARSAL_RUN_DIRECTORY",p.toString());e.put("P6_REHEARSAL_JDBC_URL","jdbc:postgresql://127.0.0.1:45431/composite_live");
        e.put("P6_REHEARSAL_DB_USER","composite");e.put("P6_REHEARSAL_DB_PASSWORD","x".repeat(40));
        e.put("P6_REHEARSAL_API_BASE_URL","http://127.0.0.1:45432");e.put("P6_REHEARSAL_GATEWAY_TCP_PORT","45433");
        e.put("P6_REHEARSAL_GATEWAY_INSTANCE","rehearsal-"+"1".repeat(32));e.put("P6_REHEARSAL_API_TOKEN","synthetic.token.signature");
        for(int i=0;i<3;i++)e.put("P6_REHEARSAL_VEHICLE_"+(char)('A'+i)+"_ID",VEHICLES.get(i).toString());
        return e;
    }
    static Properties owner(Map<String,String> e) {
        Properties p=new Properties();p.setProperty("SchemaVersion","1");p.setProperty("RunId",e.get("P6_REHEARSAL_RUN_ID"));
        p.setProperty("OwnerNonce",e.get("P6_REHEARSAL_OWNER_NONCE"));p.setProperty("RunDirectory",e.get("P6_REHEARSAL_RUN_DIRECTORY"));
        p.setProperty("CreatedAt",START.toString());p.setProperty("PgPort","45431");return p;
    }
    static Properties marker(Map<String,String> e) {
        Properties p=owner(e);p.remove("PgPort");p.setProperty("ApiPort","45432");p.setProperty("GatewayTcpPort","45433");p.setProperty("GatewayInstance",e.get("P6_REHEARSAL_GATEWAY_INSTANCE"));
        for(int i=0;i<3;i++)p.setProperty("Vehicle"+(char)('A'+i)+"Id",VEHICLES.get(i).toString());return p;
    }
    // M1: retain the real run/finally and HTTP parser; only wire/JDBC are synthetic.
    static void onboardHttpTests() throws Exception {
        for(String shape:List.of("valid","membership","capability","unauthenticated","roles","profile")) test("http_onboard_actual_parser_"+shape,()->{
            HttpServer server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
            List<String> requests=Collections.synchronizedList(new ArrayList<>());
            var json=new com.fasterxml.jackson.databind.ObjectMapper();
            Fake f=new Fake() {
                P6CompositeWireHarness.Live live;
                public void onboard(int i,P6CompositeWireHarness.Mapping mapping) {
                    super.onboard(i,mapping);
                    try {
                        if(live==null)live=new P6CompositeWireHarness.Live(new P6CompositeWireHarness.Config(directory(),"http://127.0.0.1:"+server.getAddress().getPort(),45433,"","","synthetic.token.signature","rehearsal-test",VEHICLES,START));
                        live.onboard(i,mapping);
                    } catch(RuntimeException e){throw e;} catch(Exception e){throw new IllegalStateException(e);}
                }
            };
            server.createContext("/",x->{
                try {
                    String route=x.getRequestURI().getPath();requests.add(x.getRequestMethod()+" "+route);
                    check(x.getRequestMethod().equals("GET")&&"Bearer synthetic.token.signature".equals(x.getRequestHeaders().getFirst("Authorization")),"ONBOARD_HTTP_CONTRACT");
                    var data=json.createObjectNode();
                    if(route.startsWith("/api/terminals/")) {
                        int i=Integer.parseInt(route.substring(route.length()-1))-1;
                        var m=f.mappings.get(i);var member=data.putObject("currentOnboardMembership");
                        member.put("onboardSystemId",(shape.equals("membership")?id(99):m.system()).toString());
                        member.put("vehicleId",m.vehicle().toString());member.put("status","ACTIVE");
                    } else {
                        int v=VEHICLES.indexOf(UUID.fromString(route.substring("/api/onboard-systems/".length())));
                        check(v>=0,"ONBOARD_WRONG_ROUTE");data.put("onboardSystemId",id(21+v).toString());data.put("vehicleId",VEHICLES.get(v).toString());
                        data.put("status","ACTIVE");data.put("operatingMode",v==1?"SAFETY_MONITOR_ONLY":"DISPATCH_SERVICE");
                        var devices=data.putArray("devices");
                        for(int i:(v==0?new int[]{0,1}:new int[]{v+1})) {
                            boolean video=i==1||i==2;var d=devices.addObject();
                            // Independent literal alias policy is the API contract: SHA256(UUID) first 12.
                            String hash=java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(id(i+1).toString().getBytes(java.nio.charset.StandardCharsets.UTF_8)));
                            d.put("deviceAlias","device-"+hash.substring(0,12));d.put("currentlyAuthenticated",!shape.equals("unauthenticated"));d.put("terminalStatus","ACTIVE");d.put("networkMode","DIRECT_CELLULAR");
                            var roles=d.putArray("roles");for(String r:(i==1?List.of("VIDEO","LOCATION_BACKUP"):i==2?List.of("VIDEO","LOCATION_PRIMARY","WAN_UPLINK"):List.of("DISPATCH","LOCATION_PRIMARY","WAN_UPLINK")))roles.add(r);
                            if(shape.equals("roles"))roles.removeAll();
                            var p=d.putObject("protocolProfiles");p.put("transportProfile","JT808_2013");p.put("businessProfile",video?"NONE":"VENDOR_DISPATCH");p.put("safetyProfile","NONE");p.put("mediaProfile",video?"JT1078_2016":"NONE");p.put("activePositionIntervalSeconds",shape.equals("profile")?11:10);p.put("idlePositionIntervalSeconds",60);
                            var capabilities=d.putArray("verifiedCapabilities");capabilities.add("JT808_LOCATION");if(!shape.equals("capability"))capabilities.add(video?"VIDEO":"VENDOR_DISPATCH");
                            d.put("lastRegisteredAt",START.plusSeconds(1).toString());d.put("lastAuthenticatedAt",START.plusSeconds(1).toString());
                        }
                    }
                    var envelope=json.createObjectNode();envelope.set("data",data);byte[] bytes=json.writeValueAsBytes(envelope);x.sendResponseHeaders(200,bytes.length);x.getResponseBody().write(bytes);
                } catch(Exception e){throw new IOException("FIXTURE_FAILED");} finally{x.close();}
            });server.start();
            Path output=directory();
            try {
                var result=run(f,output);
                check(result.passed()==shape.equals("valid"),"ONBOARD_PARSER_RESULT");
                check(Arrays.equals(f.open,new boolean[4]),"ONBOARD_FAILURE_NOT_CLOSED");
                check(Files.exists(output.resolve("acceptance"))==shape.equals("valid"),"ONBOARD_FAILURE_PUBLISHED");
                if(shape.equals("valid"))check(requests.size()==8&&requests.get(0).equals("GET /api/terminals/SYN0001")&&requests.get(7).equals("GET /api/onboard-systems/"+VEHICLES.get(2)),"ONBOARD_ROUTES_MISSING");
                else check(!f.events.contains("evidence")&&f.events.subList(f.events.size()-4,f.events.size()).equals(List.of("close3","close2","close1","close0")),"ONBOARD_FAILURE_CONTINUED");
            } finally{server.stop(0);}
        });
    }
    static void adapterTests() throws Exception {
        test("environment_owned_fixed_endpoints",()->{
            Map<String,String> e=environment(directory());
            var c=P6CompositeWireHarness.validateEnvironment(e,owner(e),marker(e));check(c.vehicles().equals(VEHICLES)&&c.tcpPort()==45433,"VALID_ENVIRONMENT_REFUSED");
        });
        for(String key:environment(Path.of("D:/synthetic")).keySet()) test("environment_drift_"+key,()->{
            Map<String,String> e=environment(directory());Properties o=owner(e),m=marker(e);e.put(key,"SYNTHETIC_SECRET");
            try {P6CompositeWireHarness.validateEnvironment(e,o,m);throw new AssertionError("DRIFT_ACCEPTED");}
            catch(P6CompositeWireHarness.SafeFailure safe){check(!safe.getMessage().contains("SECRET"),"ENVIRONMENT_LEAK");}
        });
        test("http_real_client_pending_and_real_version_activation",()->{
            HttpServer server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
            List<String> requests=new ArrayList<>();
            server.createContext("/",x->{
                try {
                    requests.add(x.getRequestMethod()+" "+x.getRequestURI());
                    String body=new String(x.getRequestBody().readAllBytes(),java.nio.charset.StandardCharsets.UTF_8);
                    check("Bearer synthetic.token.signature".equals(x.getRequestHeaders().getFirst("Authorization")),"HTTP_AUTH_MISSING");
                    boolean post=x.getRequestMethod().equals("POST");
                    if(post)check(body.equals("{\"expectedVersion\":73,\"reason\":\"isolated synthetic rehearsal\"}"),"ACTIVATION_VERSION_NOT_READ");
                    String data="{\"data\":{\"terminalCode\":\"SYN0001\",\"status\":\""+(post?"ACTIVE":"PENDING")+"\",\"registrationCompleted\":true,\"version\":73,\"lastRegisteredAt\":\""+START.plusSeconds(1)+"\"}}";
                    byte[] bytes=data.getBytes(java.nio.charset.StandardCharsets.UTF_8);x.sendResponseHeaders(200,bytes.length);x.getResponseBody().write(bytes);
                } finally {x.close();}
            });server.start();
            try {
                var c=new P6CompositeWireHarness.Config(directory(),"http://127.0.0.1:"+server.getAddress().getPort(),45433,"", "","synthetic.token.signature","rehearsal-test",VEHICLES,START);
                var live=new P6CompositeWireHarness.Live(c);var pending=live.terminalState(0);check(pending.version()==73&&pending.status().equals("PENDING"),"HTTP_PENDING_MISSING");
                check(live.activate(0,pending.version()).status().equals("ACTIVE"),"HTTP_ACTIVATE_MISSING");
                check(requests.equals(List.of("GET /api/terminals/SYN0001","POST /api/terminals/SYN0001/activate")),"HTTP_WRONG_ROUTE");
            } finally {server.stop(0);}
        });
        test("tcp_real_simulator_registration_token_auth_heartbeat_and_close",()->{
            try(ServerSocket server=new ServerSocket(0,1,InetAddress.getByName("127.0.0.1"))) {
                server.setSoTimeout(5000);ExecutorService executor=Executors.newSingleThreadExecutor();
                java.util.concurrent.atomic.AtomicInteger peerStage=new java.util.concurrent.atomic.AtomicInteger();
                Future<Boolean> peer=executor.submit(()->{
                    int stage=0;
                    try(Socket socket=server.accept()) {
                        peerStage.set(1);
                        socket.setSoTimeout(5000);InputStream in=socket.getInputStream();OutputStream out=socket.getOutputStream();
                        for(int message:new int[]{0x0100,0x0102,0x0002}) {
                            stage=message;
                            byte[] request=readFrame(in);int actual=((request[0]&255)<<8)|(request[1]&255);check(actual==message,"WRONG_TCP_SEQUENCE");
                            peerStage.set(2);
                            if(message==0x0102)check(Arrays.equals(Arrays.copyOfRange(request,12,request.length-1),new byte[]{65,66,67}),"TOKEN_NOT_REUSED");
                            if(message==0x0100)check(new String(request,41,7,java.nio.charset.StandardCharsets.US_ASCII).equals("SYN0001"),"WRONG_SYNTHETIC_TERMINAL");
                            byte[] body=message==0x0100?new byte[]{request[10],request[11],0,65,66,67}:new byte[]{request[10],request[11],(byte)(message>>8),(byte)message,0};
                            sendFrame(out,request,message==0x0100?0x8100:0x8001,body);
                            peerStage.set(3);
                        }
                        return in.read()==-1;
                    } catch(SocketTimeoutException timeout){throw new AssertionError("TCP_FIXTURE_TIMEOUT_"+stage);}
                });
                try {
                    var c=new P6CompositeWireHarness.Config(directory(),"",server.getLocalPort(),"","","","rehearsal-test",VEHICLES,START);
                    try(var wire=new P6CompositeWireHarness.Live(c).terminal(0)) {
                        wire.connect();
                        for(int m:new int[]{256,258,2}) {
                            int serial=wire.send(m);var reply=wire.reply();if(reply==null&&peer.isDone())peer.get();check(reply!=null,"TCP_REPLY_MISSING_"+m+"_PEER_"+peerStage.get());
                            check(reply.message()==(m==256?0x8100:0x8001)&&reply.request()==m&&reply.serial()==serial&&reply.result()==0,"TCP_REPLY_INVALID_"+m);
                        }
                        check(wire.live(),"REAL_SOCKET_CLOSED_EARLY");
                    }
                    check(peer.get(8,TimeUnit.SECONDS),"REAL_SOCKET_NOT_CLOSED");
                } finally {executor.shutdownNow();}
            }
        });
        test("jdbc_fixed_prepared_readonly_mapping_and_live_evidence",()->{
            Fake f=new Fake();Db db=new Db(f);var config=new P6CompositeWireHarness.Config(directory(),"",45433,"","","","rehearsal-test",VEHICLES,START);
            var live=new P6CompositeWireHarness.Live(config,db::connection);
            check(live.expected().equals(f.mappings),"JDBC_MAPPING_WRONG");db.active=true;
            var evidence=live.evidence();check(evidence.mappings().equals(f.mappings)&&evidence.leases().equals(f.leases),"JDBC_LEASE_WRONG");
            check(db.closed==2&&db.queries==2,"JDBC_RESOURCE_LEAK");
        });
        test("fragmented_reply_missing_is_fail_closed_without_publication",()->{
            try(ServerSocket server=new ServerSocket(0,1,InetAddress.getByName("127.0.0.1"))) {
                server.setSoTimeout(7000);ExecutorService executor=Executors.newSingleThreadExecutor();
                Future<Boolean> peer=executor.submit(()->{
                    try(Socket socket=server.accept()) {
                        socket.setSoTimeout(7000);byte[] request=readFrame(socket.getInputStream());
                        ByteArrayOutputStream reply=new ByteArrayOutputStream();sendFrame(reply,request,0x8100,new byte[]{request[10],request[11],0,65,66,67});
                        byte[] bytes=reply.toByteArray();socket.getOutputStream().write(bytes,0,1);socket.getOutputStream().flush();
                        Thread.sleep(100);socket.getOutputStream().write(bytes,1,bytes.length-1);socket.getOutputStream().flush();
                        return socket.getInputStream().read()==-1;
                    }
                });
                try {
                    Path p=directory();var config=new P6CompositeWireHarness.Config(p,"",server.getLocalPort(),"","","","rehearsal-test",VEHICLES,START);
                    Fake f=new Fake(){@Override public P6CompositeWireHarness.Wire terminal(int i){return i==0?new P6CompositeWireHarness.Live(config).terminal(i):super.terminal(i);}};
                    var outcome=run(f,p);
                    check(!outcome.passed()&&outcome.step().equals("REGISTER")&&outcome.code().equals("REHEARSAL_WIRE_REPLY_INVALID")&&!Files.exists(p.resolve("acceptance")),"FRAGMENT_FAILURE_NOT_CLOSED");
                    check(Arrays.equals(f.open,new boolean[4])&&peer.get(8,TimeUnit.SECONDS),"FRAGMENT_SOCKET_LEAK");
                } finally {executor.shutdownNow();}
            }
        });
        test("jdbc_read_failure_closes_and_never_returns_evidence",()->{
            Db db=new Db(new Fake());db.fail=true;var config=new P6CompositeWireHarness.Config(directory(),"",45433,"","","","rehearsal-test",VEHICLES,START);
            try {new P6CompositeWireHarness.Live(config,db::connection).expected();throw new AssertionError("JDBC_FAILURE_IGNORED");}catch(SQLException expected){check(db.closed==1,"JDBC_FAILURE_LEAK");}
        });
    }
    static class Db {
        final Fake fixture;boolean active,fail;int closed,queries;
        Db(Fake fixture){this.fixture=fixture;}
        Connection connection(){
            boolean[] readonly={false};
            return (Connection)java.lang.reflect.Proxy.newProxyInstance(getClass().getClassLoader(),new Class<?>[]{Connection.class},(p,m,a)->{
                switch(m.getName()) {
                    case "setReadOnly":check(Boolean.TRUE.equals(a[0]),"JDBC_NOT_READONLY");readonly[0]=true;return null;
                    case "close":closed++;return null;
                    case "prepareStatement":
                        String sql=(String)a[0];check(sql.startsWith("SELECT ")&&sql.contains("WHERE t.terminal_code IN (?,?,?,?) ORDER BY t.terminal_code")&&sql.contains("m.status='ACTIVE' AND m.valid_to IS NULL")&&sql.contains("s.status='ACTIVE'")&&!sql.contains(";")&&!sql.contains("UPDATE"),"ARBITRARY_OR_UNSCOPED_SQL");
                        Map<Integer,String> parameters=new HashMap<>();boolean[] timeout={false},max={false};
                        return java.lang.reflect.Proxy.newProxyInstance(getClass().getClassLoader(),new Class<?>[]{PreparedStatement.class},(sp,sm,sa)->{
                            switch(sm.getName()) {
                                case "setQueryTimeout":check((int)sa[0]>0&&(int)sa[0]<=5,"JDBC_UNBOUNDED");timeout[0]=true;return null;
                                case "setMaxRows":check((int)sa[0]==5,"JDBC_UNBOUNDED_ROWS");max[0]=true;return null;
                                case "setString":parameters.put((int)sa[0],(String)sa[1]);return null;
                                case "close":return null;
                                case "executeQuery":
                                    check(readonly[0]&&timeout[0]&&max[0]&&parameters.equals(Map.of(1,"SYN0001",2,"SYN0002",3,"SYN0003",4,"SYN0004")),"JDBC_WRONG_BOUNDARY");queries++;if(fail)throw new SQLException("SYNTHETIC_SECRET");
                                    int[] cursor={-1};return java.lang.reflect.Proxy.newProxyInstance(getClass().getClassLoader(),new Class<?>[]{ResultSet.class},(rp,rm,ra)->{
                                        if(rm.getName().equals("next"))return ++cursor[0]<4;if(rm.getName().equals("close"))return null;
                                        int i=cursor[0],v=i<2?0:i-1;var mapping=fixture.mappings.get(i);var lease=fixture.leases.get(i);String column=(String)ra[0];
                                        return switch(column){
                                            case "terminal_id"->mapping.terminal().toString();case "vehicle_id"->mapping.vehicle().toString();case "system_id"->mapping.system().toString();
                                            case "terminal_code"->"SYN000"+(i+1);case "terminal_phone"->"13990000000"+(i+1);case "manufacturer_id"->"SIMMF";case "model"->"SIM-MODEL";case "protocol_version"->"JT808_2013";
                                            case "status"->active?"ACTIVE":"PENDING";case "plate_number"->"SYN-"+(char)('A'+v);case "dispatchable"->false;case "fleet_name"->"SYNTHETIC-REHEARSAL";
                                            case "created_at"->START.plusSeconds(1).atOffset(ZoneOffset.UTC);
                                            case "last_registered_at","last_authenticated_at"->active?START.plusSeconds(1).atOffset(ZoneOffset.UTC):null;
                                            case "connection_id"->active?lease.connection().toString():null;case "gateway_instance"->lease.gateway();case "authenticated_at"->lease.authenticatedAt().atOffset(ZoneOffset.UTC);case "expires_at"->lease.expiresAt().atOffset(ZoneOffset.UTC);
                                            case "released_at"->null;case "live","token_current"->true;
                                            default->throw new AssertionError("UNEXPECTED_JDBC_COLUMN");
                                        };
                                    });
                                default:throw new AssertionError("UNEXPECTED_STATEMENT_OPERATION");
                            }
                        });
                    default:throw new AssertionError("UNEXPECTED_CONNECTION_OPERATION");
                }
            });
        }
    }
    static byte[] readFrame(InputStream in) throws Exception {
        check(in.read()==126,"FRAME_START");ByteArrayOutputStream bytes=new ByteArrayOutputStream();int b;
        while((b=in.read())!=126) {check(b>=0,"FRAME_EOF");if(b==125){int escape=in.read();check(escape==1||escape==2,"FRAME_ESCAPE");b=escape==1?125:126;}bytes.write(b);check(bytes.size()<1024,"FRAME_TOO_BIG");}
        byte[] data=bytes.toByteArray();int checksum=0;for(byte value:data)checksum^=value&255;check(checksum==0&&data.length>=13,"FRAME_CHECKSUM");return data;
    }
    static void sendFrame(OutputStream out,byte[] request,int message,byte[] body) throws Exception {
        byte[] raw=new byte[13+body.length];raw[0]=(byte)(message>>8);raw[1]=(byte)message;raw[3]=(byte)body.length;System.arraycopy(request,4,raw,4,6);raw[11]=1;System.arraycopy(body,0,raw,12,body.length);
        for(int i=0;i<raw.length-1;i++)raw[raw.length-1]^=raw[i];
        ByteArrayOutputStream encoded=new ByteArrayOutputStream();encoded.write(126);for(byte value:raw){int b=value&255;if(b==125||b==126){encoded.write(125);encoded.write(b==125?1:2);}else encoded.write(b);}encoded.write(126);
        var decoder=new io.netty.channel.embedded.EmbeddedChannel(new com.idavy.drtops.jt.protocol.codec.Jt808FrameDecoder());
        try {
            check(decoder.writeInbound(io.netty.buffer.Unpooled.wrappedBuffer(encoded.toByteArray())),"FIXTURE_REPLY_NOT_DECODED");
            com.idavy.drtops.jt.protocol.codec.Jt808Frame frame=decoder.readInbound();
            try{check(frame.header().messageId()==message&&frame.body().readableBytes()==body.length,"FIXTURE_REPLY_WRONG_HEADER");}finally{frame.body().release();}
        } catch(RuntimeException invalid){throw new AssertionError("FIXTURE_REPLY_CODEC_FAILED");}
        finally{decoder.finishAndReleaseAll();}
        out.write(encoded.toByteArray());out.flush();
    }
    static void directoryContractTests() {
        for(String shape:List.of("short","old","wrong_run","too_long","generated_240","generated_241"))test("c2_D1_directory_"+shape,()->{
            Path base=Path.of("D:/synthetic-repo");
            String run="1".repeat(32);
            Path p=base.resolve(".tmp/p6iso/native-"+run);
            if(shape.equals("old"))p=base.resolve(".superpowers/sdd/2026-09-06-p6-2-local-isolation-rehearsal/native-"+run);
            if(shape.equals("wrong_run"))p=base.resolve(".tmp/p6iso/native-"+"2".repeat(32));
            if(shape.equals("too_long"))p=base.resolve("a".repeat(200)).resolve(".tmp/p6iso/native-"+run);
            if(shape.startsWith("generated_")) {
                int budget=shape.equals("generated_240")?240:241;
                int padding=budget-base.resolve("x/.tmp/p6iso/native-"+run+"/.wire-stage/expected.json").toString().length()+1;
                p=base.resolve("a".repeat(padding)).resolve(".tmp/p6iso/native-"+run);
                check(p.resolve(".wire-stage/expected.json").toString().length()==budget,"PATH_FIXTURE_LENGTH_INVALID");
            }
            Map<String,String> env=environment(p);
            boolean accepted=true;
            try{P6CompositeWireHarness.validateRunDirectory(p,env);}catch(P6CompositeWireHarness.SafeFailure rejected){accepted=false;}
            check(accepted==(shape.equals("short")||shape.equals("generated_240")),"SHORT_ROOT_CONTRACT_MISSING");
        });
    }
    public static void main(String[] args) throws Exception {
        String group=args.length==0?"ALL":args.length==1?args[0]:"INVALID";
        if(!List.of("ALL","CORE","ONBOARD","ADAPTERS","DIRECTORY").contains(group)) {
            System.out.println("P6_WIRE_GROUP_INVALID");System.exit(2);return;
        }
        // 固定互斥分区：原core 63、HTTP onboard 6、其余adapter 19；无参仍按原顺序全跑88。
        if(group.equals("ALL")||group.equals("CORE")) {
        test("success_exact_order_and_atomic_files",()->{
            Fake f=new Fake();Path p=directory();var result=run(f,p);
            check(result.passed(),"WIRE_STATE_MACHINE_MISSING");
            List<String> expected=sequence();for(int i=3;i>=0;i--) expected.add("close"+i);
            check(f.events.equals(expected),"SIDE_EFFECT_ORDER");
            check(Files.isRegularFile(p.resolve("acceptance/expected.json"))&&Files.isRegularFile(p.resolve("acceptance/results.json")),"ATOMIC_PAIR_MISSING");
            check(!Files.exists(p.resolve(".wire-stage")),"STAGING_RETAINED");
            var parser=new com.fasterxml.jackson.databind.ObjectMapper();
            for(String file:List.of("expected.json","results.json")) {
                var rows=parser.readTree(Files.readString(p.resolve("acceptance/"+file)));check(rows.isArray()&&rows.size()==4,"OUTPUT_CARDINALITY");
                for(int i=0;i<4;i++) {
                    var row=rows.get(i);Set<String> fields=new HashSet<>();row.fieldNames().forEachRemaining(fields::add);
                    Set<String> expectedFields=new HashSet<>(Set.of("SafeAlias","TerminalId","VehicleId","OnboardSystemId"));if(file.equals("results.json"))expectedFields.add("Status");
                    check(fields.equals(expectedFields)&&row.path("SafeAlias").asText().equals("terminal-0"+(i+1)),"OUTPUT_FIELD_LEAK");
                    check(row.path("TerminalId").asText().equals(id(i+1).toString())&&row.path("VehicleId").asText().equals(VEHICLES.get(i<2?0:i-1).toString()),"OUTPUT_MAPPING_WRONG");
                    if(file.equals("results.json"))check(row.path("Status").asText().equals("PASS"),"OUTPUT_STATUS_WRONG");
                }
            }
        });
        for(String event:sequence()) test("stop_at_"+event.replace(':','_'),()->{
            Fake f=new Fake();f.fail=event;Path p=directory();var r=run(f,p);
            check(!r.passed(),"FAILURE_ACCEPTED");
            check(f.events.contains(event),"STEP_NOT_EXECUTED");
            int failure=f.events.indexOf(event);
            check(f.events.subList(failure+1,f.events.size()).stream().allMatch(s->s.startsWith("close")),"CONTINUED_AFTER_FAILURE");
            check(Arrays.equals(f.open,new boolean[4]),"CONNECTION_LEAK");
            check(!Files.exists(p.resolve("acceptance")),"FAILED_RUN_PUBLISHED_PASS");
            check(!r.toString().contains("SECRET"),"UNSAFE_EXCEPTION");
        });
        for(String bad:List.of("serial","result","registration","closed")) test("reject_"+bad,()->{
            Fake f=new Fake();f.bad=bad;Path p=directory();var r=run(f,p);
            check(!r.passed()&&!Files.exists(p.resolve("acceptance")),"INVALID_EVIDENCE_PASSED");
            check(Arrays.equals(f.open,new boolean[4]),"CONNECTION_LEAK");
        });
        test("duplicate_lease_rejected",()->{
            Fake f=new Fake();f.leases.set(3,f.leases.get(2));Path p=directory();
            check(!run(f,p).passed()&&!Files.exists(p.resolve("acceptance")),"DUPLICATE_CONNECTION_ACCEPTED");
        });
        test("wrong_vehicle_shape_rejected_before_socket",()->{
            Fake f=new Fake();var x=f.mappings.get(1);f.mappings.set(1,new P6CompositeWireHarness.Mapping(x.terminal(),VEHICLES.get(1),x.system()));
            Path p=directory();check(!run(f,p).passed()&&f.events.equals(List.of("expected")),"WRONG_MAPPING_OPENED_SOCKET");
        });
        test("existing_output_never_overwritten",()->{
            Fake f=new Fake();Path p=directory();Files.createDirectory(p.resolve("acceptance"));Files.writeString(p.resolve("acceptance/expected.json"),"sentinel");
            check(!run(f,p).passed()&&f.events.isEmpty(),"EXISTING_OUTPUT_NOT_REFUSED_FIRST");
            check(Files.readString(p.resolve("acceptance/expected.json")).equals("sentinel"),"OUTPUT_OVERWRITTEN");
        });
        test("close_failure_revokes_publication_and_closes_remaining",()->{
            Fake f=new Fake();f.fail="close2";Path p=directory();var r=run(f,p);
            check(!r.passed()&&!Files.exists(p.resolve("acceptance")),"CLOSE_FAILURE_PUBLISHED_PASS");
            check(Arrays.equals(f.open,new boolean[4])&&f.events.contains("close0"),"CLOSE_FAILURE_LEAK");
        });
        }
        if(group.equals("ALL")||group.equals("ONBOARD"))onboardHttpTests();
        if(group.equals("ALL")||group.equals("ADAPTERS"))adapterTests();
        if(group.equals("ALL")||group.equals("DIRECTORY"))directoryContractTests();
        int expected=switch(group){case "CORE"->63;case "ONBOARD"->6;case "ADAPTERS"->19;case "DIRECTORY"->6;default->94;};
        if(total!=expected){System.out.println("P6_WIRE_GROUP_COVERAGE_INVALID");System.exit(2);return;}
        String prefix=group.equals("ALL")?"P6_WIRE_TESTS":"P6_WIRE_GROUP GROUP="+group;
        System.out.println(prefix+" TOTAL="+total+" PASSED="+passed+" FAILED="+(total-passed));
        System.exit(total==passed?0:1);
    }
}
