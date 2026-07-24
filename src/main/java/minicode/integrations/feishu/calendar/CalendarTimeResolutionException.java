package minicode.integrations.feishu.calendar;

public final class CalendarTimeResolutionException extends RuntimeException {
    public CalendarTimeResolutionException(String message) {
        super(message);
    }

    public CalendarTimeResolutionException(String message, Throwable cause) {
        super(message, cause);
    }
}
