package kr.local.transitglance;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;

/**
 * 실시간 갱신. 사용자가 시작했을 때만, 정해진 시간 동안만 30초 간격으로 갱신하고 스스로 끝난다.
 * 결과는 잠금화면에 보이는 진행 중 알림과 홈 화면 위젯에 표시된다.
 */
public final class LiveService extends Service {
    static final String ACTION_STOP = "kr.local.transitglance.action.STOP";
    private static final String CHANNEL = "live";
    private static final int NOTIFICATION_ID = 1;
    private static final long INTERVAL_MS = 30_000L;

    static volatile boolean running;

    private HandlerThread thread;
    private Handler worker;
    private long endAt;
    private boolean showOnLock = true;

    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            if (!running) return;
            if (System.currentTimeMillis() >= endAt) {
                stopSelf();
                return;
            }
            Snapshot s = Engine.refresh(LiveService.this, true);
            if (!running) return;
            NotificationManager nm = getSystemService(NotificationManager.class);
            if (nm != null) nm.notify(NOTIFICATION_ID, build(s));
            worker.postDelayed(this, INTERVAL_MS);
        }
    };

    @Override
    public void onCreate() {
        super.onCreate();
        thread = new HandlerThread("live");
        thread.start();
        worker = new Handler(thread.getLooper());
        NotificationManager nm = getSystemService(NotificationManager.class);
        if (nm != null) {
            NotificationChannel ch = new NotificationChannel(CHANNEL, "실시간 도착 정보", NotificationManager.IMPORTANCE_LOW);
            ch.setShowBadge(false);
            nm.createNotificationChannel(ch);
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && ACTION_STOP.equals(intent.getAction())) {
            // startForegroundService로 시작된 경우를 대비해 먼저 포그라운드 진입 계약을 지킨 뒤 종료한다.
            enterForeground(Snapshot.load(this), false);
            stopSelf();
            return START_NOT_STICKY;
        }
        Config cfg = Config.load(this);
        showOnLock = cfg.showOnLock;
        endAt = System.currentTimeMillis() + cfg.liveMinutes * 60_000L;
        boolean wantLocation = cfg.mode == Config.MODE_NEAREST && Locator.granted(this);
        if (!enterForeground(Snapshot.load(this), wantLocation)) {
            stopSelf();
            return START_NOT_STICKY;
        }
        running = true;
        worker.removeCallbacks(tick);
        worker.post(tick);
        return START_NOT_STICKY;
    }

    private boolean enterForeground(Snapshot s, boolean withLocation) {
        Notification n = build(s);
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                int type = ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC;
                if (withLocation) type |= ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION;
                try {
                    startForeground(NOTIFICATION_ID, n, type);
                } catch (SecurityException e) {
                    // 위치 유형으로 시작할 자격이 없는 상황(백그라운드 시작 등)이면 위치 없이 진행한다.
                    startForeground(NOTIFICATION_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC);
                }
            } else {
                startForeground(NOTIFICATION_ID, n);
            }
            return true;
        } catch (RuntimeException e) {
            return false; // 시스템이 포그라운드 시작을 허용하지 않음
        }
    }

    private Notification build(Snapshot s) {
        PendingIntent open = PendingIntent.getActivity(this, 3,
                new Intent(this, MainActivity.class).setFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        PendingIntent stop = PendingIntent.getService(this, 4,
                new Intent(this, LiveService.class).setAction(ACTION_STOP),
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        String firstLine = s.body.split("\n", 2)[0];
        Notification.Builder b = new Notification.Builder(this, CHANNEL)
                .setSmallIcon(R.drawable.ic_bus)
                .setContentTitle(s.title)
                .setContentText(firstLine)
                .setSubText(s.footer)
                .setStyle(new Notification.BigTextStyle().bigText(s.body))
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setShowWhen(false)
                .setCategory(Notification.CATEGORY_STATUS)
                // 잠금화면 표시: 켜면 내용 전체, 끄면 잠금 해제 전에는 내용을 가린다.
                .setVisibility(showOnLock ? Notification.VISIBILITY_PUBLIC : Notification.VISIBILITY_PRIVATE)
                .setContentIntent(open)
                .addAction(new Notification.Action.Builder(null, "중지", stop).build());
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            b.setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE);
        }
        return b.build();
    }

    @Override
    public void onTimeout(int startId, int fgsType) {
        stopSelf(); // Android 15+의 dataSync 시간 제한에 도달
    }

    @Override
    public void onDestroy() {
        running = false;
        worker.removeCallbacksAndMessages(null);
        thread.quitSafely();
        stopForeground(STOP_FOREGROUND_REMOVE);
        ArrivalWidget.push(getApplicationContext(), Snapshot.load(this));
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
