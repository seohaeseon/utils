package kr.local.transitglance.core;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.junit.Test;

import java.util.Arrays;
import java.util.List;

public class ParsersTest {
    private static String tago(String items) {
        return "{\"response\":{\"header\":{\"resultCode\":\"00\",\"resultMsg\":\"NORMAL SERVICE.\"},"
                + "\"body\":{\"items\":" + items + ",\"numOfRows\":50,\"pageNo\":1,\"totalCount\":1}}}";
    }

    @Test
    public void tagoArrivalsArray() throws Exception {
        List<Arrival> a = Parsers.tagoArrivals(tago("{\"item\":["
                + "{\"arrprevstationcnt\":3,\"arrtime\":245,\"nodeid\":\"DJB1\",\"routeno\":102,\"routetp\":\"간선버스\"},"
                + "{\"arrprevstationcnt\":1,\"arrtime\":40,\"nodeid\":\"DJB1\",\"routeno\":\"7-1\"}]}"));
        assertEquals(2, a.size());
        assertEquals("102", a.get(0).route);
        assertEquals("4분", a.get(0).eta());
        assertEquals("3정거장 전", a.get(0).detail);
        assertEquals("곧 도착", a.get(1).eta());
    }

    @Test
    public void tagoSingleItemIsObjectAndEmptyIsString() throws Exception {
        assertEquals(1, Parsers.tagoArrivals(tago("{\"item\":{\"arrtime\":60,\"routeno\":5}}")).size());
        assertEquals(0, Parsers.tagoArrivals(tago("\"\"")).size());
    }

    @Test
    public void tagoStopsAndCities() throws Exception {
        List<Stop> s = Parsers.tagoStops(tago("{\"item\":[{\"gpslati\":36.35,\"gpslong\":127.38,\"nodeid\":\"DJB8001793\","
                + "\"nodenm\":\"시청\",\"nodeno\":44810},{\"nodeid\":\"\",\"nodenm\":\"x\"}]}"), "25");
        assertEquals(1, s.size());
        assertEquals("시청 (44810)", s.get(0).name);
        assertTrue(s.get(0).hasLocation());
        assertEquals("25", s.get(0).cityCode);
        List<String[]> c = Parsers.tagoCities(tago("{\"item\":[{\"citycode\":25,\"cityname\":\"대전광역시\"}]}"));
        assertEquals("25", c.get(0)[0]);
    }

    @Test
    public void tagoErrorsAreSafeMessages() {
        String[] bodies = {
                "{\"OpenAPI_ServiceResponse\":{\"cmmMsgHeader\":{\"errMsg\":\"SERVICE_KEY_IS_NOT_REGISTERED_ERROR\",\"returnReasonCode\":\"30\"}}}",
                "<OpenAPI_ServiceResponse><cmmMsgHeader><returnReasonCode>30</returnReasonCode></cmmMsgHeader></OpenAPI_ServiceResponse>",
        };
        for (String b : bodies) {
            try {
                Parsers.tagoArrivals(b);
                fail();
            } catch (TransitException e) {
                assertTrue(e.getMessage().contains("등록되지 않은 인증키"));
            }
        }
        for (String garbage : new String[]{"", "<html>", "[]", "{}", "{\"response\":5}", "null"}) {
            try {
                Parsers.tagoArrivals(garbage);
                fail(garbage);
            } catch (TransitException expected) {
                // ok
            }
        }
    }

    @Test
    public void subwayArrivals() throws Exception {
        String body = "{\"errorMessage\":{\"status\":200,\"code\":\"INFO-000\"},\"realtimeArrivalList\":["
                + "{\"subwayId\":\"1002\",\"trainLineNm\":\"성수행 - 역삼방면\",\"barvlDt\":\"180\",\"recptnDt\":\"2026-01-01 12:00:00\",\"arvlMsg2\":\"3분 후\"},"
                + "{\"subwayId\":\"1077\",\"trainLineNm\":\"광교행 - 양재방면\",\"barvlDt\":\"0\",\"arvlMsg2\":\"전역 출발\"}]}";
        long noon = 1767236400000L; // 2026-01-01 12:00:00 KST
        List<Arrival> a = Parsers.seoulSubwayArrivals(body, noon + 60_000);
        assertEquals("2호선", a.get(0).route);
        assertEquals(120, a.get(0).seconds); // 수신 후 60초 경과분 보정
        assertEquals("전역 출발", a.get(1).eta());
        assertEquals("신분당", a.get(1).route);
    }

