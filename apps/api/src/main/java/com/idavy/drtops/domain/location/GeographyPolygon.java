package com.idavy.drtops.domain.location;

import org.locationtech.jts.geom.Geometry;
import org.locationtech.jts.geom.GeometryFactory;
import org.locationtech.jts.geom.Polygon;
import org.locationtech.jts.geom.PrecisionModel;
import org.locationtech.jts.io.ParseException;
import org.locationtech.jts.io.WKTReader;

public final class GeographyPolygon {

    private static final int WGS84_SRID = 4326;
    private static final GeometryFactory GEOMETRY_FACTORY =
            new GeometryFactory(new PrecisionModel(), WGS84_SRID);

    private GeographyPolygon() {
    }

    public static Polygon fromWkt(String wkt) {
        if (wkt == null) {
            return null;
        }
        try {
            Geometry geometry = new WKTReader(GEOMETRY_FACTORY).read(wkt);
            if (geometry instanceof Polygon polygon) {
                return polygon;
            }
            throw new IllegalArgumentException("boundary must be a POLYGON");
        } catch (ParseException exception) {
            throw new IllegalArgumentException("boundary must be valid WKT", exception);
        }
    }

    public static String toWkt(Polygon polygon) {
        return polygon == null ? null : polygon.toText();
    }
}
