package kr.local.transitglance;

import android.content.Context;

import org.json.JSONException;
import org.json.JSONObject;

/** 위젯·알림에 그릴 마지막 결과. */
final class Snapshot {
    final String title;
    final String body;
    final String footer;

    Snapshot(String title, String body, String footer) {
        this.title = title;
        this.body = body;
        this.footer = footer;
    }

    void save(Context c) {
        try {
            Vault.put(c, "snap", new JSONObject().put("t", title).put("b", body).put("f", footer).toString());
        } catch (JSONException ignored) {
            // 저장 실패는 화면 표시에 영향이 없다.
        }
    }

    static Snapshot load(Context c) {
        String raw = Vault.get(c, "snap");
        if (raw != null) {
            try {
                JSONObject o = new JSONObject(raw);
                return new Snapshot(o.optString("t"), o.optString("b"), o.optString("f"));
            } catch (JSONException ignored) {
                // 아래 기본값으로 대체
            }
        }
        return new Snapshot("교통 도착 정보", "탭하면 실시간 갱신을 시작합니다.", "");
    }
}
