package com.idavy.drtops.domain.location;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
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
                    and boundary is not null
                    and ST_Covers(
                      boundary,
                      ST_SetSRID(ST_MakePoint(?, ?), 4326)::geography)
                )
                """, Boolean.class, longitude, latitude);
        return Boolean.TRUE.equals(covered);
    }

    @Override
    public PublishedAreaCheck checkPublishedArea(BigDecimal longitude, BigDecimal latitude) {
        return queryPublishedArea("""
                select id,
                       ST_Covers(boundary, ST_SetSRID(ST_MakePoint(?, ?), 4326)::geography) as inside,
                       ST_Distance(boundary, ST_SetSRID(ST_MakePoint(?, ?), 4326)::geography) as distance_meters
                  from service_areas
                 where enabled
                   and boundary is not null
                   and published_at is not null
                 order by distance_meters asc
                 limit 1
                """, longitude, latitude, longitude, latitude);
    }

    @Override
    public PublishedAreaCheck checkPublishedArea(UUID serviceAreaId, BigDecimal longitude, BigDecimal latitude) {
        return queryPublishedArea("""
                select id,
                       ST_Covers(boundary, ST_SetSRID(ST_MakePoint(?, ?), 4326)::geography) as inside,
                       ST_Distance(boundary, ST_SetSRID(ST_MakePoint(?, ?), 4326)::geography) as distance_meters
                  from service_areas
                 where id = ?
                   and enabled
                   and boundary is not null
                   and published_at is not null
                 limit 1
                """, longitude, latitude, longitude, latitude, serviceAreaId);
    }

    private PublishedAreaCheck queryPublishedArea(String sql, Object... arguments) {
        List<PublishedAreaCheck> checks = jdbcTemplate.query(sql, (resultSet, rowNumber) -> new PublishedAreaCheck(
                resultSet.getBoolean("inside"),
                resultSet.getObject("id", java.util.UUID.class),
                resultSet.getDouble("distance_meters")), arguments);
        return checks.isEmpty() ? new PublishedAreaCheck(false, null, null) : checks.getFirst();
    }
}
