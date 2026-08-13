package com.idavy.drtops.domain.location;

import java.math.BigDecimal;
import java.math.RoundingMode;
import org.springframework.stereotype.Component;

@Component
public class CoordinateTransformer {
    private static final double EARTH_RADIUS = 6378245d;
    private static final double ECCENTRICITY_SQUARED = 0.00669342162296594323d;
    private static final double PI = Math.PI;

    public StandardizedCoordinate transform(BigDecimal longitude, BigDecimal latitude, String coordinateSystem) {
        validate(longitude, latitude, coordinateSystem);
        if ("GCJ02".equals(coordinateSystem)) {
            return new StandardizedCoordinate(longitude, latitude, "GCJ02", "IDENTITY_GCJ02_V1");
        }
        double lon = longitude.doubleValue();
        double lat = latitude.doubleValue();
        if (outsideChina(lon, lat)) {
            return new StandardizedCoordinate(longitude, latitude, "GCJ02", "WGS84_GCJ02_V1");
        }
        double latitudeOffset = transformLatitude(lon - 105, lat - 35);
        double longitudeOffset = transformLongitude(lon - 105, lat - 35);
        double radians = lat / 180d * PI;
        double magic = 1 - ECCENTRICITY_SQUARED * Math.sin(radians) * Math.sin(radians);
        double rootMagic = Math.sqrt(magic);
        double convertedLongitude = lon + longitudeOffset * 180d / (EARTH_RADIUS / rootMagic * Math.cos(radians) * PI);
        double convertedLatitude = lat + latitudeOffset * 180d
                / ((EARTH_RADIUS * (1 - ECCENTRICITY_SQUARED)) / (magic * rootMagic) * PI);
        return new StandardizedCoordinate(decimal(convertedLongitude), decimal(convertedLatitude), "GCJ02", "WGS84_GCJ02_V1");
    }

    private static void validate(BigDecimal longitude, BigDecimal latitude, String coordinateSystem) {
        if (longitude == null || latitude == null || longitude.compareTo(new BigDecimal("-180")) < 0
                || longitude.compareTo(new BigDecimal("180")) > 0 || latitude.compareTo(new BigDecimal("-90")) < 0
                || latitude.compareTo(new BigDecimal("90")) > 0
                || (!"WGS84".equals(coordinateSystem) && !"GCJ02".equals(coordinateSystem))) {
            throw new IllegalArgumentException("invalid coordinate");
        }
    }

    private static BigDecimal decimal(double value) { return BigDecimal.valueOf(value).setScale(7, RoundingMode.HALF_UP); }
    private static boolean outsideChina(double lon, double lat) { return lon < 72.004 || lon > 137.8347 || lat < 0.8293 || lat > 55.8271; }
    private static double transformLatitude(double lon, double lat) {
        double result = -100 + 2 * lon + 3 * lat + .2 * lat * lat + .1 * lon * lat + .2 * Math.sqrt(Math.abs(lon));
        result += (20 * Math.sin(6 * lon * PI) + 20 * Math.sin(2 * lon * PI)) * 2 / 3d;
        result += (20 * Math.sin(lat * PI) + 40 * Math.sin(lat / 3 * PI)) * 2 / 3d;
        return result + (160 * Math.sin(lat / 12 * PI) + 320 * Math.sin(lat * PI / 30)) * 2 / 3d;
    }
    private static double transformLongitude(double lon, double lat) {
        double result = 300 + lon + 2 * lat + .1 * lon * lon + .1 * lon * lat + .1 * Math.sqrt(Math.abs(lon));
        result += (20 * Math.sin(6 * lon * PI) + 20 * Math.sin(2 * lon * PI)) * 2 / 3d;
        result += (20 * Math.sin(lon * PI) + 40 * Math.sin(lon / 3 * PI)) * 2 / 3d;
        return result + (150 * Math.sin(lon / 12 * PI) + 300 * Math.sin(lon / 30 * PI)) * 2 / 3d;
    }

    public record StandardizedCoordinate(BigDecimal longitude, BigDecimal latitude, String coordinateSystem, String transformVersion) { }
}
