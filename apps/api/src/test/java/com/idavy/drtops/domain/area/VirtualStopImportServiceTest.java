package com.idavy.drtops.domain.area;

import static org.assertj.core.api.Assertions.assertThat;

import com.idavy.drtops.domain.location.ServiceAreaLocationChecker;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;

@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:virtual_stop_import;MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.flyway.enabled=false",
        "spring.jpa.hibernate.ddl-auto=create-drop"
})
@Import(VirtualStopImportServiceTest.LocationCheckConfiguration.class)
class VirtualStopImportServiceTest {

    private static final UUID SERVICE_AREA_ID = UUID.fromString("22222222-2222-2222-2222-222222222222");

    @Autowired private VirtualStopImportService importService;
    @Autowired private VirtualStopRepository virtualStopRepository;
    @Autowired private ServiceAreaRepository serviceAreaRepository;

    @BeforeEach
    void setUp() {
        virtualStopRepository.deleteAll();
        serviceAreaRepository.deleteAll();
        serviceAreaRepository.save(ServiceArea.create(
                SERVICE_AREA_ID, "通渭县试点服务区",
                "POLYGON((105.20 35.18,105.30 35.18,105.30 35.25,105.20 35.25,105.20 35.18))",
                "06:30", "19:00", UUID.randomUUID()));
    }

    @Test
    void importsThreeValidRows() {
        VirtualStopImportResult result = importService.importCsv(csv(
                "县医院北门,通渭县医院北门,105.2411,35.2112,通渭县试点服务区,500,是,是,医院门口",
                "文化广场,通渭县文化广场,105.2422,35.2123,通渭县试点服务区,500,是,否,广场东侧",
                "汽车站,通渭县汽车站,105.2433,35.2134,通渭县试点服务区,600,否,是,\"客运站，落客区\""), UUID.randomUUID());

        assertThat(result.createdCount()).isEqualTo(3);
        assertThat(result.skippedCount()).isZero();
        assertThat(result.issues()).isEmpty();
        assertThat(virtualStopRepository.findAll()).hasSize(3);
    }

    @Test
    void reportsDuplicateAndOutsideRowsWithoutFailingTheWholeFile() {
        virtualStopRepository.save(VirtualStop.create(
                UUID.randomUUID(), SERVICE_AREA_ID, "县医院北门", "POINT(105.2411 35.2112)", 500, true, true, "已有站点"));

        VirtualStopImportResult result = importService.importCsv(csv(
                "县医院北门,重复站点,105.2411,35.2112,通渭县试点服务区,500,是,是,重复",
                "服务区外站点,县城外,106.001,35.2134,通渭县试点服务区,500,是,是,待核验"), UUID.randomUUID());

        assertThat(result.createdCount()).isEqualTo(1);
        assertThat(result.skippedCount()).isEqualTo(1);
        assertThat(result.issues()).extracting(VirtualStopImportResult.Issue::message)
                .contains("站点名称已存在", "站点位于服务区外，已按未启用状态暂存");
        assertThat(virtualStopRepository.findAll()).hasSize(2);
        assertThat(virtualStopRepository.findAll().stream()
                .filter(stop -> stop.getName().equals("服务区外站点"))
                .findFirst().orElseThrow().isEnabled()).isFalse();
    }

    private String csv(String... rows) {
        return "站点名称,地址,经度,纬度,所属区域,服务半径(米),允许上车,允许下车,安全说明\n" + String.join("\n", rows);
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class LocationCheckConfiguration {
        @Bean @Primary
        ServiceAreaLocationChecker serviceAreaLocationChecker() {
            return (longitude, latitude) -> longitude.compareTo(new java.math.BigDecimal("105.30")) <= 0;
        }
    }
}
