package com.idavy.drtops.domain.area;

import java.math.BigDecimal;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Comparator;
import java.util.UUID;
import org.springframework.stereotype.Service;

@Service
public class VirtualStopMatcher {

    private static final double EARTH_RADIUS_METERS = 6_371_000.0;

    private final VirtualStopRepository virtualStopRepository;

    public VirtualStopMatcher(VirtualStopRepository virtualStopRepository) {
        this.virtualStopRepository = virtualStopRepository;
    }

    public VirtualStopMatch matchStops(
            BigDecimal originLng,
            BigDecimal originLat,
            BigDecimal destinationLng,
            BigDecimal destinationLat,
            Instant requestedDepartureAt) {
        MatchedStop boarding = matchNearest(originLng, originLat, true);
        MatchedStop alighting = matchNearest(destinationLng, destinationLat, false);
        return new VirtualStopMatch(
                boarding.stopId(),
                alighting.stopId(),
                boarding.distanceMeters(),
                alighting.distanceMeters());
    }

    private MatchedStop matchNearest(BigDecimal lng, BigDecimal lat, boolean boarding) {
        return virtualStopRepository.findAll().stream()
                .filter(VirtualStop::isEnabled)
                .filter(stop -> boarding ? stop.isBoardingEnabled() : stop.isAlightingEnabled())
                .map(stop -> toMatchedStop(stop, lng.doubleValue(), lat.doubleValue()))
                .filter(match -> match.distanceMeters() <= match.serviceRadiusMeters())
                .min(Comparator.comparingDouble(MatchedStop::distanceMeters))
                .orElseThrow(() -> new NoMatchedStopException(boarding ? "boarding stop not found" : "alighting stop not found"));
    }

    private MatchedStop toMatchedStop(VirtualStop stop, double lng, double lat) {
        Coordinate coordinate = Coordinate.fromPointValue(stop.getLocation());
        double distanceMeters = haversineMeters(lat, lng, coordinate.lat(), coordinate.lng());
        return new MatchedStop(stop.getId(), distanceMeters, stop.getServiceRadiusMeters());
    }

    private static double haversineMeters(double lat1, double lng1, double lat2, double lng2) {
        double latRadians1 = Math.toRadians(lat1);
        double latRadians2 = Math.toRadians(lat2);
        double deltaLat = Math.toRadians(lat2 - lat1);
        double deltaLng = Math.toRadians(lng2 - lng1);
        double a = Math.sin(deltaLat / 2) * Math.sin(deltaLat / 2)
                + Math.cos(latRadians1) * Math.cos(latRadians2)
                * Math.sin(deltaLng / 2) * Math.sin(deltaLng / 2);
        double c = 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
        return EARTH_RADIUS_METERS * c;
    }

    public record VirtualStopMatch(
            UUID boardingStopId,
            UUID alightingStopId,
            double boardingDistanceMeters,
            double alightingDistanceMeters) {
    }

    public static class NoMatchedStopException extends RuntimeException {
        public NoMatchedStopException(String message) {
            super(message);
        }
    }

    private record MatchedStop(UUID stopId, double distanceMeters, int serviceRadiusMeters) {
    }

    private record Coordinate(double lng, double lat) {
        static Coordinate fromPointValue(String pointValue) {
            String trimmed = pointValue.trim();
            if (trimmed.startsWith("SRID=")) {
                int separator = trimmed.indexOf(';');
                if (separator < 0) {
                    throw unsupportedPointValue(pointValue);
                }
                trimmed = trimmed.substring(separator + 1);
            }
            if (trimmed.startsWith("POINT(")) {
                return fromPointWkt(trimmed, pointValue);
            }
            return fromPostgisEwkb(trimmed, pointValue);
        }

        private static Coordinate fromPointWkt(String pointWkt, String originalValue) {
            String trimmed = pointWkt.trim();
            if (!trimmed.startsWith("POINT(") || !trimmed.endsWith(")")) {
                throw unsupportedPointValue(originalValue);
            }
            String[] parts = trimmed.substring("POINT(".length(), trimmed.length() - 1).split("\\s+");
            if (parts.length != 2) {
                throw unsupportedPointValue(originalValue);
            }
            return new Coordinate(Double.parseDouble(parts[0]), Double.parseDouble(parts[1]));
        }

        private static Coordinate fromPostgisEwkb(String ewkb, String originalValue) {
            byte[] bytes;
            try {
                bytes = HexFormat.of().parseHex(ewkb);
            } catch (IllegalArgumentException exception) {
                throw unsupportedPointValue(originalValue);
            }
            if (bytes.length < 21) {
                throw unsupportedPointValue(originalValue);
            }

            ByteOrder byteOrder = switch (bytes[0]) {
                case 0 -> ByteOrder.BIG_ENDIAN;
                case 1 -> ByteOrder.LITTLE_ENDIAN;
                default -> throw unsupportedPointValue(originalValue);
            };
            ByteBuffer buffer = ByteBuffer.wrap(bytes).order(byteOrder);
            buffer.get();
            int geometryType = buffer.getInt();
            boolean includesSrid = (geometryType & 0x20000000) != 0;
            if ((geometryType & 0x0FFFFFFF) != 1) {
                throw unsupportedPointValue(originalValue);
            }
            if (includesSrid) {
                if (buffer.remaining() < 20) {
                    throw unsupportedPointValue(originalValue);
                }
                buffer.getInt();
            } else if (buffer.remaining() < 16) {
                throw unsupportedPointValue(originalValue);
            }
            return new Coordinate(buffer.getDouble(), buffer.getDouble());
        }

        private static IllegalArgumentException unsupportedPointValue(String pointValue) {
            return new IllegalArgumentException("Unsupported point value: " + pointValue);
        }
    }
}
