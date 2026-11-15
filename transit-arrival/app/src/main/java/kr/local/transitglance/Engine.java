package kr.local.transitglance;

import android.content.Context;
import android.location.Location;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;

import kr.local.transitglance.core.Arrival;
import kr.local.transitglance.core.Geo;
import kr.local.transitglance.core.Stop;
import kr.local.transitglance.core.Transit;
import kr.local.transitglance.core.TransitException;

/** 한 번의 갱신: (필요하면 기기 안에서 가장 가까운 정류장 선택) → 그 정류장의 도착 정보 조회 → 위젯 반영. */
final class Engine {
    private Engine() {}

    private static final Object LOCK = new Object();
    private static final int MAX_LINES = 5;

    /** 워커 스레드에서 호출. activeLocate가 false면 새 측위 없이 최근 위치만 참고한다. */
    static Snapshot refresh(Context context, boolean activeLocate) {
        Context app = context.getApplicationContext();
        synchronized (LOCK) {
            Snapshot s = compute(app, activeLocate);
            s.save(app);
            ArrivalWidget.push(app, s);
            return s;
        }
    }

    private static Snapshot compute(Context app, boolean activeLocate) {
        Config cfg = Config.load(app);
        if (cfg.stops.isEmpty()) return new Snapshot("정류장 미등록", "앱을 열어 정류장/역을 등록하세요.", "");

        int idx = clamp(cfg.selected, cfg.stops.size());
        String note = "";
        if (cfg.mode == Config.MODE_NEAREST) {
            // 위치는 이 블록 안에서 거리 계산에만 쓰고 저장·전송하지 않는다. 남기는 것은 "몇 번째 정류장인지"뿐.
            Location loc = Locator.current(app, cfg.gpsOnly, !activeLocate);
            int n = loc == null ? -1 : Geo.nearest(cfg.stops, loc.getLatitude(), loc.getLongitude());
            if (n >= 0) {
                idx = n;
                note = " · 가장 가까운 곳";
                if (cfg.lastNearest != n) {
                    cfg.lastNearest = n;
                    cfg.save(app);
                }
            } else {
                if (cfg.lastNearest >= 0) idx = clamp(cfg.lastNearest, cfg.stops.size());
                note = loc == null ? " · 위치 확인 불가(최근 정류장)" : " · 좌표가 등록된 정류장 없음";
            }
        }

        Stop stop = cfg.stops.get(idx);
        String title = "[" + stop.typeLabel() + "] " + stop.name;
        String time = new SimpleDateFormat("HH:mm:ss", Locale.KOREA).format(new Date());
        String key = Vault.get(app, Config.keyName(stop.type));
        if (key == null) return new Snapshot(title, "앱에서 인증키를 설정하세요.", time + note);
        try {
            List<Arrival> list = Transit.arrivals(stop, key);
            if (list.isEmpty()) return new Snapshot(title, "도착 예정 정보가 없습니다.", time + " 기준" + note);
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < list.size() && i < MAX_LINES; i++) {
                if (i > 0) sb.append('\n');
                sb.append(list.get(i).line());
            }
            return new Snapshot(title, sb.toString(), time + " 기준" + note);
        } catch (TransitException e) {
            return new Snapshot(title, e.getMessage(), time + note);
        } catch (RuntimeException e) {
            return new Snapshot(title, "처리 중 오류가 발생했습니다.", time + note);
        }
    }

    private static int clamp(int i, int size) {
        return i < 0 || i >= size ? 0 : i;
    }
}
