package kr.local.transitglance.core;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.junit.Test;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.net.URL;
import java.util.Locale;

public class EndpointsTest {
    private static final String KEY = "abcDEF123+/==";
    private static final String SEOUL_KEY = "abcdef0123456789";

    private static URL[] all() throws Exception {
        return new URL[]{
                Endpoints.tagoCities(KEY),
                Endpoints.tagoStopSearch(KEY, "25", "시청"),
                Endpoints.tagoArrivals(KEY, "25", "DJB8001793"),
                Endpoints.seoulSubwayArrivals(SEOUL_KEY, "강남"),
                Endpoints.seoulBusStopSearch(KEY, "광화문"),
                Endpoints.seoulBusArrivals(KEY, "01126"),
        };
    }

    @Test
    public void everyUrlIsHttpsAndAllowlisted() throws Exception {
        for (URL u : all()) {
            assertEquals("https", u.getProtocol());
            assertTrue(Endpoints.ALLOWED_HOSTS.contains(u.getHost()));
            assertEquals(-1, u.getPort());
        }
    }

    /** 위치 유출 방지 불변식: 어떤 요청 URL에도 좌표 파라미터가 없다. */
    @Test
    public void noUrlCarriesCoordinates() throws Exception {
        for (URL u : all()) {
            String s = u.toString().toLowerCase(Locale.ROOT);
            for (String banned : new String[]{"gps", "lati", "long", "lat=", "lon=", "tmx", "tmy", "coord"}) {
                assertFalse(u + " contains " + banned, s.contains(banned));
            }
        }
    }

    /** 위치 유출 방지 불변식: URL을 만드는 공개 메서드는 문자열 인자만 받는다(좌표 타입을 받을 수 없다). */
    @Test
    public void builderMethodsAcceptOnlyStrings() {
        for (Method m : Endpoints.class.getDeclaredMethods()) {
            if (!Modifier.isPublic(m.getModifiers()) || m.getReturnType() != URL.class) continue;
            for (Class<?> p : m.getParameterTypes()) assertEquals(m.getName(), String.class, p);
        }
    }

    @Test
    public void rejectsNonAllowlistedOrInsecureUrls() throws Exception {
        String[] bad = {
                "http://apis.data.go.kr/1613000/x",
                "https://evil.example/1613000/x",
                "https://apis.data.go.kr.evil.example/x",
                "https://apis.data.go.kr:8443/x",
                "https://user@apis.data.go.kr/x",
                "https://apis.data.go.kr@evil.example/x",
        };
        for (String b : bad) {
            try {
                Endpoints.requireAllowed(new URL(b));
                fail("should reject " + b);
            } catch (TransitException expected) {
                // ok
            }
        }
    }

    @Test
    public void rejectsInjectionInInputs() {
        String[][] cases = {
                {"25&nodeId=x", "A1"}, {"25", "A1&serviceKey=zz"}, {"25", "../x"}, {"", "A1"}, {"25", "a b"},
        };
        for (String[] c : cases) {
            try {
                Endpoints.tagoArrivals(KEY, c[0], c[1]);
                fail("should reject");
            } catch (TransitException expected) {
                // ok
            }
        }
        for (String badKey : new String[]{"../../etc", "a/b/c/d/e/f", "short", "abcdefgh?x=1", "abcd efgh ijkl"}) {
            assertFalse(Endpoints.isValidSeoulKey(badKey));
        }
        for (String badKey : new String[]{"abc def ghi", "abcdefgh&x=1", "abcdefgh#frag", "abcdefgh?x", "tiny"}) {
            assertFalse(Endpoints.isValidDataGoKey(badKey));
        }
    }

    @Test
    public void encodesNamesAndKeys() throws Exception {
        URL u = Endpoints.tagoStopSearch(KEY, "25", "시청&x=1 #a");
        assertFalse(u.getQuery().contains("x=1"));
        assertFalse(u.toString().contains(" "));
        assertTrue(u.getQuery().contains("serviceKey=abcDEF123%2B%2F%3D%3D"));
        // 이미 인코딩된(Encoding) 키는 이중 인코딩하지 않는다.
        assertTrue(Endpoints.tagoCities("abc%2Bdef%3D%3D").getQuery().contains("serviceKey=abc%2Bdef%3D%3D"));
        assertTrue(Endpoints.seoulSubwayArrivals(SEOUL_KEY, "서울 숲").getPath().endsWith("%EC%84%9C%EC%9A%B8%20%EC%88%B2"));
    }
}
