package kr.local.transitglance.core;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.TimeZone;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** 서버 응답(신뢰할 수 없는 입력)을 방어적으로 해석한다. 형식이 어긋나면 예외 대신 안전한 오류 메시지로 바꾼다. */
public final class Parsers {
    private Parsers() {}

    private static final Pattern XML_REASON = Pattern.compile("<returnReasonCode>\\s*(\\d+)\\s*<");

    // ---------- TAGO (국토교통부) ----------

    static JSONArray tagoItems(String body) throws TransitException {
        JSONObject root;
        try {
            root = new JSONObject(body);
        } catch (JSONException e) {
            Matcher m = XML_REASON.matcher(body);
            if (m.find()) throw new TransitException(dataGoReason(m.group(1)));
            throw new TransitException("서버 응답을 해석할 수 없습니다.");
        }
        JSONObject err = root.optJSONObject("OpenAPI_ServiceResponse");
        if (err != null) {
            JSONObject h = err.optJSONObject("cmmMsgHeader");
            throw new TransitException(dataGoReason(h == null ? "" : h.optString("returnReasonCode", "")));
        }
        JSONObject resp = root.optJSONObject("response");
        if (resp == null) throw new TransitException("서버 응답 형식이 예상과 다릅니다.");
        JSONObject header = resp.optJSONObject("header");
        String code = header == null ? "" : header.optString("resultCode", "");
        if (!code.isEmpty() && !"00".equals(code) && !"0".equals(code)) {
            throw new TransitException(dataGoReason(code));
        }
        JSONArray out = new JSONArray();
        JSONObject b = resp.optJSONObject("body");
        JSONObject items = b == null ? null : b.optJSONObject("items"); // 결과가 없으면 ""(문자열)로 온다
        if (items == null) return out;
        Object item = items.opt("item");
        if (item instanceof JSONArray) return (JSONArray) item;
        if (item instanceof JSONObject) out.put(item); // 결과가 1건이면 배열이 아닌 객체로 온다
        return out;
    }

    private static String dataGoReason(String code) {
        switch (code) {
            case "20": return "이 API의 활용신청이 승인되지 않았습니다. (코드 20)";
            case "22": return "하루 호출 한도를 초과했습니다. (코드 22)";
            case "30": return "등록되지 않은 인증키입니다. (코드 30)";
            case "31": return "인증키 사용 기간이 만료되었습니다. (코드 31)";
            case "32": return "등록되지 않은 IP입니다. (코드 32)";
            default: return "공공데이터 서버 오류" + (code.matches("\\d{1,3}") ? " (코드 " + code + ")" : "");
        }
    }

    public static List<Arrival> tagoArrivals(String body) throws TransitException {
        JSONArray items = tagoItems(body);
        List<Arrival> out = new ArrayList<>();
        for (int i = 0; i < items.length(); i++) {
            JSONObject o = items.optJSONObject(i);
            if (o == null) continue;
            String route = clean(o.optString("routeno", ""));
            if (route.isEmpty()) continue;
            int sec = o.optInt("arrtime", -1);
            int prev = o.optInt("arrprevstationcnt", -1);
            out.add(new Arrival(route, prev >= 0 ? prev + "정거장 전" : "", sec, ""));
        }
        return out;
    }

    public static List<Stop> tagoStops(String body, String cityCode) throws TransitException {
        JSONArray items = tagoItems(body);
        List<Stop> out = new ArrayList<>();
        for (int i = 0; i < items.length(); i++) {
            JSONObject o = items.optJSONObject(i);
            if (o == null) continue;
            String id = o.optString("nodeid", "");
            String name = clean(o.optString("nodenm", ""));
            if (id.isEmpty() || name.isEmpty()) continue;
            String no = clean(o.optString("nodeno", ""));
            out.add(new Stop(Stop.TAGO_BUS, cityCode, id, no.isEmpty() ? name : name + " (" + no + ")",
                    coord(o.optDouble("gpslati", Double.NaN), 90), coord(o.optDouble("gpslong", Double.NaN), 180), ""));
        }
        return out;
    }

    /** 결과: {코드, 이름} 쌍 목록. */
    public static List<String[]> tagoCities(String body) throws TransitException {
        JSONArray items = tagoItems(body);
        List<String[]> out = new ArrayList<>();
        for (int i = 0; i < items.length(); i++) {
            JSONObject o = items.optJSONObject(i);
            if (o == null) continue;
            String code = o.optString("citycode", "");
            String name = clean(o.optString("cityname", ""));
            if (code.matches("[0-9]{1,10}") && !name.isEmpty()) out.add(new String[]{code, name});
        }
        return out;
    }

    // ---------- 서울 지하철 ----------

