package com.idavy.drtops.domain.area;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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

    @Autowired
    ServiceAreaCommandService commandService;

    @Autowired
    ServiceAreaRepository serviceAreaRepository;

    @Autowired
    DispatchRuleSetRepository ruleSetRepository;

    @BeforeEach
    void setUp() {
        serviceAreaRepository.deleteAll();
        ruleSetRepository.deleteAll();
        ruleSetRepository.save(DispatchRuleSet.defaultRules(RULE_SET_ID));
        serviceAreaRepository.save(ServiceArea.create(
                AREA_ID,
                "通渭县试点服务区",
                "POLYGON((105.20 35.20,105.30 35.20,105.30 35.30,105.20 35.30,105.20 35.20))",
                "06:00:00",
                "23:00:00",
                RULE_SET_ID));
    }

    @Test
    void savesValidClosedPolygonAsGcj02ManualDraft() {
        ServiceAreaView view = commandService.saveBoundary(
                AREA_ID,
                new ServiceAreaBoundaryRequest(
                        "POLYGON((105.20 35.20,105.30 35.20,105.30 35.30,105.20 35.30,105.20 35.20))", null),
                ACTOR_ID);

        assertThat(view.boundaryWkt()).startsWith("POLYGON((105.20 35.20");
        assertThat(view.boundarySource()).isEqualTo("MANUAL");
        assertThat(view.boundaryVersion()).isEqualTo(1);
        assertThat(view.coordinateSystem()).isEqualTo("GCJ02");
        assertThat(view.publishedAt()).isNull();
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
