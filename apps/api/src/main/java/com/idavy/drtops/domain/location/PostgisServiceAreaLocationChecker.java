package com.idavy.drtops.domain.location;

import java.math.BigDecimal;
import java.util.List;
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

    @Override
    public PublishedAreaCheck checkPublishedArea(BigDecimal longitude, BigDecimal latitude) {
        List<PublishedAreaCheck> checks = jdbcTemplate.query("""
                select id,
                       ST_Covers(boundary, ST_SetSRID(ST_MakePoint(?, ?), 4326)::geography) as inside,
                       ST_Distance(boundary, ST_SetSRID(ST_MakePoint(?, ?), 4326)::geography) as distance_meters
                  from service_areas
                 where enabled
                   and published_at is not null
                 order by distance_meters asc
                 limit 1
                """, (resultSet, rowNumber) -> new PublishedAreaCheck(
                resultSet.getBoolean("inside"),
                resultSet.getObject("id", java.util.UUID.class),
                resultSet.getDouble("distance_meters")), longitude, latitude, longitude, latitude);
        return checks.isEmpty() ? new PublishedAreaCheck(false, null, null) : checks.getFirst();
    }
}