    public static List<Arrival> seoulSubwayArrivals(String body, long nowMillis) throws TransitException {
        JSONObject root;
        try {
            root = new JSONObject(body);
        } catch (JSONException e) {
            throw new TransitException("서버 응답을 해석할 수 없습니다.");
        }
        JSONArray list = root.optJSONArray("realtimeArrivalList");
        if (list == null) {
            JSONObject em = root.optJSONObject("errorMessage");
            String code = clean((em != null ? em : root).optString("code", ""));
            if ("INFO-200".equals(code)) return new ArrayList<>(); // 해당 데이터 없음
            if ("INFO-100".equals(code)) throw new TransitException("서울 지하철 인증키가 유효하지 않습니다. (INFO-100)");
            throw new TransitException("서울 지하철 서버 오류" + (code.matches("[A-Z]{3,6}-[0-9]{1,4}") ? " (" + code + ")" : ""));
        }
        List<Arrival> out = new ArrayList<>();
        for (int i = 0; i < list.length(); i++) {
            JSONObject o = list.optJSONObject(i);
            if (o == null) continue;
            String line = lineName(o.optString("subwayId", ""));
            String dest = clean(o.optString("trainLineNm", ""));
            String msg = clean(o.optString("arvlMsg2", ""));
            int sec = parseInt(o.optString("barvlDt", ""), -1);
            if (sec <= 0) {
                sec = -1; // 초 단위 값이 없으면 원문 메시지를 보여준다
            } else {
                long received = parseKst(o.optString("recptnDt", ""));
                if (received > 0 && nowMillis > received) sec = (int) Math.max(0, sec - (nowMillis - received) / 1000);
            }
            out.add(new Arrival(line, dest, sec, msg));
        }
        return out;
    }

    static String lineName(String id) {
        switch (id) {
            case "1001": return "1호선";
            case "1002": return "2호선";
            case "1003": return "3호선";
            case "1004": return "4호선";
            case "1005": return "5호선";
            case "1006": return "6호선";
            case "1007": return "7호선";
            case "1008": return "8호선";
            case "1009": return "9호선";
            case "1032": return "GTX-A";
            case "1063": return "경의중앙";
            case "1065": return "공항철도";
            case "1067": return "경춘선";
            case "1075": return "수인분당";
            case "1077": return "신분당";
            case "1081": return "경강선";
            case "1092": return "우이신설";
            case "1093": return "서해선";
            default: return clean(id);
        }
    }

    private static long parseKst(String s) {
        try {
            SimpleDateFormat f = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US);
            f.setTimeZone(TimeZone.getTimeZone("Asia/Seoul"));
            f.setLenient(false);
            Date d = f.parse(s);
            return d == null ? -1 : d.getTime();
        } catch (ParseException e) {
            return -1;
        }
    }

    // ---------- 서울 버스 ----------

    static JSONArray seoulBusItems(String body) throws TransitException {
        JSONObject root;
        try {
            root = new JSONObject(body);
        } catch (JSONException e) {
            Matcher m = XML_REASON.matcher(body);
            if (m.find()) throw new TransitException(dataGoReason(m.group(1)));
            throw new TransitException("서버 응답을 해석할 수 없습니다.");
        }
        JSONObject h = root.optJSONObject("msgHeader");
        String code = h == null ? "" : h.optString("headerCd", "");
        if ("4".equals(code)) return new JSONArray(); // 결과 없음
        if (!"0".equals(code)) {
            if ("7".equals(code)) throw new TransitException("서울 버스 인증키가 유효하지 않습니다. (코드 7)");
            throw new TransitException("서울 버스 서버 오류" + (code.matches("\\d{1,3}") ? " (코드 " + code + ")" : ""));
        }
        JSONObject b = root.optJSONObject("msgBody");
        JSONArray list = b == null ? null : b.optJSONArray("itemList");
        return list == null ? new JSONArray() : list;
    }

    public static List<Stop> seoulBusStops(String body) throws TransitException {
        JSONArray items = seoulBusItems(body);
        List<Stop> out = new ArrayList<>();
        for (int i = 0; i < items.length(); i++) {
            JSONObject o = items.optJSONObject(i);
            if (o == null) continue;
            String ars = o.optString("arsId", "");
            String name = clean(o.optString("stNm", ""));
            if (!ars.matches("[0-9]{1,10}") || "0".equals(ars) || name.isEmpty()) continue;
            out.add(new Stop(Stop.SEOUL_BUS, "", ars, name + " (" + ars + ")",
                    coord(o.optDouble("tmY", Double.NaN), 90), coord(o.optDouble("tmX", Double.NaN), 180), ""));
        }
        return out;
    }

    public static List<Arrival> seoulBusArrivals(String body) throws TransitException {
        JSONArray items = seoulBusItems(body);
        List<Arrival> out = new ArrayList<>();
        for (int i = 0; i < items.length(); i++) {
            JSONObject o = items.optJSONObject(i);
            if (o == null) continue;
            String route = clean(o.optString("rtNm", ""));
            if (route.isEmpty()) continue;
            String dir = clean(o.optString("adirection", ""));
            String detail = dir.isEmpty() ? "" : dir + " 방면";
            out.add(new Arrival(route, detail, parseInt(o.optString("traTime1", ""), -1), clean(o.optString("arrmsg1", ""))));
        }
        return out;
    }

    // ---------- 공통 ----------

    /** 화면에 그릴 문자열 정리: 제어문자 제거, 길이 제한. */
    static String clean(String s) {
        if (s == null) return "";
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < s.length() && sb.length() < 60; i++) {
            char ch = s.charAt(i);
            if (!Character.isISOControl(ch)) sb.append(ch);
        }
        return sb.toString().trim();
    }

    private static double coord(double v, double limit) {
        return (Double.isNaN(v) || Double.isInfinite(v) || Math.abs(v) > limit || v == 0.0) ? Double.NaN : v;
    }

    private static int parseInt(String s, int fallback) {
        try {
            return Integer.parseInt(s.trim());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }
}
