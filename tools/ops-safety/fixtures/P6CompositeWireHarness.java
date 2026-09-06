import java.nio.file.*;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.Duration;
import java.io.*;
import java.net.*;
import java.net.http.*;
import java.sql.*;
import java.security.MessageDigest;
import java.util.concurrent.Flow;
import java.nio.ByteBuffer;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.idavy.drtops.jtsimulator.SimulatedTerminal;
import com.idavy.drtops.jt.protocol.codec.ProtocolVersion;
import java.util.*;

/** 本轮合成四终端专用编排；I/O边界不接收任意终端清单、URL或SQL。 */
public final class P6CompositeWireHarness {
    record Config(Path directory, String api, int tcpPort, String jdbc, String password, String token,
                  String gateway, List<UUID> vehicles, Instant created) {}
    static Config validateEnvironment(Map<String,String> env,Properties owner,Properties wire) {
        try {
            Set<String> ownerKeys=Set.of("SchemaVersion","RunId","OwnerNonce","RunDirectory","CreatedAt","PgPort");
            Set<String> wireKeys=Set.of("SchemaVersion","RunId","OwnerNonce","RunDirectory","CreatedAt","ApiPort","GatewayTcpPort","GatewayInstance","VehicleAId","VehicleBId","VehicleCId");
            require(owner.stringPropertyNames().equals(ownerKeys)&&wire.stringPropertyNames().equals(wireKeys),"REHEARSAL_WIRE_OWNERSHIP_INVALID");
            String run=env.get("P6_REHEARSAL_RUN_ID"),nonce=env.get("P6_REHEARSAL_OWNER_NONCE"),directory=env.get("P6_REHEARSAL_RUN_DIRECTORY");
            require(run!=null&&run.matches("[a-f0-9]{32}")&&nonce!=null&&nonce.matches("[a-f0-9]{64}")&&directory!=null,"REHEARSAL_WIRE_OWNERSHIP_INVALID");
            require("1".equals(owner.getProperty("SchemaVersion"))&&"1".equals(wire.getProperty("SchemaVersion")),"REHEARSAL_WIRE_OWNERSHIP_INVALID");
            for(Properties marker:List.of(owner,wire)) require(run.equals(marker.getProperty("RunId"))&&nonce.equals(marker.getProperty("OwnerNonce"))&&directory.equals(marker.getProperty("RunDirectory")),"REHEARSAL_WIRE_OWNERSHIP_INVALID");
            Instant created=Instant.parse(owner.getProperty("CreatedAt")),provisioned=Instant.parse(wire.getProperty("CreatedAt"));
            require(inRun(provisioned,created),"REHEARSAL_WIRE_OWNERSHIP_INVALID");
            int pg=port(owner.getProperty("PgPort")),api=port(wire.getProperty("ApiPort")),tcp=port(wire.getProperty("GatewayTcpPort"));
            require(Set.of(pg,api,tcp).size()==3,"REHEARSAL_WIRE_ENDPOINT_INVALID");
            String apiUrl="http://127.0.0.1:"+api,jdbc="jdbc:postgresql://127.0.0.1:"+pg+"/composite_live",gateway="rehearsal-"+run;
            require(apiUrl.equals(env.get("P6_REHEARSAL_API_BASE_URL"))&&jdbc.equals(env.get("P6_REHEARSAL_JDBC_URL"))&&Integer.toString(tcp).equals(env.get("P6_REHEARSAL_GATEWAY_TCP_PORT"))&&gateway.equals(env.get("P6_REHEARSAL_GATEWAY_INSTANCE"))&&gateway.equals(wire.getProperty("GatewayInstance")),"REHEARSAL_WIRE_ENDPOINT_INVALID");
            String password=env.get("P6_REHEARSAL_DB_PASSWORD"),token=env.get("P6_REHEARSAL_API_TOKEN");
            require("composite".equals(env.get("P6_REHEARSAL_DB_USER"))&&password!=null&&password.matches("[A-Za-z0-9_-]{32,128}")&&token!=null&&token.matches("[A-Za-z0-9_-]+\\.[A-Za-z0-9_-]+\\.[A-Za-z0-9_-]+")&&token.length()<=8192,"REHEARSAL_WIRE_CREDENTIAL_INVALID");
            List<UUID> vehicles=new ArrayList<>();
            Set<String> allowed=new HashSet<>(Set.of("RUN_ID","OWNER_NONCE","RUN_DIRECTORY","JDBC_URL","DB_USER","DB_PASSWORD","API_BASE_URL","API_TOKEN","GATEWAY_TCP_PORT","GATEWAY_INSTANCE"));
            for(char suffix='A';suffix<='C';suffix++) {
                String key="VEHICLE_"+suffix+"_ID",value=env.get("P6_REHEARSAL_"+key);allowed.add(key);
                require(value!=null&&value.equals(wire.getProperty("Vehicle"+suffix+"Id")),"REHEARSAL_WIRE_MAPPING_INVALID");vehicles.add(uuid(value));
            }
            require(new HashSet<>(vehicles).size()==3,"REHEARSAL_WIRE_MAPPING_INVALID");
            for(String key:env.keySet()) if(key.startsWith("P6_REHEARSAL_"))require(allowed.contains(key.substring(13)),"REHEARSAL_WIRE_ENVIRONMENT_INVALID");
            Path path=Path.of(directory);safePath(path);
            return new Config(path,apiUrl,tcp,jdbc,password,token,gateway,List.copyOf(vehicles),created);
        } catch(SafeFailure safe) {throw safe;}
        catch(Throwable ignored) {throw new SafeFailure("REHEARSAL_WIRE_OWNERSHIP_INVALID");}
    }
    static int port(String value) {require(value!=null&&value.matches("[1-9][0-9]{3,4}"),"REHEARSAL_WIRE_ENDPOINT_INVALID");int n=Integer.parseInt(value);require(n>=1024&&n<=65535,"REHEARSAL_WIRE_ENDPOINT_INVALID");return n;}
    static UUID uuid(String value) {require(value!=null&&value.matches("[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}"),"REHEARSAL_WIRE_MAPPING_INVALID");UUID id=UUID.fromString(value);require(!id.equals(new UUID(0,0)),"REHEARSAL_WIRE_MAPPING_INVALID");return id;}
    interface Connections {Connection open() throws Exception;}
    static class Live implements Boundary {
        private final Config config;
        private final Connections connections;
        private final HttpClient http=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).followRedirects(HttpClient.Redirect.NEVER).proxy(new ProxySelector(){
            public List<Proxy> select(URI uri){return List.of(Proxy.NO_PROXY);}
            public void connectFailed(URI uri,SocketAddress address,IOException error){}
        }).build();
        private final ObjectMapper json=new ObjectMapper();
        Live(Config config) {this(config,()->{
            Properties properties=new Properties();properties.setProperty("user","composite");properties.setProperty("password",config.password());
            properties.setProperty("connectTimeout","5");properties.setProperty("socketTimeout","10");
            DriverManager.setLoginTimeout(5);return DriverManager.getConnection(config.jdbc(),properties);
        });}
        Live(Config config,Connections connections) {this.config=config;this.connections=connections;}
        public Wire terminal(int i) {
            String terminalCode=code(i),phone="13990000000"+(i+1),plate="SYN-"+(char)('A'+(i<2?0:i-1));
            SimulatedTerminal terminal=new SimulatedTerminal(phone,ProtocolVersion.JT808_2013,plate,"SIMMF","SIM-MODEL",terminalCode);
            return new Wire() {
                public void connect(){terminal.connect(new InetSocketAddress("127.0.0.1",config.tcpPort()));}
                public int send(int message){return switch(message){case 0x0100->terminal.sendRegistration();case 0x0102->terminal.sendAuthentication();case 0x0002->terminal.sendHeartbeat();default->throw new SafeFailure("REHEARSAL_WIRE_MESSAGE_INVALID");};}
                public Reply reply(){var r=terminal.awaitReply(Duration.ofSeconds(5));return r==null?null:new Reply(r.messageId(),r.requestMessageId()==null?-1:r.requestMessageId(),r.requestSerialNo(),r.result());}
                public boolean live(){return !terminal.awaitPeerClose(Duration.ofMillis(1));}
                public void close(){terminal.close();}
            };
        }
        public TerminalState terminalState(int i) throws Exception {return terminalView(request("GET","/api/terminals/"+code(i),null),i);}
        public TerminalState activate(int i,long version) throws Exception {
            require(version>=0,"REHEARSAL_WIRE_TERMINAL_INVALID");
            return terminalView(request("POST","/api/terminals/"+code(i)+"/activate","{\"expectedVersion\":"+version+",\"reason\":\"isolated synthetic rehearsal\"}"),i);
        }
        private TerminalState terminalView(JsonNode data,int i) {
            require(code(i).equals(string(data,"terminalCode")),"REHEARSAL_WIRE_API_INVALID");
            require(data.path("registrationCompleted").isBoolean()&&data.path("version").isIntegralNumber()&&data.path("version").canConvertToLong(),"REHEARSAL_WIRE_API_INVALID");
            return new TerminalState(string(data,"status"),data.path("registrationCompleted").booleanValue(),data.path("version").longValue(),optionalInstant(data,"lastRegisteredAt"),optionalInstant(data,"lastAuthenticatedAt"));
        }
        public void onboard(int i,Mapping mapping) throws Exception {
            JsonNode terminal=request("GET","/api/terminals/"+code(i),null),membership=terminal.path("currentOnboardMembership");
            require(mapping.system().equals(uuid(string(membership,"onboardSystemId")))&&mapping.vehicle().equals(uuid(string(membership,"vehicleId")))&&"ACTIVE".equals(string(membership,"status")),"REHEARSAL_WIRE_MAPPING_INVALID");
            JsonNode system=request("GET","/api/onboard-systems/"+mapping.vehicle(),null);
            require(mapping.system().equals(uuid(string(system,"onboardSystemId")))&&mapping.vehicle().equals(uuid(string(system,"vehicleId")))&&"ACTIVE".equals(string(system,"status"))&&(i==2?"SAFETY_MONITOR_ONLY":"DISPATCH_SERVICE").equals(string(system,"operatingMode")),"REHEARSAL_WIRE_API_INVALID");
            JsonNode devices=system.path("devices");require(devices.isArray()&&devices.size()==(i<2?2:1),"REHEARSAL_WIRE_API_INVALID");
            String deviceAlias=deviceAlias(mapping.terminal());JsonNode matched=null;
            for(JsonNode device:devices)if(deviceAlias.equals(string(device,"deviceAlias"))){require(matched==null,"REHEARSAL_WIRE_API_INVALID");matched=device;}
            require(matched!=null&&matched.path("currentlyAuthenticated").isBoolean()&&matched.path("currentlyAuthenticated").booleanValue()&&"ACTIVE".equals(string(matched,"terminalStatus"))&&"DIRECT_CELLULAR".equals(string(matched,"networkMode")),"REHEARSAL_WIRE_API_INVALID");
            Set<String> expectedRoles=i==1?Set.of("VIDEO","LOCATION_BACKUP"):i==2?Set.of("VIDEO","LOCATION_PRIMARY","WAN_UPLINK"):Set.of("DISPATCH","LOCATION_PRIMARY","WAN_UPLINK");
            require(strings(matched.path("roles")).equals(expectedRoles),"REHEARSAL_WIRE_API_INVALID");
            boolean video=i==1||i==2;JsonNode profile=matched.path("protocolProfiles");
            require("JT808_2013".equals(string(profile,"transportProfile"))&&(video?"NONE":"VENDOR_DISPATCH").equals(string(profile,"businessProfile"))&&"NONE".equals(string(profile,"safetyProfile"))&&(video?"JT1078_2016":"NONE").equals(string(profile,"mediaProfile"))&&profile.path("activePositionIntervalSeconds").isIntegralNumber()&&profile.path("activePositionIntervalSeconds").intValue()==10&&profile.path("idlePositionIntervalSeconds").isIntegralNumber()&&profile.path("idlePositionIntervalSeconds").intValue()==60,"REHEARSAL_WIRE_API_INVALID");
            require(strings(matched.path("verifiedCapabilities")).equals(Set.of("JT808_LOCATION",video?"VIDEO":"VENDOR_DISPATCH"))&&inRun(optionalInstant(matched,"lastRegisteredAt"),config.created())&&inRun(optionalInstant(matched,"lastAuthenticatedAt"),config.created()),"REHEARSAL_WIRE_API_INVALID");
        }
        // 固定只读SQL。UUID来自本轮创建响应/受控联结，绝不由mask或别名反推。
        static final String SQL="SELECT t.id AS terminal_id,t.terminal_code,t.terminal_phone,t.manufacturer_id,t.model,t.protocol_version,t.status,t.last_registered_at,t.last_authenticated_at,t.created_at,s.id AS system_id,s.vehicle_id,v.plate_number,v.dispatchable,v.fleet_name,l.connection_id,l.gateway_instance,l.authenticated_at,l.expires_at,l.released_at,l.expires_at > now() AS live,l.token_version=t.auth_token_version AS token_current FROM jt_terminals t JOIN onboard_device_memberships m ON m.terminal_id=t.id AND m.status='ACTIVE' AND m.valid_to IS NULL JOIN onboard_systems s ON s.id=m.onboard_system_id AND s.status='ACTIVE' JOIN vehicles v ON v.id=s.vehicle_id LEFT JOIN jt_terminal_session_leases l ON l.terminal_id=t.id WHERE t.terminal_code IN (?,?,?,?) ORDER BY t.terminal_code";
        public List<Mapping> expected() throws Exception {return database(false).mappings();}
        public Evidence evidence() throws Exception {return database(true);}
        private Evidence database(boolean active) throws Exception {
            List<Mapping> mappings=new ArrayList<>();List<Lease> leases=new ArrayList<>();
            try(Connection connection=connections.open()) {
                connection.setReadOnly(true);
                try(PreparedStatement statement=connection.prepareStatement(SQL)) {
                    statement.setQueryTimeout(5);statement.setMaxRows(5);for(int i=0;i<4;i++)statement.setString(i+1,code(i));
                    try(ResultSet rows=statement.executeQuery()) {
                        for(int i=0;i<4;i++) {
                            require(rows.next(),"REHEARSAL_WIRE_MAPPING_INVALID");int v=i<2?0:i-1;
                            require(code(i).equals(rows.getString("terminal_code"))&&("13990000000"+(i+1)).equals(rows.getString("terminal_phone"))&&"SIMMF".equals(rows.getString("manufacturer_id"))&&"SIM-MODEL".equals(rows.getString("model"))&&"JT808_2013".equals(rows.getString("protocol_version"))&&("SYN-"+(char)('A'+v)).equals(rows.getString("plate_number"))&&!rows.getBoolean("dispatchable")&&"SYNTHETIC-REHEARSAL".equals(rows.getString("fleet_name"))&&inRun(time(rows,"created_at"),config.created()),"REHEARSAL_WIRE_FIXTURE_INVALID");
                            Mapping mapping=new Mapping(uuid(rows.getString("terminal_id")),uuid(rows.getString("vehicle_id")),uuid(rows.getString("system_id")));mappings.add(mapping);
                            require((active?"ACTIVE":"PENDING").equals(rows.getString("status")),"REHEARSAL_WIRE_TERMINAL_INVALID");
                            if(active) {
                                require(inRun(time(rows,"last_registered_at"),config.created())&&inRun(time(rows,"last_authenticated_at"),config.created())&&rows.getBoolean("live")&&rows.getBoolean("token_current"),"REHEARSAL_WIRE_LEASE_INVALID");
                                leases.add(new Lease(uuid(rows.getString("connection_id")),rows.getString("gateway_instance"),time(rows,"authenticated_at"),time(rows,"expires_at"),rows.getObject("released_at")!=null));
                            } else require(rows.getObject("last_registered_at")==null&&rows.getObject("last_authenticated_at")==null&&rows.getObject("connection_id")==null,"REHEARSAL_WIRE_FIXTURE_INVALID");
                        }
                        require(!rows.next(),"REHEARSAL_WIRE_MAPPING_INVALID");
                    }
                }
            }
            validateMappings(mappings,config.vehicles());return new Evidence(List.copyOf(mappings),List.copyOf(leases));
        }
        private JsonNode request(String method,String path,String body) throws Exception {
            HttpRequest.Builder request=HttpRequest.newBuilder(URI.create(config.api()+path)).timeout(Duration.ofSeconds(5)).header("Authorization","Bearer "+config.token()).header("Accept","application/json");
            if("POST".equals(method))request.header("Content-Type","application/json").POST(HttpRequest.BodyPublishers.ofString(body));else request.GET();
            HttpResponse<byte[]> response=http.send(request.build(),info->new LimitedBody());
            require(response.statusCode()==200,"REHEARSAL_WIRE_HTTP_FAILED");
            JsonNode envelope=json.readTree(response.body());require(envelope!=null&&envelope.isObject()&&envelope.path("data").isObject(),"REHEARSAL_WIRE_API_INVALID");return envelope.path("data");
        }
    }
    static final class LimitedBody implements HttpResponse.BodySubscriber<byte[]> {
        final HttpResponse.BodySubscriber<byte[]> delegate=HttpResponse.BodySubscribers.ofByteArray();Flow.Subscription subscription;long size;
        public java.util.concurrent.CompletionStage<byte[]> getBody(){return delegate.getBody();}
        public void onSubscribe(Flow.Subscription s){subscription=s;delegate.onSubscribe(s);}
        public void onNext(List<ByteBuffer> buffers){for(ByteBuffer b:buffers)size+=b.remaining();if(size>65536){subscription.cancel();delegate.onError(new SafeFailure("REHEARSAL_WIRE_HTTP_FAILED"));}else delegate.onNext(buffers);}
        public void onError(Throwable error){delegate.onError(error);}public void onComplete(){delegate.onComplete();}
    }
    static String string(JsonNode node,String name){JsonNode value=node.path(name);require(value.isTextual(),"REHEARSAL_WIRE_API_INVALID");return value.textValue();}
    static Set<String> strings(JsonNode array){require(array.isArray(),"REHEARSAL_WIRE_API_INVALID");Set<String> values=new HashSet<>();for(JsonNode value:array)require(value.isTextual()&&values.add(value.textValue()),"REHEARSAL_WIRE_API_INVALID");return values;}
    static Instant optionalInstant(JsonNode node,String name){JsonNode value=node.path(name);return value.isMissingNode()||value.isNull()?null:Instant.parse(string(node,name));}
    static Instant time(ResultSet rows,String column)throws Exception{java.time.OffsetDateTime value=rows.getObject(column,java.time.OffsetDateTime.class);return value==null?null:value.toInstant();}
    static String deviceAlias(UUID terminal)throws Exception{return "device-"+HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(terminal.toString().getBytes(StandardCharsets.US_ASCII)),0,6);}
    record Reply(int message, int request, int serial, int result) {}
    record TerminalState(String status, boolean registered, long version, Instant registeredAt, Instant authenticatedAt) {}
    record Mapping(UUID terminal, UUID vehicle, UUID system) {}
    record Lease(UUID connection, String gateway, Instant authenticatedAt, Instant expiresAt, boolean released) {}
    record Evidence(List<Mapping> mappings, List<Lease> leases) {}
    interface Wire extends AutoCloseable {
        void connect() throws Exception;
        int send(int message) throws Exception;
        Reply reply() throws Exception;
        boolean live() throws Exception;
        void close() throws Exception;
    }
    interface Boundary {
        List<Mapping> expected() throws Exception;
        Wire terminal(int index) throws Exception;
        TerminalState terminalState(int index) throws Exception;
        TerminalState activate(int index, long version) throws Exception;
        void onboard(int index, Mapping mapping) throws Exception;
        Evidence evidence() throws Exception;
    }
    record Outcome(boolean passed, String step, String alias, String code) {}
    static Outcome run(Boundary boundary, List<UUID> vehicles, String gateway, Instant start, Path runDirectory) {
        List<Wire> wires=new ArrayList<>();
        String step="OUTPUT",alias="NONE";
        boolean staged=false,published=false;
        Path stage=runDirectory.resolve(".wire-stage"),target=runDirectory.resolve("acceptance");
        Outcome outcome=new Outcome(false,step,alias,"REHEARSAL_WIRE_STEP_FAILED");
        try {
            safePath(runDirectory);
            require(!Files.exists(target,LinkOption.NOFOLLOW_LINKS)&&!Files.exists(stage,LinkOption.NOFOLLOW_LINKS),"REHEARSAL_WIRE_OUTPUT_INVALID");
            // 不覆盖旧结果；stage目录同时是单进程排他认领，任何预存内容都拒绝。
            Files.createDirectory(stage);staged=true;
            step="EXPECTED";
            List<Mapping> expected=List.copyOf(boundary.expected());
            validateMappings(expected,vehicles);
            require(start!=null&&!start.isAfter(Instant.now()),"REHEARSAL_WIRE_EVIDENCE_INVALID");
            for(int i=0;i<4;i++) {
                step="CONNECT";alias=alias(i);
                Wire wire=boundary.terminal(i);wires.add(wire);wire.connect();
            }
            for(int i=0;i<4;i++) {
                alias=alias(i);Wire wire=wires.get(i);
                step="REGISTER";exchange(wire,0x0100);
                step="PENDING";TerminalState pending=boundary.terminalState(i);
                require(pending!=null&&"PENDING".equals(pending.status())&&pending.registered()&&pending.version()>=0,"REHEARSAL_WIRE_TERMINAL_INVALID");
                require(inRun(pending.registeredAt(),start),"REHEARSAL_WIRE_TERMINAL_INVALID");
                step="ACTIVATE";TerminalState active=boundary.activate(i,pending.version());
                require(active!=null&&"ACTIVE".equals(active.status())&&active.registered(),"REHEARSAL_WIRE_TERMINAL_INVALID");
                step="AUTHENTICATE";exchange(wire,0x0102);
                step="HEARTBEAT";exchange(wire,0x0002);
            }
            for(int i=0;i<4;i++) {
                step="API";alias=alias(i);TerminalState state=boundary.terminalState(i);
                require(state!=null&&"ACTIVE".equals(state.status())&&state.registered()&&inRun(state.registeredAt(),start)&&inRun(state.authenticatedAt(),start),"REHEARSAL_WIRE_TERMINAL_INVALID");
                boundary.onboard(i,expected.get(i));
            }
            step="LEASES";alias="ALL";
            Evidence evidence=boundary.evidence();
            require(evidence!=null&&expected.equals(evidence.mappings()),"REHEARSAL_WIRE_MAPPING_INVALID");
            validateMappings(evidence.mappings(),vehicles);
            require(evidence.leases()!=null&&evidence.leases().size()==4,"REHEARSAL_WIRE_LEASE_INVALID");
            Set<UUID> connections=new HashSet<>();
            for(Lease lease:evidence.leases()) {
                require(lease!=null&&lease.connection()!=null&&connections.add(lease.connection())&&gateway.equals(lease.gateway())&&!lease.released()
                    &&inRun(lease.authenticatedAt(),start)&&lease.expiresAt()!=null&&lease.expiresAt().isAfter(Instant.now()),"REHEARSAL_WIRE_LEASE_INVALID");
            }
            step="LIVE";
            for(int i=0;i<4;i++) {alias=alias(i);require(wires.get(i).live(),"REHEARSAL_WIRE_CLOSED");}
            step="PUBLISH";alias="ALL";
            writeNew(stage.resolve("expected.json"),json(expected,false));
            writeNew(stage.resolve("results.json"),json(expected,true));
            safePath(runDirectory);
            require(!Files.exists(target,LinkOption.NOFOLLOW_LINKS),"REHEARSAL_WIRE_OUTPUT_INVALID");
            // 两文件关闭并force后整目录原子发布；不支持ATOMIC_MOVE的平台直接失败。
            Files.move(stage,target,StandardCopyOption.ATOMIC_MOVE);staged=false;published=true;
            outcome=new Outcome(true,"COMPLETE","ALL","REHEARSAL_WIRE_OK");
        } catch(SafeFailure safe) {outcome=new Outcome(false,step,alias,safe.getMessage());}
        catch(Throwable unsafe) {outcome=new Outcome(false,step,alias,"REHEARSAL_WIRE_STEP_FAILED");}
        finally {
            // 每个close独立执行：一个close失败不能阻止其余socket关闭。
            boolean closeFailed=false;
            for(int i=wires.size()-1;i>=0;i--) try {wires.get(i).close();} catch(Throwable ignored) {closeFailed=true;}
            if(closeFailed) outcome=new Outcome(false,"CLOSE","ALL","REHEARSAL_WIRE_CLOSE_FAILED");
            try {
                if(staged) removeOwnedPair(stage);
                if(published&&!outcome.passed()) removeOwnedPair(target);
            } catch(Throwable ignored) {outcome=new Outcome(false,"CLEANUP","ALL","REHEARSAL_WIRE_OUTPUT_RETAINED");}
        }
        return outcome;
    }

    static String alias(int i) {require(i>=0&&i<4,"REHEARSAL_WIRE_FIXTURE_INVALID");return "terminal-0"+(i+1);}
    static String code(int i) {alias(i);return "SYN000"+(i+1);}
    static boolean inRun(Instant value,Instant start) {return value!=null&&!value.isBefore(start)&&!value.isAfter(Instant.now());}
    static void validateMappings(List<Mapping> mappings,List<UUID> vehicles) {
        require(mappings!=null&&mappings.size()==4&&vehicles!=null&&vehicles.size()==3&&new HashSet<>(vehicles).size()==3&&vehicles.stream().allMatch(Objects::nonNull),"REHEARSAL_WIRE_MAPPING_INVALID");
        Set<UUID> terminals=new HashSet<>(),systems=new HashSet<>();
        for(int i=0;i<4;i++) {
            Mapping m=mappings.get(i);int v=i<2?0:i-1;
            require(m!=null&&m.terminal()!=null&&terminals.add(m.terminal())&&vehicles.get(v).equals(m.vehicle())&&m.system()!=null,"REHEARSAL_WIRE_MAPPING_INVALID");
            systems.add(m.system());
        }
        require(systems.size()==3&&mappings.get(0).system().equals(mappings.get(1).system()),"REHEARSAL_WIRE_MAPPING_INVALID");
    }
    static void exchange(Wire wire,int message) throws Exception {
        int serial=wire.send(message);Reply reply=wire.reply();
        require(serial>0&&serial<=65535&&reply!=null&&reply.message()==(message==0x0100?0x8100:0x8001)
            &&reply.request()==message&&reply.serial()==serial&&reply.result()==0,"REHEARSAL_WIRE_REPLY_INVALID");
    }
    static void safePath(Path path) throws Exception {
        require(path.isAbsolute()&&path.equals(path.normalize())&&Files.isDirectory(path,LinkOption.NOFOLLOW_LINKS),"REHEARSAL_WIRE_OUTPUT_INVALID");
        for(Path p=path;p!=null;p=p.getParent()) require(!Files.isSymbolicLink(p)&&!Files.readAttributes(p,java.nio.file.attribute.BasicFileAttributes.class,LinkOption.NOFOLLOW_LINKS).isOther(),"REHEARSAL_WIRE_OUTPUT_INVALID");
    }
    static void writeNew(Path path,String data) throws Exception {
        try(FileChannel channel=FileChannel.open(path,StandardOpenOption.CREATE_NEW,StandardOpenOption.WRITE)) {
            java.nio.ByteBuffer bytes=StandardCharsets.UTF_8.encode(data);
            while(bytes.hasRemaining()) channel.write(bytes);
            channel.force(true);
        }
    }
    static void removeOwnedPair(Path path) throws Exception {
        // 只删除本调用创建的两固定文件及空目录，无递归、无任意路径输入。
        safePath(path);
        Files.deleteIfExists(path.resolve("expected.json"));Files.deleteIfExists(path.resolve("results.json"));Files.delete(path);
    }
    static String json(List<Mapping> mappings,boolean results) {
        List<String> rows=new ArrayList<>();
        for(int i=0;i<4;i++) {
            Mapping m=mappings.get(i);
            rows.add("{\"SafeAlias\":\""+alias(i)+"\",\"TerminalId\":\""+m.terminal()+"\",\"VehicleId\":\""+m.vehicle()+"\",\"OnboardSystemId\":\""+m.system()+"\""+(results?",\"Status\":\"PASS\"":"")+"}");
        }
        return "["+String.join(",",rows)+"]\n";
    }
    static final class SafeFailure extends IllegalStateException {SafeFailure(String code) {super(code);}}
    static void require(boolean value,String code) {if(!value) throw new SafeFailure(code);}
    static Properties readMarker(Path path) throws Exception {
        require(Files.isRegularFile(path,LinkOption.NOFOLLOW_LINKS)&&!Files.isSymbolicLink(path)&&Files.size(path)<=8192&&!Files.readAttributes(path,java.nio.file.attribute.BasicFileAttributes.class,LinkOption.NOFOLLOW_LINKS).isOther(),"REHEARSAL_WIRE_OWNERSHIP_INVALID");
        Properties values=new Properties(){
            @Override public synchronized Object put(Object key,Object value){require(!containsKey(key),"REHEARSAL_WIRE_OWNERSHIP_INVALID");return super.put(key,value);}
        };
        try(InputStream input=Files.newInputStream(path,LinkOption.NOFOLLOW_LINKS)){values.load(input);}
        return values;
    }
    public static void main(String[] args) {
        PrintStream output=System.out;
        // 第三方网络/JDBC日志全部丢弃，只有固定step/alias/code可写stdout。
        System.setOut(new PrintStream(OutputStream.nullOutputStream()));System.setErr(new PrintStream(OutputStream.nullOutputStream()));
        Outcome outcome;
        try {
            require(args.length==0,"REHEARSAL_WIRE_ARGUMENT_INVALID");
            Map<String,String> env=System.getenv();String run=env.get("P6_REHEARSAL_RUN_ID");
            require(run!=null&&run.matches("[a-f0-9]{32}"),"REHEARSAL_WIRE_OWNERSHIP_INVALID");
            Path cwd=Path.of("").toAbsolutePath().normalize();
            require(cwd.toString().equals(env.get("P6_REHEARSAL_RUN_DIRECTORY"))&&cwd.getFileName().toString().equals("native-"+run),"REHEARSAL_WIRE_OWNERSHIP_INVALID");
            Path parent=cwd.getParent();
            require(parent!=null&&parent.getFileName().toString().equals("2026-09-06-p6-2-local-isolation-rehearsal")&&parent.getParent()!=null&&parent.getParent().getFileName().toString().equals("sdd")&&parent.getParent().getParent()!=null&&parent.getParent().getParent().getFileName().toString().equals(".superpowers"),"REHEARSAL_WIRE_OWNERSHIP_INVALID");
            safePath(cwd);
            Config config=validateEnvironment(env,readMarker(cwd.resolve("owner.properties")),readMarker(cwd.resolve("wire.properties")));
            outcome=run(new Live(config),config.vehicles(),config.gateway(),Instant.now(),cwd);
        } catch(SafeFailure safe){outcome=new Outcome(false,"INIT","NONE",safe.getMessage());}
        catch(Throwable ignored){outcome=new Outcome(false,"INIT","NONE","REHEARSAL_WIRE_OWNERSHIP_INVALID");}
        output.println("P6_WIRE_STATUS="+(outcome.passed()?"PASS":"FAIL")+" STEP="+outcome.step()+" ALIAS="+outcome.alias()+" CODE="+outcome.code()+" REMAINING="+(outcome.passed()?"NONE":"SKIP"));
        System.exit(outcome.passed()?0:1);
    }
}
