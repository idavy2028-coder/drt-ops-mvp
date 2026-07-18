package com.idavy.drtops.domain.area;

import com.idavy.drtops.domain.location.GeographyPolygon;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import org.locationtech.jts.geom.Polygon;

@Entity
@Table(name = "service_areas")
public class ServiceArea {

    @Id
    private UUID id;

    @Column(nullable = false, length = 100)
    private String name;

    @JdbcTypeCode(SqlTypes.GEOGRAPHY)
    @Column(columnDefinition = "geometry")
    private Polygon boundary;

    @JdbcTypeCode(SqlTypes.GEOGRAPHY)
    @Column(columnDefinition = "geometry")
    private Polygon draftBoundary;

    @Column(nullable = false)
    private LocalTime serviceStart;

    @Column(nullable = false)
    private LocalTime serviceEnd;

    @Column(nullable = false)
    private UUID ruleSetId;

    @Column(nullable = false)
    private boolean enabled;

    @Column(nullable = false, length = 40)
    private String boundarySource;

    @Column(nullable = false)
    private int boundaryVersion;

    @Column(length = 40)
    private String draftBoundarySource;

    @Column(nullable = false)
    private int draftBoundaryVersion;

    @Column(length = 20, nullable = false)
    private String coordinateSystem;

    private OffsetDateTime publishedAt;

    @Column(nullable = false)
    private OffsetDateTime createdAt;

    @Column(nullable = false)
    private OffsetDateTime updatedAt;

    @Version
    private long version;

    protected ServiceArea() {
    }

    private ServiceArea(
            UUID id,
            String name,
            String boundary,
            String draftSource,
            LocalTime serviceStart,
            LocalTime serviceEnd,
            UUID ruleSetId) {
        this.id = id;
        this.name = name;
        this.boundary = null;
        this.draftBoundary = GeographyPolygon.fromWkt(boundary);
        this.serviceStart = serviceStart;
        this.serviceEnd = serviceEnd;
        this.ruleSetId = ruleSetId;
        this.enabled = true;
        this.boundarySource = "LEGACY";
        this.boundaryVersion = 0;
        this.draftBoundarySource = draftSource;
        this.draftBoundaryVersion = 1;
        this.coordinateSystem = "GCJ02";
        this.createdAt = OffsetDateTime.now();
        this.updatedAt = createdAt;
    }

    public static ServiceArea create(
            UUID id,
            String name,
            String boundaryWkt,
            String serviceStart,
            String serviceEnd,
            UUID ruleSetId) {
        return new ServiceArea(
                id,
                name,
                boundaryWkt,
                "MANUAL",
                LocalTime.parse(serviceStart),
                LocalTime.parse(serviceEnd),
                ruleSetId);
    }

    static ServiceArea createImportedDraft(
            UUID id,
            String name,
            String boundaryWkt,
            String serviceStart,
            String serviceEnd,
            UUID ruleSetId) {
        return new ServiceArea(
                id,
                name,
                boundaryWkt,
                "AMAP_DISTRICT",
                LocalTime.parse(serviceStart),
                LocalTime.parse(serviceEnd),
                ruleSetId);
    }

    public UUID getId() {
        return id;
    }

    public String getName() {
        return name;
    }

    public String getBoundary() {
        return GeographyPolygon.toWkt(boundary);
    }

    public String getDraftBoundary() {
        return GeographyPolygon.toWkt(draftBoundary);
    }

    public LocalTime getServiceStart() {
        return serviceStart;
    }

    public LocalTime getServiceEnd() {
        return serviceEnd;
    }

    public UUID getRuleSetId() {
        return ruleSetId;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public String getBoundarySource() {
        return boundarySource;
    }

    public int getBoundaryVersion() {
        return boundaryVersion;
    }

    public String getDraftBoundarySource() {
        return draftBoundarySource;
    }

    public int getDraftBoundaryVersion() {
        return draftBoundaryVersion;
    }

    public String getCoordinateSystem() {
        return coordinateSystem;
    }

    public OffsetDateTime getPublishedAt() {
        return publishedAt;
    }

    public OffsetDateTime getUpdatedAt() {
        return updatedAt;
    }

    Polygon getPublishedBoundaryGeometry() {
        return boundary;
    }

    Polygon getDraftBoundaryGeometry() {
        return draftBoundary;
    }

    long getVersion() {
        return version;
    }

    void replaceDraftBoundary(String boundaryWkt, String source) {
        draftBoundary = GeographyPolygon.fromWkt(boundaryWkt);
        draftBoundarySource = source;
        draftBoundaryVersion++;
        coordinateSystem = "GCJ02";
        updatedAt = OffsetDateTime.now();
    }

    void publishDraft() {
        if (draftBoundary == null) {
            throw new IllegalStateException("服务区没有可发布的边界草稿");
        }
        boundary = draftBoundary;
        boundarySource = draftBoundarySource;
        boundaryVersion++;
        publishedAt = OffsetDateTime.now();
        enabled = true;
        coordinateSystem = "GCJ02";
        updatedAt = publishedAt;
    }
}
