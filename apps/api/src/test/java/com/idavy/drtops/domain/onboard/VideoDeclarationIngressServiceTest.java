package com.idavy.drtops.domain.onboard;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.idavy.drtops.domain.terminal.*;
import com.idavy.drtops.domain.location.*;
import com.idavy.drtops.domain.audit.*;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.util.ReflectionTestUtils;
import static org.assertj.core.api.Assertions.*;
@SpringBootTest(properties={"spring.datasource.url=jdbc:h2:mem:video_declaration;MODE=PostgreSQL;DB_CLOSE_DELAY=-1", "spring.datasource.username=sa", "spring.datasource.password=", "spring.datasource.driver-class-name=org.h2.Driver", "spring.flyway.enabled=false", "spring.jpa.hibernate.ddl-auto=create-drop"})
@org.springframework.context.annotation.Import(com.idavy.drtops.domain.audit.AuditFailureTestConfiguration.class)
class VideoDeclarationIngressServiceTest {
 @Autowired VideoDeclarationIngressService service;
 @Autowired VideoDeclarationObservationRepository observations;
 @Autowired JtTerminalRepository terminals;
 @Autowired JtTerminalSessionLeaseService leases;
 @Autowired OnboardDeviceCapabilityRepository capabilities;
 @Autowired AuditLogRepository audits;
 @Autowired ObjectMapper mapper;
 @Autowired com.idavy.drtops.domain.audit.AuditFailureTestConfiguration.FailureSwitch auditFailure;
 @Autowired org.springframework.jdbc.core.JdbcTemplate jdbc;
 @Test void declarationIsDurableAssociatedIdempotentAndNeverVerified() throws Exception {
  UUID id=UUID.randomUUID(), actor=UUID.randomUUID(), connection=UUID.randomUUID();
  jdbc.update("insert into user_accounts (id,username,display_name,password_hash,enabled,token_version,must_change_password,created_at,updated_at) values (?,?,?,'DISABLED_TEST_IDENTITY',false,0,true,current_timestamp,current_timestamp)",actor,"video-"+actor,"Synthetic video");
  var terminal=JtTerminal.preset(id,"00000000000000000012","VIDEO-"+id,"MFG01","MODEL-X","JT808_2019","WGS84",actor);
  ReflectionTestUtils.setField(terminal,"authTokenVersion",7); ReflectionTestUtils.setField(terminal,"status",JtTerminal.Status.ACTIVE); terminals.saveAndFlush(terminal);
  var lease=leases.acquire(id,"gateway-video",connection,7);
  var fields=new LinkedHashMap<String,Object>(); fields.put("terminalId",id); fields.put("connectionId",connection); fields.put("gatewayInstance","gateway-video"); fields.put("tokenVersion",7); fields.put("leaseGeneration",lease.owner().leaseGeneration()); fields.put("serialNumber",12); fields.put("messageId",4099); fields.put("payloadDigest","a".repeat(64)); fields.put("attributes",Map.of("audioEncoding",6,"audioChannels",1,"audioSampleRate",0,"audioSampleBits",1,"audioFrameLength",320,"audioOutput",1,"videoEncoding",98,"maxAudioChannels",4,"maxVideoChannels",4));
  var envelope=new GatewayIngressEnvelope(1,UUID.randomUUID(),"CAPABILITY_DECLARATION",Instant.now(),mapper.writeValueAsString(fields));
  long before=audits.count();
  assertThat(service.ingest(envelope).status()).isEqualTo("ACCEPTED");
  assertThat(service.ingest(envelope).status()).isEqualTo("ACCEPTED");
  assertThat(observations.findById(envelope.idempotencyKey())).isPresent();
  assertThat(capabilities.findCurrentByTerminalIdAndCapability(id,OnboardDeviceCapability.Capability.VIDEO).orElseThrow().getStatus()).isEqualTo(OnboardDeviceCapability.CapabilityStatus.DECLARED);
  assertThat(audits.count()).isEqualTo(before+1);
  var fact=capabilities.findCurrentByTerminalIdAndCapability(id,OnboardDeviceCapability.Capability.VIDEO).orElseThrow();
  fact.verify("synthetic-only-test-proof",actor,"Synthetic verification fixture",OffsetDateTime.now(ZoneOffset.UTC));capabilities.saveAndFlush(fact);
  for(int replay=0;replay<2;replay++) {
   var unchanged=new GatewayIngressEnvelope(1,UUID.randomUUID(),envelope.kind(),Instant.now(),envelope.payloadJson());
   assertThat(service.ingest(unchanged).reasonCodes()).contains("UNCHANGED");
  }
  fields.put("attributes",Map.of("audioEncoding",6,"audioChannels",1,"audioSampleRate",0,"audioSampleBits",1,"audioFrameLength",320,"audioOutput",1,"videoEncoding",98,"maxAudioChannels",4,"maxVideoChannels",2));
  var conflict=new GatewayIngressEnvelope(1,UUID.randomUUID(),envelope.kind(),Instant.now(),mapper.writeValueAsString(fields));
  assertThat(service.ingest(conflict).reasonCodes()).contains("REVIEW_REQUIRED");
  var observed=observations.findById(conflict.idempotencyKey()).orElseThrow();
  service.resolve(terminal.getTerminalCode(),observed.getId(),observed.getVersion(),actor,"Synthetic conflict disposition","synthetic-test-evidence");
  assertThat(observations.findById(observed.getId()).orElseThrow().getResolvedAt()).isNotNull();
  var replacement=leases.acquire(id,"gateway-video",UUID.randomUUID(),7);
  var stale=new GatewayIngressEnvelope(1,UUID.randomUUID(),envelope.kind(),envelope.gatewayReceivedAt(),envelope.payloadJson());
  assertThat(service.ingest(stale).reasonCodes()).contains("QUARANTINED");
  assertThat(capabilities.findCurrentByTerminalIdAndCapability(id,OnboardDeviceCapability.Capability.VIDEO).orElseThrow().getStatus()).isEqualTo(OnboardDeviceCapability.CapabilityStatus.VERIFIED);
  fields.put("connectionId",replacement.owner().connectionId());fields.put("leaseGeneration",replacement.owner().leaseGeneration());
  var rollback=new GatewayIngressEnvelope(1,UUID.randomUUID(),envelope.kind(),Instant.now(),mapper.writeValueAsString(fields));
  long auditBeforeFailure=audits.count();
  auditFailure.fail=true;
  try { assertThatThrownBy(()->service.ingest(rollback)).isInstanceOf(RuntimeException.class); }
  finally { auditFailure.fail=false; }
  assertThat(observations.findById(rollback.idempotencyKey())).isEmpty();
  assertThat(audits.count()).isEqualTo(auditBeforeFailure);
  fields.put("payloadDigest","b".repeat(64));
  assertThat(service.ingest(new GatewayIngressEnvelope(1,envelope.idempotencyKey(),envelope.kind(),envelope.gatewayReceivedAt(),mapper.writeValueAsString(fields))).status()).isEqualTo("REJECTED");
 }
}
