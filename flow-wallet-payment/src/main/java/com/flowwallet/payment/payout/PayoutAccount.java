package com.flowwallet.payment.payout;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.Instant;
import java.util.Locale;
import java.util.Objects;

/**
 * One user's connected account and bank account at Stripe. The row is reserved before Stripe is called, with a
 * server-generated {@code accountKey} that keys the account's creation, so a lost race reads the winner instead of
 * creating a second account. {@code tosAcceptedAt} is the time the user accepted Stripe's terms in the request that
 * created the row, sent verbatim on every retry so the parameters under the key never change.
 * <p>
 * FlowWallet holds only Stripe's ids, the last four digits and the last token applied: a bank token works once, so a
 * request that repeats it is answered from the row.
 * See docs/adr/0035-payouts-run-as-a-step-machine-on-stripe-connect.md.
 */
@Entity
@Getter
@Builder
@AllArgsConstructor
@Table(name = "payout_accounts")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class PayoutAccount {
    @Id
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "payout_accounts_seq_gen")
    @SequenceGenerator(name = "payout_accounts_seq_gen", sequenceName = "payout_accounts_seq", allocationSize = 50)
    private Long id;

    @Column(name = "user_id", nullable = false, unique = true, length = 64)
    private String userId;

    @Column(name = "account_key", nullable = false, unique = true, length = 64)
    private String accountKey;

    @Column(name = "stripe_account_id", unique = true, length = 128)
    private String stripeAccountId;

    @Column(name = "tos_accepted_at", nullable = false)
    private Instant tosAcceptedAt;

    @Column(name = "external_account_id", length = 128)
    private String externalAccountId;

    @Column(name = "bank_last4", length = 4)
    private String bankLast4;

    @Column(name = "bank_currency", length = 3)
    private String bankCurrency;

    @Column(name = "last_token", length = 128)
    private String lastToken;

    @Enumerated(EnumType.STRING)
    @Column(name = "bank_state", nullable = false, length = 20)
    private BankAccountState bankState;

    @Version
    @Column(name = "version", nullable = false)
    private Long version;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    /**
     * The row reserved before the account is created at Stripe. No bank account is ready until one is attached.
     */
    public static PayoutAccount reserve(String userId, String accountKey, Instant tosAcceptedAt) {
        return PayoutAccount.builder()
                .userId(userId)
                .accountKey(accountKey)
                .tosAcceptedAt(tosAcceptedAt)
                .bankState(BankAccountState.NOT_SET)
                .build();
    }

    /**
     * Records the connected account Stripe created under the account's key. A retry under the key answers with the
     * same account, so recording a different one is a defect.
     */
    public void recordStripeAccount(String stripeAccountId) {
        Objects.requireNonNull(stripeAccountId, "stripeAccountId");
        if (this.stripeAccountId != null && !this.stripeAccountId.equals(stripeAccountId)) {
            throw new IllegalStateException("Payout account " + accountKey + " already has another Stripe account");
        }
        this.stripeAccountId = stripeAccountId;
    }

    /**
     * Records the bank account a token attached as the default for its currency, and the state Stripe reports for it.
     */
    public void recordBankAccount(
            String token,
            String externalAccountId,
            String last4,
            String currency,
            BankAccountState state
    ) {
        this.lastToken = Objects.requireNonNull(token, "token");
        this.externalAccountId = Objects.requireNonNull(externalAccountId, "externalAccountId");
        this.bankLast4 = last4;
        // Stripe reports the currency in lower case, a payout carries it in upper case.
        this.bankCurrency = currency == null ? null : currency.toUpperCase(Locale.ROOT);
        this.bankState = Objects.requireNonNull(state, "state");
    }

    /**
     * Whether a payout in this currency can go out: the bank account is {@code READY} and is the default for it.
     */
    public boolean isReadyFor(String currency) {
        return bankState == BankAccountState.READY && bankCurrency != null && bankCurrency.equalsIgnoreCase(currency);
    }

    /**
     * Sets the state Stripe reports now, from a payout refused for a bank reason or an account event.
     *
     * @return whether the state changed
     */
    public boolean updateBankState(BankAccountState state) {
        Objects.requireNonNull(state, "state");
        if (bankState == state) {
            return false;
        }
        this.bankState = state;
        return true;
    }

    /**
     * Whether this token is the one last attached, which Stripe would refuse a second time.
     */
    public boolean isLastTokenApplied(String token) {
        return token != null && token.equals(lastToken);
    }
}
