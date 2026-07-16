package com.idavy.drtops.domain.area;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.idavy.drtops.domain.audit.AuditLogRepository;
import com.idavy.drtops.domain.dispatch.DispatchRuleSet;
import com.idavy.drtops.domain.dispatch.DispatchRuleSetRepository;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:service_area_command;MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.flyway.enabled=false",
        "spring.jpa.hibernate.ddl-auto=create-drop"
})
class ServiceAreaCommandServiceTest {

    private static final UUID RULE_SET_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID AREA_ID = UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final UUID ACTOR_ID = UUID.fromString("33333333-3333-3333-3333-333333333333");
    private static final String BOUNDARY_A = "POLYGON((105.20 35.20,105.30 35.20,105.30 35.30,105.20 35.30,105.20 35.20))";
    private static final String BOUNDARY_B = "POLYGON((105.40 35.40,105.50 35.40,105.50 35.50,105.40 35.50,105.40 35.40))";

    @Autowired
    ServiceAreaCommandService commandService;

    @Autowired
    ServiceAreaRepository serviceAreaRepository;

    @Autowired
    DispatchRuleSetRepository ruleSetRepository;

    @Autowired
    AuditLogRepository auditLogRepository;

    @BeforeEach
    void setUp() {
        auditLogRepository.deleteAll();
        serviceAreaRepository.deleteAll();
        ruleSetRepository.deleteAll();
        ruleSetRepository.save(DispatchRuleSet.defaultRules(RULE_SET_ID));
        commandService.create(new ServiceAreaCommandService.CreateServiceAreaCommand(
                AREA_ID, "通渭县试点服务区", BOUNDARY_A, "06:00:00", "23:00:00", RULE_SET_ID), ACTOR_ID);
    }

    @Test
    void savesValidClosedPolygonAsGcj02ManualDraftWithoutChangingPublishedBoundary() {
        ServiceAreaView view = commandService.saveBoundary(
                AREA_ID, new ServiceAreaBoundaryRequest(BOUNDARY_B, null), ACTOR_ID);

        assertThat(view.boundaryWkt()).isNull();
        assertThat(view.draftBoundaryWkt()).startsWith("POLYGON((105.40 35.40");
        assertThat(view.draftBoundarySource()).isEqualTo("MANUAL");
        assertThat(view.draftBoundaryVersion()).isEqualTo(2);
        assertThat(view.coordinateSystem()).isEqualTo("GCJ02");
        assertThat(view.publishedAt()).isNull();
    }

    @Test
    void keepsPublishedBoundaryUntilDraftIsExplicitlyPublished() {
        ServiceAreaView publishedA = commandService.publish(AREA_ID, ACTOR_ID);

        ServiceAreaView draftB = commandService.saveBoundary(
                AREA_ID, new ServiceAreaBoundaryRequest(BOUNDARY_B, null), ACTOR_ID);

        assertThat(draftB.boundaryWkt()).isEqualTo(publishedA.boundaryWkt());
        assertThat(draftB.boundaryVersion()).isEqualTo(publishedA.boundaryVersion());
        assertThat(draftB.draftBoundaryWkt()).contains("105.40 35.40");
        assertThat(draftB.publishedAt()).isNotNull();

        ServiceAreaView publishedB = commandService.publish(AREA_ID, ACTOR_ID);

        assertThat(publishedB.boundaryWkt()).contains("105.40 35.40");
        assertThat(publishedB.boundaryVersion()).isEqualTo(2);
        assertThat(publishedB.publishedAt()).isAfterOrEqualTo(publishedA.publishedAt());
        assertThat(auditLogRepository.findByEntityId(AREA_ID))
                .anyMatch(log -> log.getAction().equals("SERVICE_AREA_PUBLISHED"));
    }

    @Test
    void importsDistrictAsDraftWithoutReplacingPublishedBoundaryAndAuditsIt() {
        commandService.publish(AREA_ID, ACTOR_ID);

        ServiceAreaView imported = commandService.importDistrictDraft("通渭县试点服务区", BOUNDARY_B, ACTOR_ID);

        assertThat(imported.boundaryWkt()).contains("105.20 35.20");
        assertThat(imported.draftBoundaryWkt()).contains("105.40 35.40");
        assertThat(imported.draftBoundarySource()).isEqualTo("AMAP_DISTRICT");
        assertThat(auditLogRepository.findByEntityId(AREA_ID))
                .anyMatch(log -> log.getAction().equals("SERVICE_AREA_DISTRICT_BOUNDARY_IMPORTED"));
    }

    @Test
    void acceptsClosedPolygonWhenEquivalentDecimalsHaveDifferentScale() {
        ServiceAreaView view = commandService.saveBoundary(AREA_ID, new ServiceAreaBoundaryRequest(
                "POLYGON((105.20 35.20,105.30 35.20,105.30 35.30,105.20 35.30,105.200 35.200))", null), ACTOR_ID);

        assertThat(view.draftBoundaryWkt()).contains("105.200 35.200");
    }

    @Test
    void rejectsPolygonThatIsNotClosed() {
        assertBoundaryRejected(
                "POLYGON((105.20 35.20,105.30 35.20,105.30 35.30,105.20 35.30))",
                "服务区边界必须闭合");
    }

    @Test
    void rejectsPolygonWithFewerThanThreeDistinctPoints() {
        assertBoundaryRejected(
                "POLYGON((105.20 35.20,105.30 35.20,105.20 35.20))",
                "服务区边界至少需要三个点");
    }

    @Test
    void rejectsPolygonWithOutOfRangeCoordinates() {
        assertBoundaryRejected(
                "POLYGON((181.00 35.20,105.30 35.20,105.30 35.30,181.00 35.20))",
                "服务区边界坐标范围不合法");
    }

    private void assertBoundaryRejected(String wkt, String message) {
        assertThatThrownBy(() -> commandService.saveBoundary(
                        AREA_ID, new ServiceAreaBoundaryRequest(wkt, null), ACTOR_ID))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(exception -> {
                    ResponseStatusException statusException = (ResponseStatusException) exception;
                    assertThat(statusException.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
                    assertThat(statusException.getReason()).isEqualTo(message);
                });
    }
}
