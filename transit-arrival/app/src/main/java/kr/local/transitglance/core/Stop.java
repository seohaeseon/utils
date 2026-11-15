package kr.local.transitglance.core;

import org.json.JSONException;
import org.json.JSONObject;

/** 사용자가 등록한 정류장/역. 좌표는 기기 안에서 거리 계산에만 쓰이며 네트워크 요청에 절대 포함되지 않는다. */
public final class Stop {
    public static final int TAGO_BUS = 1;      // 전국 버스 (국토교통부 TAGO)
    public static final int SEOUL_SUBWAY = 2;  // 서울 지하철 실시간
    public static final int SEOUL_BUS = 3;     // 서울 버스

    public final int type;
    public final String cityCode; // TAGO 전용, 그 외 ""
    public final String stopId;   // TAGO nodeId / 지하철 역명 / 서울버스 arsId
    public final String name;
    public final double lat;      // 모르면 NaN
    public final double lon;
    public final String routes;   // 쉼표로 구분한 노선 필터, ""이면 전체

    public Stop(int type, String cityCode, String stopId, String name, double lat, double lon, String routes) {
        this.type = type;
        this.cityCode = cityCode == null ? "" : cityCode;
        this.stopId = stopId;
        this.name = name;
        this.lat = lat;
        this.lon = lon;
        this.routes = routes == null ? "" : routes.trim();
    }

    public boolean hasLocation() {
        return !Double.isNaN(lat) && !Double.isNaN(lon);
    }

    public Stop withLocation(double newLat, double newLon) {
        return new Stop(type, cityCode, stopId, name, newLat, newLon, routes);
    }

    public Stop withRoutes(String newRoutes) {
        return new Stop(type, cityCode, stopId, name, lat, lon, newRoutes);
    }

    public String typeLabel() {
        switch (type) {
            case TAGO_BUS: return "버스";
            case SEOUL_SUBWAY: return "지하철";
            case SEOUL_BUS: return "서울버스";
            default: return "?";
        }
    }

    public JSONObject toJson() throws JSONException {
        JSONObject o = new JSONObject();
        o.put("t", type).put("c", cityCode).put("i", stopId).put("n", name).put("r", routes);
        if (hasLocation()) o.put("la", lat).put("lo", lon);
        return o;
    }

    public static Stop fromJson(JSONObject o) throws JSONException {
        return new Stop(o.getInt("t"), o.optString("c", ""), o.getString("i"), o.getString("n"),
                o.optDouble("la", Double.NaN), o.optDouble("lo", Double.NaN), o.optString("r", ""));
    }
}