    @Test
    public void subwayErrors() throws Exception {
        assertEquals(0, Parsers.seoulSubwayArrivals("{\"status\":500,\"code\":\"INFO-200\",\"message\":\"없음\"}", 0).size());
        try {
            Parsers.seoulSubwayArrivals("{\"status\":500,\"code\":\"INFO-100\",\"message\":\"인증키\"}", 0);
            fail();
        } catch (TransitException e) {
            assertTrue(e.getMessage().contains("인증키"));
        }
    }

    @Test
    public void seoulBus() throws Exception {
        String stops = "{\"msgHeader\":{\"headerCd\":\"0\"},\"msgBody\":{\"itemList\":["
                + "{\"arsId\":\"01126\",\"stNm\":\"광화문\",\"tmX\":\"126.97\",\"tmY\":\"37.57\"},"
                + "{\"arsId\":\"0\",\"stNm\":\"가상\"}]}}";
        List<Stop> s = Parsers.seoulBusStops(stops);
        assertEquals(1, s.size());
        assertEquals(37.57, s.get(0).lat, 1e-9);
        String arr = "{\"msgHeader\":{\"headerCd\":\"0\"},\"msgBody\":{\"itemList\":["
                + "{\"rtNm\":\"7016\",\"adirection\":\"상명대\",\"traTime1\":\"312\",\"arrmsg1\":\"5분12초후[3번째 전]\"},"
                + "{\"rtNm\":\"N37\",\"arrmsg1\":\"운행종료\"}]}}";
        List<Arrival> a = Parsers.seoulBusArrivals(arr);
        assertEquals("5분", a.get(0).eta());
        assertEquals("운행종료", a.get(1).eta());
        assertEquals(0, Parsers.seoulBusArrivals("{\"msgHeader\":{\"headerCd\":\"4\"},\"msgBody\":{\"itemList\":null}}").size());
    }

    @Test
    public void hostileStringsAreSanitized() throws Exception {
        String longName = new String(new char[500]).replace('\0', 'A');
        List<Arrival> a = Parsers.tagoArrivals(tago("{\"item\":{\"arrtime\":60,\"routeno\":\"7\\u0000\\n" + longName + "\"}}"));
        assertTrue(a.get(0).route.length() <= 60);
        assertFalse(a.get(0).route.contains("\n"));
    }

    @Test
    public void filterAndSort() {
        List<Arrival> all = Arrays.asList(new Arrival("707", "", 100, ""), new Arrival("7", "", 300, ""),
                new Arrival("7-1", "", -1, "운행종료"), new Arrival("7", "", 50, ""));
        Stop bus = new Stop(Stop.TAGO_BUS, "25", "X", "n", Double.NaN, Double.NaN, " 7 , 7-1 ");
        List<Arrival> f = Transit.sortAndFilter(all, bus);
        assertEquals(3, f.size());
        assertEquals(50, f.get(0).seconds);
        assertEquals(-1, f.get(2).seconds); // 시간 정보 없는 항목은 맨 뒤
        Stop sub = new Stop(Stop.SEOUL_SUBWAY, "", "강남", "강남역", Double.NaN, Double.NaN, "성수행");
        assertEquals(1, Transit.sortAndFilter(Arrays.asList(new Arrival("2호선", "성수행 - 역삼방면", 10, ""),
                new Arrival("2호선", "신도림행", 20, "")), sub).size());
        assertEquals("강남", Transit.normalizeStation(" 강남역 "));
        assertEquals("서울", Transit.normalizeStation("서울역"));
    }
}
