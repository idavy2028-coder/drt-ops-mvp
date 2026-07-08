package com.idavy.drtops.domain.dispatch;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;

@DataJpaTest(showSql = false, properties = {
        "spring.flyway.enabled=false",
        "spring.jpa.hibernate.ddl-auto=create-drop"
})
class DispatchDecisionRepositoryTest {

    @Autowired
    DispatchDecisionRepository repository;

    @Autowired
    TestEntityManager entityManager;

    @Test
    void savesAndReloadsManualReviewDecision() {
        DispatchDecision decision = DispatchDecision.manualReview(
                UUID.randomUUID(),
                2,
                UUID.fromString("33333333-3333-3333-3333-333333333331"),
                null,
                "0.1.0",
                "SYSTEM",
                "dispatch-engine");

        UUID decisionId = repository.save(decision).getId();
        entityManager.flush();
        entityManager.clear();

        DispatchDecision reloaded = repository.findById(decisionId).orElseThrow();

        assertThat(reloaded.getDecisionResult()).isEqualTo("PENDING_MANUAL_REVIEW");
    }
}
