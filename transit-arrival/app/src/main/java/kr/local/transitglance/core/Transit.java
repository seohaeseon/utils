package kr.local.transitglance.core;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/** 정류장 하나의 도착 정보를 가져오는 진입점. 인자는 정류장 식별자와 키뿐이며 위치는 받지 않는다. */
public final class Transit {
    private Transit() {}

    public static List<Arrival> arrivals(Stop stop, String key) throws TransitException {
        List<Arrival> all;
        switch (stop.type) {
            case Stop.TAGO_BUS:
                all = Parsers.tagoArrivals(SecureHttp.get(Endpoints.tagoArrivals(key, stop.cityCode, stop.stopId)));
                break;
            case Stop.SEOUL_SUBWAY:
                all = Parsers.seoulSubwayArrivals(SecureHttp.get(Endpoints.seoulSubwayArrivals(key, stop.stopId)),
                        System.currentTimeMillis());
                break;
            case Stop.SEOUL_BUS:
                all = Parsers.seoulBusArrivals(SecureHttp.get(Endpoints.seoulBusArrivals(key, stop.stopId)));
                break;
            default:
                throw new TransitException("알 수 없는 정류장 종류입니다.");
        }
        return sortAndFilter(all, stop);
    }

    /** 노선 필터 적용 후 빨리 오는 순으로 정렬. */
    public static List<Arrival> sortAndFilter(List<Arrival> all, Stop stop) {
        List<String> tokens = new ArrayList<>();
        for (String t : stop.routes.split(",")) {
            String s = t.trim().toLowerCase(Locale.ROOT);
            if (!s.isEmpty()) tokens.add(s);
        }
        List<Arrival> out = new ArrayList<>();
        for (Arrival a : all) {
            if (tokens.isEmpty() || matches(a, tokens, stop.type == Stop.SEOUL_SUBWAY)) out.add(a);
        }
        Collections.sort(out, (x, y) -> Integer.compare(rank(x), rank(y)));
        return out;
    }

    private static boolean matches(Arrival a, List<String> tokens, boolean subway) {
        String route = a.route.toLowerCase(Locale.ROOT);
        String detail = a.detail.toLowerCase(Locale.ROOT);
        for (String t : tokens) {
            // 버스는 번호가 정확히 같아야 하고(7 ≠ 707), 지하철은 호선/방면에 포함되면 된다.
            if (subway ? (route.contains(t) || detail.contains(t)) : route.equals(t)) return true;
        }
        return false;
    }

    private static int rank(Arrival a) {
        return a.seconds < 0 ? Integer.MAX_VALUE : a.seconds;
    }

    /** 지하철 역명 정규화: "강남역" → "강남" (API는 '역'을 뺀 이름을 받는다). */
    public static String normalizeStation(String raw) {
        String s = raw == null ? "" : raw.trim();
        if (s.length() > 1 && s.endsWith("역")) s = s.substring(0, s.length() - 1);
        return s;
    }
}
