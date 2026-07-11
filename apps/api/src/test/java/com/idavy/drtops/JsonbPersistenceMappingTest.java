package com.idavy.drtops;

import static org.assertj.core.api.Assertions.assertThat;

import com.idavy.drtops.domain.audit.AuditLog;
import com.idavy.drtops.domain.dispatch.DispatchDecision;
import java.lang.reflect.Field;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import org.junit.jupiter.api.Test;

class JsonbPersistenceMappingTest {

    @Test
    void jsonbColumnsUseHibernateJsonJdbcType() throws NoSuchFieldException {
        assertJsonMapping(DispatchDecision.class, "rejectedReasonsJson");
        assertJsonMapping(DispatchDecision.class, "explanationJson");
        assertJsonMapping(AuditLog.class, "metadataJson");
    }

    private void assertJsonMapping(Class<?> entityType, String fieldName) throws NoSuchFieldException {
        Field field = entityType.getDeclaredField(fieldName);
        JdbcTypeCode jdbcTypeCode = field.getAnnotation(JdbcTypeCode.class);

        assertThat(jdbcTypeCode)
                .as("%s.%s must declare a JSON JDBC type", entityType.getSimpleName(), fieldName)
                .isNotNull();
        assertThat(jdbcTypeCode.value()).isEqualTo(SqlTypes.JSON);
    }
}
