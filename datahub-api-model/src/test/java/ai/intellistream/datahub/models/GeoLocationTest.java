// SPDX-License-Identifier: Apache-2.0
package ai.intellistream.datahub.models;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GeoLocationTest {

    private static final String POINT = "{\"type\":\"Point\",\"coordinates\":[10.75,59.91]}";
    private static final String POLYGON = "{\"type\":\"Polygon\",\"coordinates\":[[[0,0],[1,0],[1,1],[0,0]]]}";

    @Test
    void nullGeometryIsValid() {
        assertTrue(new GeoLocation().isValidGeoJson());
    }

    @Test
    void validPointAndPolygonAccepted() {
        assertTrue(new GeoLocation(POINT).isValidGeoJson());
        assertTrue(new GeoLocation(POLYGON).isValidGeoJson());
    }

    @Test
    void geometryCollectionAccepted() {
        assertTrue(new GeoLocation("{\"type\":\"GeometryCollection\",\"geometries\":[]}").isValidGeoJson());
    }

    @Test
    void missingTypeRejected() {
        assertFalse(new GeoLocation("{\"coordinates\":[1,2]}").isValidGeoJson());
    }

    @Test
    void unknownGeometryTypeRejected() {
        assertFalse(new GeoLocation("{\"type\":\"Banana\",\"coordinates\":[1,2]}").isValidGeoJson());
    }

    @Test
    void missingCoordinatesRejected() {
        assertFalse(new GeoLocation("{\"type\":\"Point\"}").isValidGeoJson());
    }

    @Test
    void malformedJsonRejected() {
        assertFalse(new GeoLocation("not json").isValidGeoJson());
    }

    @Test
    void pointCoordinatesExtractedAsLonLat() {
        assertArrayEquals(new double[]{10.75, 59.91}, new GeoLocation(POINT).pointCoordinates(), 1e-9);
    }

    @Test
    void nonPointHasNoPointCoordinates() {
        assertNull(new GeoLocation(POLYGON).pointCoordinates());
    }

    @Test
    void geometryTypeReported() {
        assertEquals("Point", new GeoLocation(POINT).geometryType());
        assertEquals("Polygon", new GeoLocation(POLYGON).geometryType());
        assertNull(new GeoLocation("garbage").geometryType());
    }
}
