package kr.local.transitglance;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.os.Build;
import android.os.CancellationSignal;
import android.os.Looper;
import android.os.SystemClock;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 현재 위치를 기기 내부에서만 얻는다.
 *  - Google Play 서비스(Fused Location)를 쓰지 않고 OS의 LocationManager만 사용한다.
 *  - "GPS만 사용"이 켜져 있으면 위성 신호로만 계산하므로 위치 계산 과정에서도 네트워크 조회가 없다.
 *  - 이 클래스는 네트워크 코드를 참조하지 않으며, 반환한 위치는 호출자가 거리 계산 직후 버린다.
 */
final class Locator {
    private Locator() {}

    static boolean granted(Context c) {
        return c.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
                || c.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED;
    }

    /** 워커 스레드에서 호출. quick이면 최근 위치만 보고 새 측위를 기다리지 않는다. 실패 시 null. */
    static Location current(Context c, boolean gpsOnly, boolean quick) {
        if (!granted(c)) return null;
        LocationManager lm = c.getSystemService(LocationManager.class);
        if (lm == null) return null;
        try {
            Location last = null;
            String[] cached = gpsOnly
                    ? new String[]{LocationManager.GPS_PROVIDER, LocationManager.PASSIVE_PROVIDER}
                    : new String[]{LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER, LocationManager.PASSIVE_PROVIDER};
            for (String p : cached) {
                Location l = lastKnown(lm, p);
                if (l != null && (last == null || ageMs(l) < ageMs(last))) last = l;
            }
            if (last != null && ageMs(last) <= 2 * 60_000L) return last;
            Location stale = last != null && ageMs(last) <= 15 * 60_000L ? last : null;
            if (quick) return stale;

            String provider = null;
            if (lm.isProviderEnabled(LocationManager.GPS_PROVIDER)) provider = LocationManager.GPS_PROVIDER;
            else if (!gpsOnly && lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) provider = LocationManager.NETWORK_PROVIDER;
            if (provider == null) return stale;
            Location fresh = requestOnce(c, lm, provider);
            return fresh != null ? fresh : stale;
        } catch (SecurityException | IllegalArgumentException e) {
            return null;
        }
    }

    private static Location lastKnown(LocationManager lm, String provider) {
        try {
            return lm.getLastKnownLocation(provider);
        } catch (SecurityException | IllegalArgumentException e) {
            return null;
        }
    }

    private static long ageMs(Location l) {
        return (SystemClock.elapsedRealtimeNanos() - l.getElapsedRealtimeNanos()) / 1_000_000L;
    }

    private static Location requestOnce(Context c, LocationManager lm, String provider) {
        final AtomicReference<Location> out = new AtomicReference<>();
        final CountDownLatch latch = new CountDownLatch(1);
        if (!granted(c)) return null;
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                CancellationSignal cancel = new CancellationSignal();
                lm.getCurrentLocation(provider, cancel, Runnable::run, l -> {
                    out.set(l);
                    latch.countDown();
                });
                if (!latch.await(10, TimeUnit.SECONDS)) cancel.cancel();
            } else {
                LocationListener listener = l -> {
                    out.set(l);
                    latch.countDown();
                };
                lm.requestSingleUpdate(provider, listener, Looper.getMainLooper());
                if (!latch.await(10, TimeUnit.SECONDS)) lm.removeUpdates(listener);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (SecurityException e) {
            return null; // 측위 도중 권한이 회수됨
        }
        return out.get();
    }
}
