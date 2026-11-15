package kr.local.transitglance.core;

/** 사용자에게 보여줘도 안전한 메시지만 담는 예외. URL·인증키·좌표는 절대 넣지 않는다. */
public final class TransitException extends Exception {
    public TransitException(String safeMessage) {
        super(safeMessage);
    }
}
