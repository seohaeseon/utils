package kr.local.transitglance;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.location.Location;
import android.os.Build;
import android.os.Bundle;
import android.text.InputType;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.RadioGroup;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import kr.local.transitglance.core.Endpoints;
import kr.local.transitglance.core.Parsers;
import kr.local.transitglance.core.SecureHttp;
import kr.local.transitglance.core.Stop;
import kr.local.transitglance.core.Transit;
import kr.local.transitglance.core.TransitException;

/** 유일한 화면: 정류장 등록, 표시 방식, 인증키, 옵션. */
public final class MainActivity extends Activity {
    private static final int REQ_LOCATION = 10;
    private static final int REQ_NOTIFY = 11;
    private static final int MAX_STOPS = 30;

    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private Config cfg;
    private TextView preview;
    private LinearLayout stopList;
    private Button btnLive;
    private List<String[]> cityCache;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        // 화면 캡처·최근 앱 미리보기·화면 녹화에 내용(동선, 키 입력)이 남지 않게 한다.
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);
        setContentView(R.layout.activity_main);
        cfg = Config.load(this);
        preview = findViewById(R.id.preview);
        stopList = findViewById(R.id.stop_list);
        btnLive = findViewById(R.id.btn_live);

        findViewById(R.id.btn_refresh).setOnClickListener(v -> refreshNow());
        btnLive.setOnClickListener(v -> toggleLive());

        RadioGroup mode = findViewById(R.id.mode_group);
        mode.check(cfg.mode == Config.MODE_NEAREST ? R.id.mode_nearest : R.id.mode_fixed);
        mode.setOnCheckedChangeListener((g, id) -> {
            cfg.mode = id == R.id.mode_nearest ? Config.MODE_NEAREST : Config.MODE_FIXED;
            cfg.save(this);
            if (cfg.mode == Config.MODE_NEAREST && !Locator.granted(this)) askLocation();
            renderStops();
        });

        findViewById(R.id.add_tago).setOnClickListener(v -> addTagoStop());
        findViewById(R.id.add_subway).setOnClickListener(v -> addSubwayStation());
        findViewById(R.id.add_seoul_bus).setOnClickListener(v -> addSeoulBusStop());

        bindKeyButton(R.id.key_tago, Stop.TAGO_BUS, "전국 버스 (공공데이터포털 TAGO)");
        bindKeyButton(R.id.key_subway, Stop.SEOUL_SUBWAY, "서울 지하철 (서울 열린데이터광장)");
        bindKeyButton(R.id.key_seoul_bus, Stop.SEOUL_BUS, "서울 버스 (공공데이터포털)");

        CheckBox lock = findViewById(R.id.opt_lock);
        lock.setChecked(cfg.showOnLock);
        lock.setOnCheckedChangeListener((b, on) -> {
            cfg.showOnLock = on;
            cfg.save(this);
        });
        CheckBox gps = findViewById(R.id.opt_gps);
        gps.setChecked(cfg.gpsOnly);
        gps.setOnCheckedChangeListener((b, on) -> {
            cfg.gpsOnly = on;
            cfg.save(this);
        });
        findViewById(R.id.btn_minutes).setOnClickListener(v -> pickMinutes());
        findViewById(R.id.btn_wipe).setOnClickListener(v -> confirmWipe());
        renderStops();
        renderMinutes();
    }

    @Override
    protected void onResume() {
        super.onResume();
        show(Snapshot.load(this));
        btnLive.setText(LiveService.running ? "실시간 중지" : "실시간 시작");
    }

    @Override
    protected void onDestroy() {
        io.shutdownNow();
        super.onDestroy();
    }

    // ---------- 미리보기 / 실시간 ----------

    private void show(Snapshot s) {
        preview.setText(s.title + "\n\n" + s.body + "\n\n" + s.footer);
    }

    private void refreshNow() {
        preview.setText("불러오는 중…");
        io.execute(() -> {
            Snapshot s = Engine.refresh(this, true);
            runOnUiThread(() -> show(s));
        });
    }

    private void toggleLive() {
        if (LiveService.running) {
            stopService(new Intent(this, LiveService.class));
            btnLive.setText("실시간 시작");
            return;
        }
        if (Build.VERSION.SDK_INT >= 33
                && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, REQ_NOTIFY);
            return;
        }
        startLive();
    }

    private void startLive() {
        try {
            startForegroundService(new Intent(this, LiveService.class));
            btnLive.setText("실시간 중지");
            toast(cfg.liveMinutes + "분 동안 30초마다 갱신합니다.");
        } catch (RuntimeException e) {
            toast("실시간 갱신을 시작할 수 없습니다.");
        }
    }

    @Override
    public void onRequestPermissionsResult(int code, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(code, permissions, results);
        if (code == REQ_NOTIFY) {
            startLive(); // 알림을 거부해도 위젯 갱신은 동작한다
        } else if (code == REQ_LOCATION) {
            renderStops();
        }
    }

    private void askLocation() {
        new AlertDialog.Builder(this)
                .setTitle("위치 권한")
                .setMessage("현재 위치는 등록한 정류장 중 가장 가까운 곳을 기기 안에서 고르는 데에만 사용됩니다.\n"
                        + "위치 좌표는 저장되지 않고, 어떤 서버로도 전송되지 않습니다.")
                .setPositiveButton("계속", (d, w) -> requestPermissions(new String[]{
                        Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION}, REQ_LOCATION))
                .setNegativeButton("취소", null)
                .show();
    }

    // ---------- 정류장 목록 ----------

    private void renderStops() {
        stopList.removeAllViews();
        if (cfg.stops.isEmpty()) {
            TextView t = new TextView(this);
            t.setText("등록된 정류장이 없습니다. 아래 버튼으로 추가하세요.");
            stopList.addView(t);
        }
        for (int i = 0; i < cfg.stops.size(); i++) {
            final int idx = i;
            Stop s = cfg.stops.get(i);
            boolean chosen = cfg.mode == Config.MODE_FIXED && cfg.selected == i;
            TextView label = new TextView(this);
            label.setText((chosen ? "✔ " : "") + "[" + s.typeLabel() + "] " + s.name
                    + "\n노선: " + (s.routes.isEmpty() ? "전체" : s.routes)
                    + " · 좌표: " + (s.hasLocation() ? "있음" : "없음(가까운 정류장 판단에서 제외)"));
            label.setPadding(0, 24, 0, 0);
            stopList.addView(label);

            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.addView(smallButton("선택", v -> {
                cfg.selected = idx;
                cfg.save(this);
                renderStops();
            }));
            row.addView(smallButton("노선", v -> editRoutes(idx)));
            row.addView(smallButton("여기로 좌표", v -> captureLocation(idx)));
            row.addView(smallButton("삭제", v -> {
                cfg.stops.remove(idx);
                cfg.selected = 0;
                cfg.lastNearest = -1;
                cfg.save(this);
                renderStops();
            }));
            stopList.addView(row);
        }
    }

    private Button smallButton(String text, View.OnClickListener l) {
        Button b = new Button(this, null, android.R.attr.buttonStyleSmall);
        b.setText(text);
        b.setOnClickListener(l);
        return b;
    }

    private void addStop(Stop s) {
        if (cfg.stops.size() >= MAX_STOPS) {
            toast("정류장은 최대 " + MAX_STOPS + "개까지 등록할 수 있습니다.");
            return;
        }
        cfg.stops.add(s);
        if (cfg.stops.size() == 1) cfg.selected = 0;
        cfg.save(this);
        renderStops();
        toast("추가했습니다: " + s.name);
    }

    private void editRoutes(int idx) {
        Stop s = cfg.stops.get(idx);
        String hint = s.type == Stop.SEOUL_SUBWAY ? "예: 2호선, 성수행 (비우면 전체)" : "예: 7, 7-1, 9401 (비우면 전체)";
        prompt("표시할 노선", hint, s.routes, false, text -> {
            if (idx < cfg.stops.size()) {
                cfg.stops.set(idx, s.withRoutes(text.length() > 100 ? text.substring(0, 100) : text));
                cfg.save(this);
                renderStops();
            }
        });
    }

    /** 지금 서 있는 곳을 이 정류장의 좌표로 기기 안에 저장한다(지하철역처럼 API가 좌표를 주지 않는 경우). */
    private void captureLocation(int idx) {
        if (!Locator.granted(this)) {
            askLocation();
            return;
        }
        toast("현재 위치를 확인하는 중…");
        io.execute(() -> {
            Location l = Locator.current(this, cfg.gpsOnly, false);
            runOnUiThread(() -> {
                if (l == null) {
                    toast("위치를 확인하지 못했습니다. 실외에서 다시 시도하세요.");
                } else if (idx < cfg.stops.size()) {
                    cfg.stops.set(idx, cfg.stops.get(idx).withLocation(l.getLatitude(), l.getLongitude()));
                    cfg.save(this);
                    renderStops();
                    toast("좌표를 기기에 저장했습니다.");
                }
            });
        });
    }

    // ---------- 정류장 추가 ----------

    private void addTagoStop() {
        String key = requireKey(Stop.TAGO_BUS);
        if (key == null) return;
        if (cityCache != null) {
            chooseCity(key);
            return;
        }
        toast("도시 목록을 불러오는 중…");
        io.execute(() -> {
            try {
                List<String[]> cities = Parsers.tagoCities(SecureHttp.get(Endpoints.tagoCities(key)));
                runOnUiThread(() -> {
                    if (cities.isEmpty()) {
                        toast("도시 목록이 비어 있습니다.");
                        return;
                    }
                    cityCache = cities;
                    chooseCity(key);
                });
            } catch (TransitException e) {
                runOnUiThread(() -> toast(e.getMessage()));
            }
        });
    }

    private void chooseCity(String key) {
        String[] names = new String[cityCache.size()];
        for (int i = 0; i < names.length; i++) names[i] = cityCache.get(i)[1];
        new AlertDialog.Builder(this).setTitle("도시 선택").setItems(names, (d, which) -> {
            String city = cityCache.get(which)[0];
            prompt("정류장 이름 검색", "예: 시청", "", false, name -> searchStops(() ->
                    Parsers.tagoStops(SecureHttp.get(Endpoints.tagoStopSearch(key, city, name)), city)));
        }).show();
    }

    private void addSeoulBusStop() {
        String key = requireKey(Stop.SEOUL_BUS);
        if (key == null) return;
        prompt("서울 버스 정류장 이름 검색", "예: 광화문", "", false, name -> searchStops(() ->
                Parsers.seoulBusStops(SecureHttp.get(Endpoints.seoulBusStopSearch(key, name)))));
    }

    private void addSubwayStation() {
        prompt("지하철 역 이름", "예: 강남", "", false, raw -> {
            String name = Transit.normalizeStation(raw);
            if (name.isEmpty() || name.length() > 40) {
                toast("역 이름을 확인하세요.");
                return;
            }
            addStop(new Stop(Stop.SEOUL_SUBWAY, "", name, name + "역", Double.NaN, Double.NaN, ""));
        });
    }

    private interface StopSearch {
        List<Stop> run() throws TransitException;
    }

    private void searchStops(StopSearch search) {
        toast("검색 중…");
        io.execute(() -> {
            try {
                List<Stop> found = search.run();
                runOnUiThread(() -> {
                    if (found.isEmpty()) {
                        toast("검색 결과가 없습니다.");
                        return;
                    }
                    List<String> names = new ArrayList<>();
                    for (Stop s : found) names.add(s.name);
                    new AlertDialog.Builder(this).setTitle("정류장 선택")
                            .setItems(names.toArray(new String[0]), (d, which) -> addStop(found.get(which)))
                            .show();
                });
            } catch (TransitException e) {
                runOnUiThread(() -> toast(e.getMessage()));
            }
        });
    }

    // ---------- 인증키 ----------

    private String requireKey(int type) {
        String key = Vault.get(this, Config.keyName(type));
        if (key == null) toast("먼저 아래에서 해당 인증키를 설정하세요.");
        return key;
    }

    private void bindKeyButton(int viewId, int type, String label) {
        Button b = findViewById(viewId);
        Runnable render = () -> b.setText(label + ": " + (Vault.get(this, Config.keyName(type)) == null ? "미설정" : "설정됨"));
        render.run();
        b.setOnClickListener(v -> prompt(label + " 인증키", "키를 붙여넣으세요 (비우면 삭제)", "", true, text -> {
            if (text.isEmpty()) {
                Vault.put(this, Config.keyName(type), null);
            } else {
                boolean ok = type == Stop.SEOUL_SUBWAY ? Endpoints.isValidSeoulKey(text) : Endpoints.isValidDataGoKey(text);
                if (!ok) {
                    toast("인증키 형식이 올바르지 않습니다.");
                    return;
                }
                Vault.put(this, Config.keyName(type), text);
                cityCache = null;
            }
            render.run();
        }));
    }

    // ---------- 옵션 ----------

    private void renderMinutes() {
        ((Button) findViewById(R.id.btn_minutes)).setText("실시간 갱신 지속 시간: " + cfg.liveMinutes + "분");
    }

    private void pickMinutes() {
        final int[] options = {10, 20, 30, 60};
        String[] labels = new String[options.length];
        for (int i = 0; i < options.length; i++) labels[i] = options[i] + "분";
        new AlertDialog.Builder(this).setTitle("실시간 갱신 지속 시간").setItems(labels, (d, which) -> {
            cfg.liveMinutes = options[which];
            cfg.save(this);
            renderMinutes();
        }).show();
    }

    private void confirmWipe() {
        new AlertDialog.Builder(this)
                .setTitle("모든 데이터 삭제")
                .setMessage("인증키, 등록 정류장, 설정과 암호화 키를 이 기기에서 완전히 지웁니다.")
                .setPositiveButton("삭제", (d, w) -> {
                    stopService(new Intent(this, LiveService.class));
                    Vault.wipe(this);
                    recreate();
                })
                .setNegativeButton("취소", null)
                .show();
    }

    // ---------- 공통 ----------

    private interface TextCallback {
        void accept(String text);
    }

    private void prompt(String title, String hint, String initial, boolean secret, TextCallback cb) {
        EditText input = new EditText(this);
        input.setHint(hint);
        input.setText(initial);
        input.setSingleLine(true);
        input.setInputType(secret
                ? InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD
                : InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        // 키보드 앱의 학습·클라우드 동기화 대상에서 제외 요청
        input.setImeOptions(android.view.inputmethod.EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING);
        int pad = (int) (20 * getResources().getDisplayMetrics().density);
        LinearLayout box = new LinearLayout(this);
        box.setPadding(pad, pad / 2, pad, 0);
        box.addView(input, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle(title)
                .setView(box)
                .setPositiveButton("확인", (d, w) -> cb.accept(input.getText().toString().trim()))
                .setNegativeButton("취소", null)
                .create();
        if (dialog.getWindow() != null) dialog.getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);
        dialog.show();
    }

    private void toast(String msg) {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show();
    }
}
