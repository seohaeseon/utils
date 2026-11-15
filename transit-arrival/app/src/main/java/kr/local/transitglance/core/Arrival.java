package kr.local.transitglance.core;

/** 도착 예정 한 건. */
public final class Arrival {
    public final String route;   // 노선 번호 또는 호선
    public final String detail;  // 방면/남은 정거장 등
    public final int seconds;    // 남은 초, 모르면 -1
    public final String message; // API 원문 메시지 (없으면 "")

    public Arrival(String route, String detail, int seconds, String message) {
        this.route = route == null ? "" : route;
        this.detail = detail == null ? "" : detail;
        this.seconds = seconds;
        this.message = message == null ? "" : message;
    }

    public String eta() {
        if (seconds < 0) return message.isEmpty() ? "정보 없음" : message;
        if (seconds < 60) return "곧 도착";
        return (seconds / 60) + "분";
    }

    public String line() {
        StringBuilder sb = new StringBuilder(route).append("  ").append(eta());
        if (!detail.isEmpty()) sb.append("  · ").append(detail);
        return sb.toString();
    }
}
