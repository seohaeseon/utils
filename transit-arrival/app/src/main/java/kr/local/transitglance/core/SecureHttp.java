package kr.local.transitglance.core;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.net.URLConnection;
import java.nio.charset.StandardCharsets;

import javax.net.ssl.HttpsURLConnection;

/**
 * 유일한 네트워크 출구. HTTPS GET만 수행한다.
 *  - 허용 목록 재검사, 리다이렉트 추적 금지(다른 호스트로 키가 넘어가는 것 방지)
 *  - 응답 크기·시간 제한
 *  - 기기 모델/OS 빌드가 담기는 기본 User-Agent 대신 고정 문자열 사용
 *  - 예외 메시지에 URL(인증키 포함)을 절대 싣지 않음
 */
public final class SecureHttp {
    private SecureHttp() {}

    private static final int MAX_BYTES = 512 * 1024;

    public static String get(URL url) throws TransitException {
        Endpoints.requireAllowed(url);
        HttpsURLConnection c = null;
        try {
            URLConnection raw = url.openConnection();
            if (!(raw instanceof HttpsURLConnection)) throw new TransitException("보안 연결(HTTPS)이 아니어서 차단했습니다.");
            c = (HttpsURLConnection) raw;
            c.setInstanceFollowRedirects(false);
            c.setConnectTimeout(7000);
            c.setReadTimeout(8000);
            c.setUseCaches(false);
            c.setRequestMethod("GET");
            c.setRequestProperty("User-Agent", "TransitGlance");
            c.setRequestProperty("Accept", "application/json");
            int code = c.getResponseCode();
            if (code >= 300 && code < 400) throw new TransitException("서버가 다른 주소로 이동을 요구해 차단했습니다.");
            InputStream in = code >= 400 ? c.getErrorStream() : c.getInputStream();
            String body = in == null ? "" : readLimited(in);
            if (body.isEmpty() && code >= 400) throw new TransitException("서버 오류 (HTTP " + code + ")");
            return body;
        } catch (TransitException e) {
            throw e;
        } catch (javax.net.ssl.SSLException e) {
            throw new TransitException("보안 연결(HTTPS)에 실패했습니다. 평문 연결로 전환하지 않습니다.");
        } catch (java.net.SocketTimeoutException e) {
            throw new TransitException("서버 응답 시간이 초과되었습니다.");
        } catch (IOException e) {
            // e.getMessage()에는 URL이 들어갈 수 있으므로 종류만 알린다.
            throw new TransitException("네트워크에 연결할 수 없습니다.");
        } finally {
            if (c != null) c.disconnect();
        }
    }

    private static String readLimited(InputStream in) throws IOException, TransitException {
        try (InputStream s = in) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = s.read(buf)) != -1) {
                out.write(buf, 0, n);
                if (out.size() > MAX_BYTES) throw new TransitException("응답이 비정상적으로 커서 중단했습니다.");
            }
            return new String(out.toByteArray(), StandardCharsets.UTF_8);
        }
    }
}
