package com.idavy.drtops.domain.terminal;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class GatewayRegistrationCapabilityContractTest {
    @Test
    void approvedRegistrationCarriesTheBoundVehicleCoordinatesAndActiveSafetyProfile() {
        UUID terminalId = UUID.randomUUID(); UUID vehicleId = UUID.randomUUID();
        TerminalManagementService.RegistrationDecision decision = new TerminalManagementService.RegistrationDecision(
                true, terminalId, vehicleId, "WGS84", "T/JSATL12-2017", List.of("ADAS", "DMS"), 7, null);

        assertThat(decision.vehicleId()).isEqualTo(vehicleId);
        assertThat(decision.sourceCoordinateSystem()).isEqualTo("WGS84");
        assertThat(decision.activeSafetyStandard()).isEqualTo("T/JSATL12-2017");
        assertThat(decision.activeSafetyModules()).containsExactly("ADAS", "DMS");
    }
}
