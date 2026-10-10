package com.flowwallet.payment.webhook;

import com.flowwallet.payment.payout.BankRefresh;
import com.flowwallet.payment.payout.PayoutAccountService;
import com.flowwallet.payment.payout.PayoutSettler;
import com.flowwallet.payment.provider.PayoutProvider;
import com.flowwallet.payment.provider.dto.ConnectEvent;
import com.flowwallet.payment.provider.exception.PayoutLookupException;
import com.flowwallet.payment.provider.exception.WebhookProcessingException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.Map;

/**
 * Applies a verified Connect webhook. A payout event settles the payout from its current status at Stripe; an account
 * event recomputes the stored bank account's state from the account as Stripe holds it. The status code reports
 * delivery: 200 for an event applied, repeated, ignored or about something this service does not know, and 500 when
 * Stripe could not be read or a refresh raced another writer, so that Stripe delivers the event again.
 * See docs/adr/0036-connect-webhooks-have-their-own-endpoint-and-secret.md.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ConnectWebhookService {
    private final PayoutProvider provider;
    private final PayoutSettler settler;
    private final PayoutAccountService accounts;

    public void process(String payload, Map<String, String> headers) {
        ConnectEvent event = provider.readConnectEvent(payload, headers);
        try {
            switch (event) {
                case ConnectEvent.PayoutChanged changed -> settler.settle(changed.accountId(), changed.payoutId());
                case ConnectEvent.AccountChanged changed -> {
                    if (accounts.refreshBankState(changed.accountId()) == BankRefresh.RACED) {
                        throw new WebhookProcessingException(
                                "Bank account state for event " + event.eventId() + " raced another writer"
                        );
                    }
                }
                case ConnectEvent.Ignored ignored -> {
                    if (ignored.defect() == null) {
                        log.debug("Ignoring Connect event {} of type {}", ignored.eventId(), ignored.type());
                    } else {
                        log.warn(
                                "Ignoring Connect event {} of type {}: {}",
                                ignored.eventId(),
                                ignored.type(),
                                ignored.defect()
                        );
                    }
                }
            }
        } catch (PayoutLookupException e) {
            throw new WebhookProcessingException("Stripe could not be read for Connect event " + event.eventId(), e);
        }
    }
}
