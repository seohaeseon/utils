package kr.local.transitglance.core;

import java.util.List;

/** 기기 내부에서만 수행하는 거리 계산. 네트워크 코드에 대한 의존이 없다. */
public final class Geo {
    private Geo() {}

    public static double meters(double lat1, double lon1, double lat2, double lon2) {
        double r = 6371000.0;
        double dLat = Math.toRadians(lat2 - lat1);
        double dLon = Math.toRadians(lon2 - lon1);
        double a = Math.sin(dLat / 2) * Math.sin(dLat / 2)
                + Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2))
                * Math.sin(dLon / 2) * Math.sin(dLon / 2);
        return 2 * r * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
    }

    /** 좌표가 있는 정류장 중 가장 가까운 것의 인덱스. 없으면 -1. */
    public static int nearest(List<Stop> stops, double lat, double lon) {
        int best = -1;
        double bestD = Double.MAX_VALUE;
        for (int i = 0; i < stops.size(); i++) {
            Stop s = stops.get(i);
            if (!s.hasLocation()) continue;
            double d = meters(lat, lon, s.lat, s.lon);
            if (d < bestD) {
                bestD = d;
                best = i;
            }
        }
        return best;
    }
}
