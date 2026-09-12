package com.idavy.drtops.domain.onboard;
import java.util.*;
import org.springframework.data.jpa.repository.JpaRepository;
public interface VideoDeclarationObservationRepository extends JpaRepository<VideoDeclarationObservation,UUID> {
 org.springframework.data.domain.Page<VideoDeclarationObservation> findByTerminalId(UUID terminalId,org.springframework.data.domain.Pageable pageable);
 org.springframework.data.domain.Page<VideoDeclarationObservation> findByTerminalIdAndResolvedAtIsNullAndOutcomeIn(UUID terminalId,List<String> outcomes,org.springframework.data.domain.Pageable pageable);
 List<VideoDeclarationObservation> findTop20ByTerminalIdOrderByReceivedAtDesc(UUID terminalId);
}
