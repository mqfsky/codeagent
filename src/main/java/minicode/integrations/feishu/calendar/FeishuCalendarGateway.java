package minicode.integrations.feishu.calendar;

import minicode.core.turn.CancellationToken;

/**
 * Creates events in the authenticated Feishu user's primary calendar.
 */
public interface FeishuCalendarGateway {
    FeishuCalendarCreateResult create(FeishuCalendarCreateRequest request,
                                      String idempotencyKey,
                                      CancellationToken cancellationToken);
}
