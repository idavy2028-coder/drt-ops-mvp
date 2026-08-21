package com.idavy.drtops.jtgateway.ingress;

/** Boundary used by protocol routing to persist one normalized position before acknowledgement. */
@FunctionalInterface
public interface PositionIngressBuffer {
    GatewayIngressBuffer.WriteResult append(CanonicalPositionIngress position);
}
