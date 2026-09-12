package com.idavy.drtops.jtgateway.dispatch;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.idavy.drtops.jt.protocol.codec.*;
import com.idavy.drtops.jt.protocol.core.*;
import com.idavy.drtops.jtgateway.ingress.*;
import com.idavy.drtops.jtgateway.session.*;
import io.netty.buffer.Unpooled;
import io.netty.channel.embedded.EmbeddedChannel;
import java.time.*; import java.util.*;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import static org.junit.jupiter.api.Assertions.*;
class VideoDeclarationDispatchTest {
 @Test void persistsOnlyQueriedAuthenticatedDeclarationAndReplaysOnce() throws Exception {
  var ds=new DriverManagerDataSource("jdbc:h2:mem:video_dispatch;MODE=PostgreSQL;DB_CLOSE_DELAY=-1","sa","");
  Flyway.configure().dataSource(ds).load().migrate();
  var repo=new GatewayOutboxRepository(ds);var mapper=new ObjectMapper().findAndRegisterModules();
  Instant now=Instant.now();var clock=Clock.fixed(now,ZoneOffset.UTC);
  var session=new TerminalSession(new EmbeddedChannel(),now);UUID id=UUID.randomUUID();
  session.registrationAccepted(new TerminalSessionContext(2,id,UUID.randomUUID(),UUID.randomUUID(),1,Set.of("LOCATION_PRIMARY"),"WGS84",new TerminalSessionContext.SessionProtocolProfile("JT808_2013","NONE","NONE","NONE",List.of(),30,60),null,List.of(),1),"000000000000");session.authenticated(now);
  session.installLease(new TerminalRegistryPort.SessionLeaseGrant(new TerminalRegistryPort.SessionLeaseOwner(id,"video-local",session.connectionId(),1,1),now,now,now.plusSeconds(180)));
  var sessions=new TerminalSessionRegistry();sessions.claim(session);
  var registry=new ProtocolModuleRegistry(new Jt808CoreModule(new LocationReportCodec()),new GatewayIngressBuffer(repo,mapper,clock),mapper,sessions,clock);
  assertFalse(registry.dispatch(session,frame()).mayAcknowledgeSuccess());
  session.beginVideoDeclarationQuery(now);
  assertTrue(registry.dispatch(session,frame()).mayAcknowledgeSuccess());
  assertTrue(registry.dispatch(session,frame()).mayAcknowledgeSuccess());
  assertEquals(1,repo.totalCount());
  var entries=repo.claimEligible(now.plusSeconds(1),GatewayOutboxRepository.Priority.HIGH,10);
  assertEquals(1,entries.size());assertEquals("CAPABILITY_DECLARATION",entries.getFirst().kind().name());
  assertEquals(id.toString(),mapper.readTree(entries.getFirst().payloadJson()).path("terminalId").asText());
 }
 @Test void restoresV5SnapshotIntoSeparateDatabase(@org.junit.jupiter.api.io.TempDir java.nio.file.Path directory) throws Exception {
  var source=new DriverManagerDataSource("jdbc:h2:mem:video_restore_source;MODE=PostgreSQL;DB_CLOSE_DELAY=-1","sa","");
  Flyway.configure().dataSource(source).target("5").load().migrate();
  var repository=new GatewayOutboxRepository(source);
  repository.insert(new GatewayIngressEnvelope(1,UUID.randomUUID(),IngressKind.LOCATION,Instant.now(),"{}"),Instant.now());
  var backup=directory.resolve("gateway-v5.sql");
  try(var c=source.getConnection();var st=c.createStatement()){st.execute("SCRIPT TO '"+backup.toString().replace('\\','/')+"'");}
  Flyway.configure().dataSource(source).load().migrate();
  assertEquals("6",Flyway.configure().dataSource(source).load().info().current().getVersion().toString());
  var restored=new DriverManagerDataSource("jdbc:h2:mem:video_restore_target;MODE=PostgreSQL;DB_CLOSE_DELAY=-1","sa","");
  try(var c=restored.getConnection();var reader=java.nio.file.Files.newBufferedReader(backup)){org.h2.tools.RunScript.execute(c,reader);}
  assertEquals("5",Flyway.configure().dataSource(restored).load().info().current().getVersion().toString());
  assertEquals(1,new GatewayOutboxRepository(restored).totalCount());
  assertThrows(org.springframework.dao.DataIntegrityViolationException.class,()->new GatewayOutboxRepository(restored).insert(new GatewayIngressEnvelope(1,UUID.randomUUID(),IngressKind.CAPABILITY_DECLARATION,Instant.now(),"{}"),Instant.now()));
 }
 private static Jt808Frame frame(){byte[] body=HexFormat.of().parseHex("06010001014001620404");return new Jt808Frame(new Jt808MessageHeader(0x1003,10,10,0,false,ProtocolVersion.JT808_2013,0,"000000000000",12,null,null),Unpooled.wrappedBuffer(body),(byte)0);}
}
