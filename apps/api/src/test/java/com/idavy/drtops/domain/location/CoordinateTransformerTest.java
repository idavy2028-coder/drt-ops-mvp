package com.idavy.drtops.domain.location;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

class CoordinateTransformerTest {

    private final CoordinateTransformer transformer = new CoordinateTransformer();

    @Test
    void convertsTongweiWgs84ReferencePointToApprovedGcj02WithinFiveMeters() {
        CoordinateTransformer.StandardizedCoordinate coordinate = transformer.transform(
                new BigDecimal("105.2384988"), new BigDecimal("35.2109657"), "WGS84");

        assertThat(coordinate.longitude()).isCloseTo(new BigDecimal("105.2421000"),
                org.assertj.core.data.Offset.offset(new BigDecimal("0.000055")));
        assertThat(coordinate.latitude()).isCloseTo(new BigDecimal("35.2103000"),
                org.assertj.core.data.Offset.offset(new BigDecimal("0.000045")));
        assertThat(coordinate.coordinateSystem()).isEqualTo("GCJ02");
        assertThat(coordinate.transformVersion()).isEqualTo("WGS84_GCJ02_V1");
    }

    @Test
    void keepsGcj02CoordinatesUnchangedInsteadOfTransformingTwice() {
        CoordinateTransformer.StandardizedCoordinate coordinate = transformer.transform(
                new BigDecimal("105.2421000"), new BigDecimal("35.2103000"), "GCJ02");

        assertThat(coordinate.longitude()).isEqualByComparingTo("105.2421000");
        assertThat(coordinate.latitude()).isEqualByComparingTo("35.2103000");
        assertThat(coordinate.transformVersion()).isEqualTo("IDENTITY_GCJ02_V1");
    }

    @Test
    void rejectsNonFiniteOrOutOfRangeCoordinatesAndUnknownCoordinateSystems() {
        assertThatThrownBy(() -> transformer.transform(new BigDecimal("181"), BigDecimal.ZERO, "WGS84"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> transformer.transform(BigDecimal.ZERO, new BigDecimal("91"), "GCJ02"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> transformer.transform(BigDecimal.ONE, BigDecimal.ONE, "BD09"))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
