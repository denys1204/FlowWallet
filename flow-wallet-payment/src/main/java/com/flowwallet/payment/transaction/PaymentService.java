package com.flowwallet.payment.transaction;

import com.flowwallet.payment.dto.CreatePaymentIntentRequest;
import com.flowwallet.payment.dto.PaymentIntentResponse;
import com.flowwallet.payment.provider.PaymentProvider;
import com.flowwallet.payment.provider.PaymentProviderFactory;
import com.flowwallet.payment.provider.PaymentProviderStrategy;
import com.flowwallet.payment.provider.dto.PaymentInitiationResult;
import com.flowwallet.payment.provider.dto.PaymentRequestContext;
import com.flowwallet.payment.transaction.mapper.PaymentEventMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

import java.util.Optional;

@Slf4j
@Service
@RequiredArgsConstructor
public class PaymentService {
    private final PaymentProviderFactory factory;
    private final PaymentTransactionStore store;
    private final PaymentEventMapper mapper;

    /**
     * Not {@code @Transactional}: the provider call runs between the store's short transactions, with no
     * connection held, and a lost reservation is explained from a fresh read once its transaction has rolled back.
     * See docs/adr/0006-short-transactions-across-bean-boundaries.md and docs/adr/0013-deposit-initiation.md.
     */
    public PaymentIntentResponse initiatePayment(CreatePaymentIntentRequest request, String userId) {
        // The user id is a bearer credential (docs/adr/0003-caller-identity-and-trust-boundary.md) and never goes
        // into a log line; the reference already identifies the row.
        // See docs/adr/0027-user-ids-stay-out-of-logs-and-provider-metadata.md.
        log.info(
                "Initiating payment for reference {} with amount {} {}",
                request.transactionReference(),
                request.amount(),
                request.currency()
        );

        Optional<PaymentTransaction> existing = store.findOwnedBy(request.transactionReference(), userId);
        if (existing.isPresent()) {
            Optional<PaymentIntentResponse> replay = replayOf(existing.get(), request);
            if (replay.isPresent()) {
                return replay.get();
            }
            log.warn(
                    "Reference {} was reserved but never reached the provider; retrying initiation",
                    request.transactionReference()
            );
        }

        // Factory lookup and validation can refuse, so they run before reserve and a refusal leaves the reference free.
        PaymentProvider provider = factory.resolve(request.providerName());
        PaymentProviderStrategy strategy = factory.getStrategy(provider);
        // One context serves validation and initiation, so the provider is sent exactly what was vetted. A reused
        // row holds the same terms, as differencesFrom has just confirmed.
        PaymentRequestContext context = new PaymentRequestContext(
                request.transactionReference(),
                request.amount(),
                request.currency(),
                userId
        );
        strategy.validateRequest(context);

        // A reserved row whose initiation was never recorded is reused. The reference is Stripe's idempotency key,
        // so the repeated call creates no second intent.
        PaymentTransaction reserved;
        if (existing.isPresent()) {
            reserved = existing.get();
        } else {
            try {
                reserved = store.reserve(request, userId, provider);
            } catch (DataIntegrityViolationException e) {
                return answerFromTheWinningRow(request, userId, e);
            }
        }

        PaymentInitiationResult result = strategy.initiatePayment(context);

        PaymentTransaction initiated = store.recordInitiation(
                reserved.getId(),
                result.providerTransactionId(),
                result.providerData()
        );

        return mapper.toResponse(initiated);
    }

    /**
     * Judges a row that already holds the reference against a request for this owner: other terms or a settled
     * payment are a 409, and an initiated row is answered as it stands. See
     * docs/adr/0005-client-supplied-idempotency-keys.md.
     * <p>
     * Neither refusal below logs: {@link com.flowwallet.platform.web.GlobalExceptionHandler} already logs every
     * 4xx {@code ApiException} at WARN with its message, which names the reference and the reason, so a second
     * log here would only repeat it.
     *
     * @return the original response, or empty for a row that was reserved but never initiated
     */
    private Optional<PaymentIntentResponse> replayOf(
            PaymentTransaction transaction,
            CreatePaymentIntentRequest request
    ) {
        transaction.differencesFrom(request).ifPresent(differences -> {
            throw DuplicateTransactionReferenceException.forConflictingPayload(
                    request.transactionReference(),
                    differences
            );
        });

        if (transaction.isSettled()) {
            throw DuplicateTransactionReferenceException.forSettledReference(request.transactionReference());
        }

        if (transaction.isInitiated()) {
            log.info("Returning existing payment transaction for reference: {}", request.transactionReference());
            return Optional.of(mapper.toResponse(transaction));
        }
        return Optional.empty();
    }

    /**
     * Explains a lost reservation from the row that won it, read in a fresh transaction because the violation
     * rolled the reservation back. The winner is judged like any retry: another owner, other terms or a settled
     * payment get a 409, and an initiated row is replayed. A winner still waiting for the provider gets a 503, so
     * the caller retries under the same key instead of being sent to a new one. No row means another constraint
     * fired, and the violation is rethrown as the defect it is.
     * See docs/adr/0024-deposit-initiation-settles-its-own-races.md.
     */
    private PaymentIntentResponse answerFromTheWinningRow(
            CreatePaymentIntentRequest request,
            String userId,
            DataIntegrityViolationException violation
    ) {
        PaymentTransaction winner = store.findOwnedBy(request.transactionReference(), userId)
                .orElseThrow(() -> violation);

        return replayOf(winner, request).orElseThrow(() -> {
            log.info(
                    "Reference {} was reserved by a concurrent request that has not reached the provider yet",
                    request.transactionReference()
            );
            return new PaymentInProgressException(request.transactionReference());
        });
    }
}
