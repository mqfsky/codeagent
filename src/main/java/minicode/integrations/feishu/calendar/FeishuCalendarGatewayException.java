package minicode.integrations.feishu.calendar;

/**
 * Reports a sanitized failure at the Feishu calendar integration boundary.
 */
public final class FeishuCalendarGatewayException extends RuntimeException {
    public FeishuCalendarGatewayException(String message) {
        super(message);
    }

    public FeishuCalendarGatewayException(String message, Throwable cause) {
        super(message, cause);
    }
}
