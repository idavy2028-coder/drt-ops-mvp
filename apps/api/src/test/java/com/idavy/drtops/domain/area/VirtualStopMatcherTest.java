package com.idavy.drtops.domain.area;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:virtual_stop_matcher;MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.flyway.enabled=false",
        "spring.jpa.hibernate.ddl-auto=create-drop"
})
class VirtualStopMatcherTest {

    private static final UUID DEMO_AREA_ID = UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final UUID BOARDING_STOP_ID = UUID.fromString("55555555-5555-5555-5555-555555555551");
    private static final UUID ALIGHTING_STOP_ID = UUID.fromString("55555555-5555-5555-5555-555555555552");

    @Autowired
    VirtualStopRepository virtualStopRepository;

    @Autowired
    VirtualStopMatcher matcher;

    @BeforeEach
    void setUp() {
        virtualStopRepository.deleteAll();
        virtualStopRepository.save(VirtualStop.create(
                BOARDING_STOP_ID,
                DEMO_AREA_ID,
                "上车虚拟站",
                "POINT(120.1550000 30.2741000)",
                600,
                true,
                false,
                "路侧安全候车"));
        virtualStopRepository.save(VirtualStop.create(
                ALIGHTING_STOP_ID,
                DEMO_AREA_ID,
                "下车虚拟站",
                "POINT(120.1688000 30.2799000)",
                600,
                false,
                true,
                "地铁口附近落客"));
    }

    @Test
    void matchesNearestBoardingAndAlightingStopsInsideServiceArea() {
        VirtualStopMatcher.VirtualStopMatch match = matcher.matchStops(
                new BigDecimal("120.1550"),
                new BigDecimal("30.2741"),
                new BigDecimal("120.1688"),
                new BigDecimal("30.2799"),
                Instant.parse("2026-07-08T02:30:00Z"));

        assertThat(match.boardingStopId()).isEqualTo(BOARDING_STOP_ID);
        assertThat(match.alightingStopId()).isEqualTo(ALIGHTING_STOP_ID);
        assertThat(match.boardingDistanceMeters()).isLessThanOrEqualTo(600);
        assertThat(match.alightingDistanceMeters()).isLessThanOrEqualTo(600);
    }

    @Test
    void rejectsDemandWhenNoBoardingStopIsInsideRadius() {
        assertThatThrownBy(() -> matcher.matchStops(
                        new BigDecimal("120.3000"),
                        new BigDecimal("30.4000"),
                        new BigDecimal("120.1688"),
                        new BigDecimal("30.2799"),
                        Instant.parse("2026-07-08T02:30:00Z")))
                .isInstanceOf(VirtualStopMatcher.NoMatchedStopException.class)
                .hasMessageContaining("boarding");
    }
}
