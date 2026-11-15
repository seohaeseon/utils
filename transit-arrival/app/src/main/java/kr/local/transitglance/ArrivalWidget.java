package kr.local.transitglance;

import android.app.PendingIntent;
import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProvider;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.widget.RemoteViews;

/** 홈 화면(및 잠금화면 위젯을 지원하는 기기의 잠금화면) 위젯. */
public final class ArrivalWidget extends AppWidgetProvider {

    @Override
    public void onUpdate(Context context, AppWidgetManager manager, int[] ids) {
        final Context app = context.getApplicationContext();
        push(app, Snapshot.load(app));
        if (LiveService.running) return; // 실시간 갱신 중이면 서비스가 그린다
        final PendingResult pending = goAsync();
        new Thread(() -> {
            try {
                Engine.refresh(app, false);
            } finally {
                pending.finish();
            }
        }, "widget-refresh").start();
    }

    static void push(Context app, Snapshot s) {
        AppWidgetManager manager = AppWidgetManager.getInstance(app);
        if (manager == null) return;
        int[] ids = manager.getAppWidgetIds(new ComponentName(app, ArrivalWidget.class));
        if (ids == null || ids.length == 0) return;

        RemoteViews v = new RemoteViews(app.getPackageName(), R.layout.widget);
        v.setTextViewText(R.id.w_title, s.title);
        v.setTextViewText(R.id.w_body, s.body);
        v.setTextViewText(R.id.w_footer, (LiveService.running ? "● 실시간 " : "탭하여 갱신 ") + s.footer);

        // 모든 PendingIntent는 대상 컴포넌트를 명시하고 변경 불가(IMMUTABLE)로 만든다.
        Intent live = new Intent(app, LiveService.class);
        v.setOnClickPendingIntent(R.id.w_root, PendingIntent.getForegroundService(app, 1, live,
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT));
        Intent open = new Intent(app, MainActivity.class).setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        v.setOnClickPendingIntent(R.id.w_settings, PendingIntent.getActivity(app, 2, open,
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT));
        manager.updateAppWidget(ids, v);
    }
}
