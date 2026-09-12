package com.idavy.drtops.domain.onboard;

import com.fasterxml.jackson.databind.*;
import com.idavy.drtops.domain.audit.*;
import com.idavy.drtops.domain.location.*;
import com.idavy.drtops.domain.terminal.*;
import jakarta.persistence.*;
import java.time.*;
import java.util.*;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class VideoDeclarationIngressService {
 private final EntityManager em;
 private final VideoDeclarationObservationRepository observations;
 private final OnboardDeviceCapabilityRepository capabilities;
 private final JtTerminalSessionLeaseRepository leases;
 private final AuditLogRepository audits;
 private final ObjectMapper mapper;
 private final Clock clock;
 public VideoDeclarationIngressService(EntityManager em,VideoDeclarationObservationRepository observations,
   OnboardDeviceCapabilityRepository capabilities,JtTerminalSessionLeaseRepository leases,
   AuditLogRepository audits,ObjectMapper mapper,ObjectProvider<Clock> clocks) {
  this.em=em;this.observations=observations;this.capabilities=capabilities;this.leases=leases;this.audits=audits;this.mapper=mapper;this.clock=clocks.getIfAvailable(Clock::systemUTC);
 }
 @Transactional
 public GpsLocationIngressService.Result ingest(GatewayIngressEnvelope e) {
  final JsonNode p; final UUID terminalId; final UUID connection;
  try {
   if(e.schemaVersion()!=1 || !"CAPABILITY_DECLARATION".equals(e.kind()) || e.idempotencyKey()==null || e.gatewayReceivedAt()==null) return rejected(e,"INVALID_DECLARATION");
   p=mapper.readTree(e.payloadJson()); terminalId=UUID.fromString(p.path("terminalId").asText()); connection=UUID.fromString(p.path("connectionId").asText());
   if(!p.path("payloadDigest").asText().matches("[0-9a-f]{64}") || !integer(p,"messageId",4099,4099)
     || !integer(p,"serialNumber",0,65535) || !integer(p,"tokenVersion",1,Integer.MAX_VALUE)
     || !p.path("leaseGeneration").isIntegralNumber() || !p.path("leaseGeneration").canConvertToLong() || p.path("leaseGeneration").longValue()<1
     || !p.path("gatewayInstance").isTextual() || p.path("gatewayInstance").asText().isBlank() || p.path("gatewayInstance").asText().length()>120
     || !validAttributes(p.path("attributes"))) return rejected(e,"INVALID_DECLARATION");
  } catch(Exception malformed) { return rejected(e,"INVALID_DECLARATION"); }
  // Same lock as lease mutation: serialize device facts with lease takeover.
  var terminal=em.find(JtTerminal.class,terminalId,LockModeType.PESSIMISTIC_WRITE);
  if(terminal==null) return rejected(e,"UNKNOWN_TERMINAL");
  var existing=observations.findById(e.idempotencyKey());
  if(existing.isPresent()) {
   var observation=existing.get();
   return observation.getTerminalId().equals(terminalId) && observation.getPayload().equals(p)
       && observation.getReceivedAt().toInstant().equals(e.gatewayReceivedAt().truncatedTo(java.time.temporal.ChronoUnit.MICROS))
     ? accepted(e,observation.getOutcome()) : rejected(e,"IDEMPOTENCY_CONFLICT");
  }
  var lease=leases.findLockedByTerminalId(terminalId).orElse(null);
  var now=OffsetDateTime.ofInstant(clock.instant(),ZoneOffset.UTC);
  boolean sessionMismatch = terminal.getStatus()!=JtTerminal.Status.ACTIVE || lease==null
    || !lease.getConnectionId().equals(connection) || lease.getLeaseGeneration()!=p.path("leaseGeneration").longValue()
    || !lease.getGatewayInstance().equals(p.path("gatewayInstance").asText())
    || lease.getTokenVersion()!=p.path("tokenVersion").intValue()
    || lease.getTokenVersion()!=terminal.getAuthTokenVersion()
    || e.gatewayReceivedAt().isBefore(lease.getAuthenticatedAt().toInstant())
    || e.gatewayReceivedAt().isAfter(now.toInstant().plusSeconds(5));
  String outcome="DECLARED";
  var history=capabilities.findHistoryByTerminalIdAndCapabilityOrderByCreatedAtAsc(terminalId,OnboardDeviceCapability.Capability.VIDEO);
  var current=capabilities.findCurrentByTerminalIdAndCapability(terminalId,OnboardDeviceCapability.Capability.VIDEO);
  if(sessionMismatch) outcome="QUARANTINED";
  else if(!lease.isLiveAt(terminal.getAuthTokenVersion(),now)) outcome="HISTORICAL";
  else if(current.isPresent() && current.get().getStatus()==OnboardDeviceCapability.CapabilityStatus.VERIFIED) {
   var recent=observations.findTop20ByTerminalIdOrderByReceivedAtDesc(terminalId);
   boolean unchanged=!recent.isEmpty() && recent.getFirst().getPayload().path("attributes").equals(p.path("attributes"))
     && (recent.getFirst().getResolvedAt()!=null || recent.getFirst().getOutcome().equals("UNCHANGED") || (recent.getFirst().getOutcome().equals("DECLARED")
       && !recent.getFirst().getReceivedAt().isAfter(current.get().getVerifiedAt())))
     && p.path("attributes").path("maxVideoChannels").intValue()>0;
   outcome=unchanged ? "UNCHANGED" : "REVIEW_REQUIRED";
  }
  else if(current.isEmpty() && !history.isEmpty()) outcome="DISABLED_CONFLICT";
  else if(p.path("attributes").path("maxVideoChannels").intValue()==0) outcome="NO_VIDEO_CHANNELS";
  else if(current.isEmpty()) capabilities.save(OnboardDeviceCapability.declare(terminalId,OnboardDeviceCapability.Capability.VIDEO,"JT1078 terminal declaration; media verification required",now));
  observations.save(new VideoDeclarationObservation(e.idempotencyKey(),terminalId,connection,p.path("leaseGeneration").longValue(),p.path("serialNumber").intValue(),p.path("payloadDigest").asText(),p,OffsetDateTime.ofInstant(e.gatewayReceivedAt().truncatedTo(java.time.temporal.ChronoUnit.MICROS),ZoneOffset.UTC),outcome));
  var metadata=mapper.createObjectNode().put("terminalId",terminalId.toString()).put("observationId",e.idempotencyKey().toString()).put("connectionId",connection.toString()).put("leaseGeneration",p.path("leaseGeneration").longValue()).put("payloadDigest",p.path("payloadDigest").asText()).put("outcome",outcome);
  audits.save(AuditLog.record("VIDEO_DECLARATION",terminalId,(Set.of("REVIEW_REQUIRED","DISABLED_CONFLICT","NO_VIDEO_CHANNELS").contains(outcome)?"DECLARATION_CONFLICT":outcome.equals("QUARANTINED")?"DECLARATION_QUARANTINED":"DECLARATION_RECORDED"),"GATEWAY","jt-gateway",outcome,metadata.toString()));
  em.flush();
  return accepted(e,outcome);
 }
 @Transactional(readOnly=true)
 public List<ObservationView> read(String terminalCode, int page, int size, boolean unresolvedOnly) {
  if(page<0 || size<1 || size>100) throw new IllegalArgumentException("INVALID_PAGE");
  var terminal=em.createQuery("select t from JtTerminal t where t.terminalCode=:code",JtTerminal.class).setParameter("code",terminalCode).getSingleResult();
  var pageable=org.springframework.data.domain.PageRequest.of(page,size,org.springframework.data.domain.Sort.by("receivedAt").descending().and(org.springframework.data.domain.Sort.by("id")));
  var result=unresolvedOnly ? observations.findByTerminalIdAndResolvedAtIsNullAndOutcomeIn(terminal.getId(),List.of("REVIEW_REQUIRED","DISABLED_CONFLICT","NO_VIDEO_CHANNELS","QUARANTINED"),pageable)
      : observations.findByTerminalId(terminal.getId(),pageable);
  return result.stream().map(this::view).toList();
 }
 @Transactional
 public ObservationView resolve(String terminalCode, UUID observationId, long expectedVersion, UUID actor, String reason, String evidence) {
  Objects.requireNonNull(actor);
  reason=OnboardText.requireAuditText(reason,"reason"); evidence=OnboardText.requireAuditText(evidence,"evidenceRef");
  var terminal=em.createQuery("select t from JtTerminal t where t.terminalCode=:code",JtTerminal.class).setParameter("code",terminalCode).setLockMode(LockModeType.PESSIMISTIC_WRITE).getSingleResult();
  var observation=observations.findById(observationId).orElseThrow();
  if(!observation.getTerminalId().equals(terminal.getId()) || observation.getVersion()!=expectedVersion || observation.getResolvedAt()!=null
     || !Set.of("REVIEW_REQUIRED","DISABLED_CONFLICT","NO_VIDEO_CHANNELS","QUARANTINED").contains(observation.getOutcome())) throw new IllegalArgumentException("DECLARATION_RESOLUTION_CONFLICT");
  observation.resolve(actor,reason,evidence,OffsetDateTime.now(clock));
  audits.save(AuditLog.record("VIDEO_DECLARATION",terminal.getId(),"DECLARATION_CONFLICT_RESOLVED","USER",actor.toString(),reason,
    mapper.createObjectNode().put("observationId",observationId.toString()).put("evidenceRef",evidence).toString()));
  em.flush();return view(observation);
 }
 private ObservationView view(VideoDeclarationObservation o){return new ObservationView(o.getId(),o.getTerminalId(),o.getPayload().path("attributes"),o.getOutcome(),o.getReceivedAt(),o.getResolvedAt(),o.getVersion());}
 public record ObservationView(UUID observationId,UUID terminalId,JsonNode attributes,String outcome,OffsetDateTime receivedAt,OffsetDateTime resolvedAt,long version){}
 private static boolean integer(JsonNode p,String key,int min,int max) { var n=p.path(key);return n.isIntegralNumber()&&n.canConvertToInt()&&n.intValue()>=min&&n.intValue()<=max; }
 private static boolean validAttributes(JsonNode a) {
  return a.isObject()&&a.size()==9&&integer(a,"audioEncoding",1,28)&&integer(a,"audioChannels",0,255)&&integer(a,"audioSampleRate",0,3)&&integer(a,"audioSampleBits",0,2)&&integer(a,"audioFrameLength",1,65535)&&integer(a,"audioOutput",0,1)&&integer(a,"videoEncoding",98,101)&&integer(a,"maxAudioChannels",0,255)&&integer(a,"maxVideoChannels",0,255);
 }
 private static GpsLocationIngressService.Result rejected(GatewayIngressEnvelope e,String reason){return new GpsLocationIngressService.Result(e.idempotencyKey(),"REJECTED",List.of(reason));}
 private static GpsLocationIngressService.Result accepted(GatewayIngressEnvelope e,String outcome){return new GpsLocationIngressService.Result(e.idempotencyKey(),"ACCEPTED",List.of(outcome));}
}
