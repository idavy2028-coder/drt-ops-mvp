package com.idavy.drtops.domain.onboard;
import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import com.fasterxml.jackson.databind.JsonNode;
import java.time.OffsetDateTime;
import java.util.UUID;
@Entity @Table(name="video_declaration_observations")
public class VideoDeclarationObservation {
 @Id private UUID id;
 @Column(nullable=false) private UUID terminalId;
 @Column(nullable=false) private UUID connectionId;
 @Column(nullable=false) private long leaseGeneration;
 @Column(nullable=false) private int serialNumber;
 @Column(nullable=false,length=64) private String payloadDigest;
 @JdbcTypeCode(SqlTypes.JSON) @Column(nullable=false) private JsonNode payload;
 @Column(nullable=false) private OffsetDateTime receivedAt;
 @Column(nullable=false,length=30) private String outcome;
 private OffsetDateTime resolvedAt;
 private UUID resolvedBy;
 @Column(length=500) private String resolutionReason;
 @Column(length=500) private String resolutionEvidenceRef;
 @Version private long version;
 public long getVersion(){return version;}
 public OffsetDateTime getResolvedAt(){return resolvedAt;}
 public void resolve(UUID actor,String reason,String evidence,OffsetDateTime at){resolvedAt=at;resolvedBy=actor;resolutionReason=reason;resolutionEvidenceRef=evidence;}
 protected VideoDeclarationObservation() {}
 public VideoDeclarationObservation(UUID id, UUID terminalId, UUID connectionId, long generation, int serial,
     String digest, JsonNode payload, OffsetDateTime receivedAt, String outcome) {
  this.id=id;this.terminalId=terminalId;this.connectionId=connectionId;this.leaseGeneration=generation;
  this.serialNumber=serial;this.payloadDigest=digest;this.payload=payload.deepCopy();this.receivedAt=receivedAt;this.outcome=outcome;
 }
 public UUID getId(){return id;}
 public UUID getTerminalId(){return terminalId;}
 public JsonNode getPayload(){return payload.deepCopy();}
 public OffsetDateTime getReceivedAt(){return receivedAt;}
 public String getOutcome(){return outcome;}
}
