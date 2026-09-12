package com.idavy.drtops.domain.audit;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;

@TestConfiguration
public class AuditFailureTestConfiguration {
    @Bean
    static FailureSwitch auditFailureSwitch() { return new FailureSwitch(); }
    public static class FailureSwitch implements BeanPostProcessor {
        public volatile boolean fail;
        @Override public Object postProcessAfterInitialization(Object bean, String name) {
            if (!(bean instanceof AuditLogRepository)) return bean;
            return Proxy.newProxyInstance(AuditLogRepository.class.getClassLoader(),
                    new Class<?>[]{AuditLogRepository.class}, (proxy, method, args) -> {
                        if (fail && method.getName().equals("save") && args[0] instanceof AuditLog audit
                                && (audit.getAction().startsWith("SESSION_LEASE_") || audit.getAction().startsWith("VEHICLE_ALARM_") || audit.getAction().startsWith("DECLARATION_"))) {
                            throw new IllegalStateException("synthetic audit failure");
                        }
                        try { return method.invoke(bean, args); }
                        catch (InvocationTargetException failure) { throw failure.getCause(); }
                    });
        }
    }
}
