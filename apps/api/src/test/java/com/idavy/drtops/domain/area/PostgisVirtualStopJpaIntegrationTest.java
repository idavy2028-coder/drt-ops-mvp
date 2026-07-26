package com.idavy.drtops.domain.area;

import static org.assertj.core.api.Assertions.assertThat;

import com.idavy.drtops.domain.dispatch.DispatchRuleSet;
import com.idavy.drtops.domain.dispatch.DispatchRuleSetRepository;
import jakarta.persistence.EntityManager;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.transaction.AfterTransaction;
import org.springframework.transaction.annotation.Transactional;

@EnabledIfSystemProperty(named = "drt.integration.postgis", matches = "true")
@SpringBootTest(properties = {
        "spring.datasource.url=${drt.integration.postgis-url:jdbc:postgresql://127.0.0.1:15432/drt_ops}",
        "spring.datasource.username=drt_ops",
        "spring.datasource.password=drt_ops"
})
@Transactional
class PostgisVirtualStopJpaIntegrationTest {

    @Autowired
    private VirtualStopRepository virtualStopRepository;

    @Autowired
    private ServiceAreaRepository serviceAreaRepository;

    @Autowired
    private DispatchRuleSetRepository dispatchRuleSetRepository;

    @Autowired
    private EntityManager entityManager;

    private UUID serviceAreaId;
    private UUID stopId;

    @BeforeEach
    void setUpServiceArea() {
        UUID ruleSetId = UUID.randomUUID();
        serviceAreaId = UUID.randomUUID();
        dispatchRuleSetRepository.save(DispatchRuleSet.defaultRules(ruleSetId));
        serviceAreaRepository.save(ServiceArea.create(
                serviceAreaId,
                "PostGIS 映射验证服务区",
                "POLYGON((105.20 35.18,105.30 35.18,105.30 35.25,105.20 35.25,105.20 35.18))",
                "06:30",
                "19:00",
                ruleSetId));
    }

    @Test
    void persistsAndReadsLocationThroughJpa() {
        stopId = UUID.randomUUID();
        VirtualStop stop = VirtualStop.create(
                stopId,
                serviceAreaId,
                "PostGIS 映射验证站",
                "POINT(105.2421 35.2103)",
                500,
                true,
                true,
                "测试数据将在事务回滚");

        virtualStopRepository.saveAndFlush(stop);
        entityManager.clear();
        assertThat(virtualStopRepository.findById(stopId).orElseThrow().getLocation())
                .isEqualTo("POINT(105.2421 35.2103)");
    }

    @AfterTransaction
    void leavesNoPersistentTestData() {
        assertThat(virtualStopRepository.findById(stopId)).isEmpty();
    }
}
