package com.idavy.drtops.domain.location;

import org.locationtech.jts.geom.Geometry;
import org.locationtech.jts.geom.GeometryFactory;
import org.locationtech.jts.geom.Point;
import org.locationtech.jts.geom.PrecisionModel;
import org.locationtech.jts.io.ParseException;
import org.locationtech.jts.io.WKTReader;

public final class GeographyPoint {

    private static final int WGS84_SRID = 4326;
    private static final GeometryFactory GEOMETRY_FACTORY =
            new GeometryFactory(new PrecisionModel(), WGS84_SRID);

    private GeographyPoint() {
    }

    public static Point fromWkt(String wkt) {
        try {
            Geometry geometry = new WKTReader(GEOMETRY_FACTORY).read(wkt);
            if (geometry instanceof Point point) {
                return point;
            }
            throw new IllegalArgumentException("location must be a POINT");
        } catch (ParseException exception) {
            throw new IllegalArgumentException("location must be valid WKT", exception);
        }
    }

    public static String toWkt(Point point) {
        if (point == null) {
            return null;
        }
        return point.toText().replace("POINT ", "POINT");
    }
}
