package com.idavy.drtops.jtgateway.attachment;

import com.idavy.drtops.jtgateway.session.TerminalSessionRegistry;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Wires the session registry shared by the control plane. Capability-ready wiring only: the
 * gateway runtime does not dispatch attachment commands until the alarm identifier retention
 * ruling is revisited.
 */
@Configuration
class AttachmentControlConfiguration {

    @Bean
    TerminalSessionRegistry terminalSessionRegistry() {
        return new TerminalSessionRegistry();
    }
}
