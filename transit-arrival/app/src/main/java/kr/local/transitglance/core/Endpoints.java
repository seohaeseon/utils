package kr.local.transitglance.core;

import java.io.UnsupportedEncodingException;
import java.net.MalformedURLException;
import java.net.URL;
import java.net.URLEncoder;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 이 앱이 만들 수 있는 모든 네트워크 요청 URL은 이 클래스에서만 만들어진다.
 *
 * 보안 불변식:
 *  - 스킴은 https, 호스트는 허용 목록 3개뿐.
 *  - 어떤 메서드도 위도/경도를 인자로 받지 않는다. 즉 위치를 요청에 실을 방법 자체가 없다.
 *    (TAGO의 "좌표 기반 근접 정류소" API는 위치가 서버로 나가므로 의도적으로 구현하지 않았다.)
 *  - 모든 입력은 형식 검증 후 URL 인코딩한다.
 */
public final class Endpoints {
    private Endpoints() {}

    public static final String HOST_TAGO = "apis.data.go.kr";
    public static final String HOST_SEOUL_SUBWAY = "swopenapi.seoul.go.kr";
    public static final String HOST_SEOUL_BUS = "ws.bus.go.kr";

    public static final Set<String> ALLOWED_HOSTS = Collections.unmodifiableSet(
            new HashSet<>(Arrays.asList(HOST_TAGO, HOST_SEOUL_SUBWAY, HOST_SEOUL_BUS)));

    private static final Pattern DATA_GO_KEY = Pattern.compile("[A-Za-z0-9+/=%_.\\-]{8,300}");
    private static final Pattern SEOUL_KEY = Pattern.compile("[A-Za-z0-9]{8,100}");
    private static final Pattern DIGITS = Pattern.compile("[0-9]{1,10}");
    private static final Pattern NODE_ID = Pattern.compile("[A-Za-z0-9_\\-]{1,40}");

    /** 허용 목록 검사. 통과하지 못하면 요청을 보내지 않는다. */
    public static void requireAllowed(URL url) throws TransitException {
        boolean ok = "https".equals(url.getProtocol())
                && ALLOWED_HOSTS.contains(url.getHost())
                && (url.getPort() == -1 || url.getPort() == 443)
                && url.getUserInfo() == null;
        if (!ok) throw new TransitException("허용되지 않은 주소로의 요청을 차단했습니다.");
    }

    public static boolean isValidDataGoKey(String key) {
        return key != null && DATA_GO_KEY.matcher(key).matches();
    }

    public static boolean isValidSeoulKey(String key) {
        return key != null && SEOUL_KEY.matcher(key).matches();
    }

    public static URL tagoCities(String key) throws TransitException {
        return build("https://" + HOST_TAGO + "/1613000/BusRouteInfoInqireService/getCtyCodeList"
                + "?serviceKey=" + dataGoKey(key) + "&_type=json");
    }

    public static URL tagoStopSearch(String key, String cityCode, String name) throws TransitException {
        return build("https://" + HOST_TAGO + "/1613000/BusSttnInfoInqireService/getSttnNoList"
                + "?serviceKey=" + dataGoKey(key) + "&_type=json&pageNo=1&numOfRows=50"
                + "&cityCode=" + digits(cityCode) + "&nodeNm=" + enc(text(name)));
    }

    public static URL tagoArrivals(String key, String cityCode, String nodeId) throws TransitException {
        if (nodeId == null || !NODE_ID.matcher(nodeId).matches()) throw new TransitException("정류장 ID 형식이 올바르지 않습니다.");
        return build("https://" + HOST_TAGO + "/1613000/ArvlInfoInqireService/getSttnAcctoArvlPrearngeInfoList"
                + "?serviceKey=" + dataGoKey(key) + "&_type=json&pageNo=1&numOfRows=50"
                + "&cityCode=" + digits(cityCode) + "&nodeId=" + nodeId);
    }

    public static URL seoulSubwayArrivals(String key, String station) throws TransitException {
        if (!isValidSeoulKey(key)) throw new TransitException("서울 지하철 인증키 형식이 올바르지 않습니다.");
        return build("https://" + HOST_SEOUL_SUBWAY + "/api/subway/" + key + "/json/realtimeStationArrival/0/30/"
                + enc(text(station)).replace("+", "%20"));
    }

    public static URL seoulBusStopSearch(String key, String name) throws TransitException {
        return build("https://" + HOST_SEOUL_BUS + "/api/rest/stationinfo/getStationByName"
                + "?serviceKey=" + dataGoKey(key) + "&resultType=json&stSrch=" + enc(text(name)));
    }

    public static URL seoulBusArrivals(String key, String arsId) throws TransitException {
        return build("https://" + HOST_SEOUL_BUS + "/api/rest/stationinfo/getStationByUid"
                + "?serviceKey=" + dataGoKey(key) + "&resultType=json&arsId=" + digits(arsId));
    }

    /** 공공데이터포털 키: Encoding 키(%, 이미 인코딩됨)는 그대로, Decoding 키는 인코딩해서 쓴다. */
    private static String dataGoKey(String key) throws TransitException {
        if (!isValidDataGoKey(key)) throw new TransitException("인증키 형식이 올바르지 않습니다.");
        return key.indexOf('%') >= 0 ? key : enc(key);
    }

    private static String digits(String s) throws TransitException {
        if (s == null || !DIGITS.matcher(s).matches()) throw new TransitException("코드 형식이 올바르지 않습니다.");
        return s;
    }

    private static String text(String s) throws TransitException {
        String t = s == null ? "" : s.trim();
        if (t.isEmpty() || t.length() > 40) throw new TransitException("이름은 1~40자로 입력하세요.");
        for (int i = 0; i < t.length(); i++) {
            if (Character.isISOControl(t.charAt(i))) throw new TransitException("이름에 사용할 수 없는 문자가 있습니다.");
        }
        return t;
    }

    private static String enc(String s) {
        try {
            return URLEncoder.encode(s, "UTF-8");
        } catch (UnsupportedEncodingException e) {
            throw new IllegalStateException(e);
        }
    }

    private static URL build(String spec) throws TransitException {
        URL url;
        try {
            url = new URL(spec);
        } catch (MalformedURLException e) {
            throw new TransitException("요청 주소를 만들 수 없습니다.");
        }
        requireAllowed(url);
        return url;
    }
}
