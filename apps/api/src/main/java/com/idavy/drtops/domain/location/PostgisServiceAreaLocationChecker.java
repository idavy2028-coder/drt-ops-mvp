package com.idavy.drtops.domain.location;

import java.math.BigDecimal;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

@Component
public class PostgisServiceAreaLocationChecker implements ServiceAreaLocationChecker {

    private final JdbcTemplate jdbcTemplate;

    public PostgisServiceAreaLocationChecker(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public boolean isInsideEnabledArea(BigDecimal longitude, BigDecimal latitude) {
        Boolean covered = jdbcTemplate.queryForObject("""
                select exists (
                  select 1
                  from service_areas
                  where enabled
                    and ST_Covers(
                      boundary,
                      ST_SetSRID(ST_MakePoint(?, ?), 4326)::geography)
                )
                """, Boolean.class, longitude, latitude);
        return Boolean.TRUE.equals(covered);
    }
}
