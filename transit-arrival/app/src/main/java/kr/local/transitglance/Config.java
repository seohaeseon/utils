package kr.local.transitglance;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

import kr.local.transitglance.core.Stop;

/** 사용자 설정. 전체가 Vault(암호화 저장소)에 한 덩어리로 들어간다. */
final class Config {
    static final int MODE_FIXED = 0;
    static final int MODE_NEAREST = 1;

    final List<Stop> stops = new ArrayList<>();
    int mode = MODE_FIXED;
    int selected = 0;        // 지정 모드에서 보여줄 정류장
    int lastNearest = -1;    // 가까운 모드에서 마지막으로 고른 정류장(인덱스만 저장, 좌표는 저장하지 않음)
    boolean showOnLock = true;
    boolean gpsOnly = true;
    int liveMinutes = 20;

    static Config load(Context c) {
        Config cfg = new Config();
        String raw = Vault.get(c, "cfg");
        if (raw == null) return cfg;
        try {
            JSONObject o = new JSONObject(raw);
            JSONArray arr = o.optJSONArray("stops");
            for (int i = 0; arr != null && i < arr.length() && i < 30; i++) cfg.stops.add(Stop.fromJson(arr.getJSONObject(i)));
            cfg.mode = o.optInt("mode", MODE_FIXED) == MODE_NEAREST ? MODE_NEAREST : MODE_FIXED;
            cfg.selected = o.optInt("sel", 0);
            cfg.lastNearest = o.optInt("near", -1);
            cfg.showOnLock = o.optBoolean("lock", true);
            cfg.gpsOnly = o.optBoolean("gps", true);
            cfg.liveMinutes = Math.max(5, Math.min(120, o.optInt("mins", 20)));
        } catch (JSONException e) {
            return new Config();
        }
        return cfg;
    }

    void save(Context c) {
        try {
            JSONArray arr = new JSONArray();
            for (Stop s : stops) arr.put(s.toJson());
            JSONObject o = new JSONObject();
            o.put("stops", arr).put("mode", mode).put("sel", selected).put("near", lastNearest)
                    .put("lock", showOnLock).put("gps", gpsOnly).put("mins", liveMinutes);
            Vault.put(c, "cfg", o.toString());
        } catch (JSONException ignored) {
            // 직렬화 실패 시 기존 값을 유지한다.
        }
    }

    static String keyName(int stopType) {
        return "key" + stopType;
    }
}
