package com.flowwallet.payment.support;

import com.stripe.Stripe;
import com.stripe.model.PaymentIntent;
import com.stripe.model.Payout;
import com.stripe.model.Transfer;
import com.stripe.model.TransferReversal;
import org.jspecify.annotations.Nullable;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import static com.flowwallet.payment.provider.stripe.StripeConstants.META_WITHDRAWAL_REF;

/**
 * Stripe objects and webhook bodies as the Stripe API returns them, for stubbing {@code StripeClient} and posting
 * webhooks. Amounts are in Stripe's minor units. The payout objects carry the reference under the metadata key and
 * in the transfer group Payment Service writes, so its lookups find them.
 */
public final class StripeFixtures {
    private StripeFixtures() {
    }

    /**
     * A visibly fake Stripe id with the prefix of its object, such as {@code tr} or {@code po}.
     */
    public static String fakeId(String prefix) {
        return prefix + "_integration-test-" + UUID.randomUUID();
    }

    public static PaymentIntent paymentIntent(String id, String status, long amount, String currency) {
        PaymentIntent intent = new PaymentIntent();
        intent.setId(id);
        intent.setObject("payment_intent");
        intent.setStatus(status);
        intent.setAmount(amount);
        intent.setCurrency(currency);
        intent.setClientSecret(id + "_secret_integration-test");
        return intent;
    }

    /**
     * A {@code payment_intent.*} event at the API version the SDK pins, so the parser takes no version detour.
     *
     * @param created the event's {@code created}, in epoch seconds
     */
    public static String paymentIntentEvent(
            String eventId,
            String type,
            long created,
            String intentId,
            String status,
            long amount,
            String currency
    ) {
        return """
                {
                  "id": "%s",
                  "object": "event",
                  "api_version": "%s",
                  "created": %d,
                  "type": "%s",
                  "data": {
                    "object": {
                      "id": "%s",
                      "object": "payment_intent",
                      "amount": %d,
                      "currency": "%s",
                      "status": "%s"
                    }
                  }
                }
                """.formatted(eventId, Stripe.API_VERSION, created, type, intentId, amount, currency, status);
    }

    /**
     * A transfer from the platform to a connected account, nothing of it reversed, in the reference's group.
     */
    public static Transfer transfer(String id, String destination, long amount, String currency, String reference) {
        Transfer transfer = new Transfer();
        transfer.setId(id);
        transfer.setObject("transfer");
        transfer.setAmount(amount);
        transfer.setAmountReversed(0L);
        transfer.setCurrency(currency);
        transfer.setDestination(destination);
        transfer.setTransferGroup("wd-" + reference);
        transfer.setMetadata(Map.of(META_WITHDRAWAL_REF, reference));
        return transfer;
    }

    /**
     * A payout from a connected account to its bank account.
     *
     * @param status Stripe's status, such as {@code pending}, {@code paid} or {@code failed}
     */
    public static Payout payout(
            String id,
            String status,
            long amount,
            String currency,
            String reference,
            String destination,
            @Nullable String failureCode
    ) {
        Payout payout = new Payout();
        payout.setId(id);
        payout.setObject("payout");
        payout.setStatus(status);
        payout.setAmount(amount);
        payout.setCurrency(currency);
        payout.setMetadata(Map.of(META_WITHDRAWAL_REF, reference));
        payout.setDestination(destination);
        payout.setFailureCode(failureCode);
        return payout;
    }

    public static TransferReversal transferReversal(String id, long amount, String currency, String reference) {
        TransferReversal reversal = new TransferReversal();
        reversal.setId(id);
        reversal.setObject("transfer_reversal");
        reversal.setAmount(amount);
        reversal.setCurrency(currency);
        reversal.setMetadata(Map.of(META_WITHDRAWAL_REF, reference));
        return reversal;
    }

    /**
     * A {@code payout.*} event from a connected account, at the API version the SDK pins. Payment Service reads only
     * the envelope and the payout's id from it and asks Stripe for the payout, so the status in the body may be stale.
     */
    public static String connectPayoutEvent(
            String eventId,
            String type,
            String accountId,
            String payoutId,
            String status,
            long amount,
            String currency
    ) {
        return """
                {
                  "id": "%s",
                  "object": "event",
                  "api_version": "%s",
                  "created": %d,
                  "type": "%s",
                  "account": "%s",
                  "data": {
                    "object": {
                      "id": "%s",
                      "object": "payout",
                      "amount": %d,
                      "currency": "%s",
                      "status": "%s"
                    }
                  }
                }
                """.formatted(
                eventId,
                Stripe.API_VERSION,
                Instant.now().getEpochSecond(),
                type,
                accountId,
                payoutId,
                amount,
                currency,
                status
        );
    }
}
