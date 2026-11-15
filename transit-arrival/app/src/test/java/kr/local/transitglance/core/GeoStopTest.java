package kr.local.transitglance.core;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;

public class GeoStopTest {
    @Test
    public void distanceAndNearest() {
        // 서울시청 ↔ 강남역 약 8.6km
        double d = Geo.meters(37.5665, 126.9780, 37.4979, 127.0276);
        assertTrue(d > 8000 && d < 9500);
        Stop a = new Stop(Stop.TAGO_BUS, "25", "A", "a", 37.5665, 126.9780, "");
        Stop noLoc = new Stop(Stop.SEOUL_SUBWAY, "", "x", "x", Double.NaN, Double.NaN, "");
        Stop b = new Stop(Stop.TAGO_BUS, "25", "B", "b", 37.4979, 127.0276, "");
        assertEquals(2, Geo.nearest(Arrays.asList(a, noLoc, b), 37.50, 127.03));
        assertEquals(0, Geo.nearest(Arrays.asList(a, noLoc, b), 37.56, 126.98));
        assertEquals(-1, Geo.nearest(Collections.singletonList(noLoc), 37.5, 127.0));
    }

    @Test
    public void stopJsonRoundTrip() throws Exception {
        Stop s = new Stop(Stop.SEOUL_BUS, "", "01126", "광화문", 37.57, 126.97, "7016");
        Stop r = Stop.fromJson(s.toJson());
        assertEquals(s.stopId, r.stopId);
        assertEquals(s.lat, r.lat, 0);
        assertEquals("7016", r.routes);
        Stop n = Stop.fromJson(new Stop(Stop.SEOUL_SUBWAY, "", "강남", "강남역", Double.NaN, Double.NaN, "").toJson());
        assertFalse(n.hasLocation());
    }
}
