package com.flowwallet.payment.provider.stripe;

import com.flowwallet.payment.provider.PayoutProvider;
import com.flowwallet.payment.provider.dto.PayoutAccountHolder;
import com.flowwallet.payment.provider.dto.PayoutCallResult;
import com.flowwallet.payment.provider.dto.PayoutRefusal;
import com.flowwallet.payment.provider.dto.ProviderBankAccount;
import com.flowwallet.payment.provider.dto.ProviderPayout;
import com.flowwallet.payment.provider.dto.ProviderPayoutStatus;
import com.flowwallet.payment.provider.dto.ProviderTransfer;
import com.flowwallet.payment.provider.exception.PayoutLookupException;
import com.flowwallet.payment.provider.stripe.client.StripeClient;
import com.stripe.exception.CardException;
import com.stripe.exception.IdempotencyException;
import com.stripe.exception.InvalidRequestException;
import com.stripe.exception.StripeException;
import com.stripe.model.Account;
import com.stripe.model.Balance;
import com.stripe.model.BankAccount;
import com.stripe.model.ExternalAccount;
import com.stripe.model.Payout;
import com.stripe.model.Transfer;
import com.stripe.model.TransferReversal;
import com.stripe.param.AccountCreateParams;
import com.stripe.param.ExternalAccountCollectionCreateParams;
import com.stripe.param.PayoutCreateParams;
import com.stripe.param.PayoutListParams;
import com.stripe.param.TransferCreateParams;
import com.stripe.param.TransferListParams;
import com.stripe.param.TransferReversalCollectionCreateParams;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static com.flowwallet.payment.provider.stripe.StripeConstants.META_PAYOUT_ACCOUNT;
import static com.flowwallet.payment.provider.stripe.StripeConstants.META_WITHDRAWAL_REF;

/**
 * Stripe Connect behind {@link PayoutProvider}: the user's connected account and its bank account, a transfer from
 * the platform to that account, a standard payout from it to the bank, and a reversal of the transfer.
 * <p>
 * A create call is a definite refusal only for an HTTP 400 or 402 whose Stripe code is on that call's list. Anything
 * else, a 409, a 429, a 5xx, a timeout or an unlisted code, leaves the outcome unknown, because the object may exist
 * at Stripe. Each idempotency key names its step, the reference and the key generation, and every object carries the
 * reference in its metadata, never a user id. See docs/adr/0035-payouts-run-as-a-step-machine-on-stripe-connect.md
 * and docs/adr/0027-user-ids-stay-out-of-logs-and-provider-metadata.md.
 */
@Component
@RequiredArgsConstructor
public class StripeConnectPayouts implements PayoutProvider {
    /**
     * The codes that make a transfer refusal definite. The codes on this list and the three below are copied from
     * https://docs.stripe.com/error-codes, retrieved on 2026-10-10. A code missing from a list only makes a refusal
     * look unknown, which is safe; a code added by mistake could return money that Stripe moved.
     */
    private static final Map<String, PayoutRefusal> TRANSFER_REFUSALS = Map.of(
            "balance_insufficient", PayoutRefusal.INSUFFICIENT_FUNDS,
            "transfers_not_allowed", PayoutRefusal.OTHER,
            "capability_not_active", PayoutRefusal.OTHER,
            "not_allowed_on_standard_account", PayoutRefusal.OTHER,
            "amount_too_small", PayoutRefusal.OTHER,
            "amount_too_large", PayoutRefusal.OTHER
    );

    /**
     * {@code payouts_not_allowed} counts as a bank account refusal: the account's payouts are off, so its bank
     * account is not ready either.
     */
    private static final Map<String, PayoutRefusal> PAYOUT_REFUSALS = Map.ofEntries(
            Map.entry("balance_insufficient", PayoutRefusal.INSUFFICIENT_FUNDS),
            Map.entry("bank_account_unusable", PayoutRefusal.BANK_ACCOUNT),
            Map.entry("bank_account_declined", PayoutRefusal.BANK_ACCOUNT),
            Map.entry("bank_account_restricted", PayoutRefusal.BANK_ACCOUNT),
            Map.entry("bank_account_bad_routing_numbers", PayoutRefusal.BANK_ACCOUNT),
            Map.entry("account_closed", PayoutRefusal.BANK_ACCOUNT),
            Map.entry("no_account", PayoutRefusal.BANK_ACCOUNT),
            Map.entry("account_number_invalid", PayoutRefusal.BANK_ACCOUNT),
            Map.entry("payouts_not_allowed", PayoutRefusal.BANK_ACCOUNT),
            Map.entry("payouts_limit_exceeded", PayoutRefusal.LIMIT_REACHED),
            Map.entry("account_invalid", PayoutRefusal.OTHER),
            Map.entry("amount_too_small", PayoutRefusal.OTHER),
            Map.entry("amount_too_large", PayoutRefusal.OTHER)
    );

    private static final Map<String, PayoutRefusal> REVERSAL_REFUSALS = Map.of(
            "balance_insufficient", PayoutRefusal.INSUFFICIENT_FUNDS,
            "amount_too_small", PayoutRefusal.OTHER,
            "amount_too_large", PayoutRefusal.OTHER
    );

    /**
     * The codes that make a token's refusal definite: Stripe knows no such token, it was used already, it belongs to
     * live mode, or the bank account it stands for cannot take payouts. Retrieved with the lists above.
     */
    private static final Map<String, PayoutRefusal> BANK_TOKEN_REFUSALS = Map.of(
            "resource_missing", PayoutRefusal.BANK_ACCOUNT,
            "token_already_used", PayoutRefusal.BANK_ACCOUNT,
            "livemode_mismatch", PayoutRefusal.BANK_ACCOUNT,
            "account_number_invalid", PayoutRefusal.BANK_ACCOUNT,
            "routing_number_invalid", PayoutRefusal.BANK_ACCOUNT,
            "bank_account_unusable", PayoutRefusal.BANK_ACCOUNT,
            "bank_account_declined", PayoutRefusal.BANK_ACCOUNT,
            "bank_account_restricted", PayoutRefusal.BANK_ACCOUNT,
            "bank_account_bad_routing_numbers", PayoutRefusal.BANK_ACCOUNT
    );

    /**
     * The bank account statuses Stripe pays out to. {@code errored}, which a failed payout leaves, and
     * {@code verification_failed} or {@code tokenized_account_number_deactivated} are not.
     */
    private static final Set<String> PAYABLE_BANK_STATUSES = Set.of("new", "validated", "verified");

    private static final long PAGE_SIZE = 100;

    private final StripeClient stripeClient;

    /**
     * A v1 account with {@code controller} settings: no Stripe dashboard, the platform collects the requirements and
     * carries fees and losses, the {@code full} agreement with only the {@code transfers} capability, and a manual
     * payout schedule, so money leaves only by a payout this service creates.
     */
    @Override
    public PayoutCallResult<String> createAccount(
            String accountKey,
            Instant tosAcceptedAt,
            PayoutAccountHolder holder
    ) {
        try {
            AccountCreateParams params = accountParams(accountKey, tosAcceptedAt, holder);
            return new PayoutCallResult.Done<>(stripeClient.createAccount(params, "acct-" + accountKey).getId());
        } catch (StripeException e) {
            // Nothing in the request is the user's, so no refusal is theirs to fix.
            return classify(e, Map.of());
        }
    }

    /**
     * Retrieves the account first, because stripe-java attaches a bank account only through the retrieved account,
     * and again afterwards, because attaching the first bank account is what turns its payouts on. The key holds
     * the token, which Stripe accepts once, so a retry under it sends the same parameters.
     */
    @Override
    public PayoutCallResult<ProviderBankAccount> attachBankAccount(String accountId, String accountKey, String token) {
        Account account;
        try {
            account = stripeClient.retrieveAccount(accountId);
        } catch (StripeException e) {
            return new PayoutCallResult.Unknown<>("the account could not be read: " + describe(e));
        }
        if (account.getExternalAccounts() == null) {
            // Stripe lists them only to the account's controller.
            return new PayoutCallResult.Defect<>("Stripe returned the account without its external accounts");
        }

        var params = ExternalAccountCollectionCreateParams.builder()
                .setExternalAccount(token)
                .setDefaultForCurrency(true)
                .build();
        ExternalAccount attached;
        try {
            attached = stripeClient.createExternalAccount(account, params, "bank-" + accountKey + "-" + token);
        } catch (StripeException e) {
            return classify(e, BANK_TOKEN_REFUSALS);
        }
        if (!(attached instanceof BankAccount bankAccount)) {
            return new PayoutCallResult.Defect<>("the token attached " + attached.getClass().getSimpleName());
        }

        Account refreshed;
        try {
            refreshed = stripeClient.retrieveAccount(accountId);
        } catch (StripeException e) {
            return new PayoutCallResult.Unknown<>("the account could not be read after the attach: " + describe(e));
        }
        return defaultBankAccount(refreshed, bankAccount.getCurrency())
                .<PayoutCallResult<ProviderBankAccount>>map(PayoutCallResult.Done::new)
                .orElseGet(() -> new PayoutCallResult.Unknown<>("the account lists no default bank account"));
    }

    @Override
    public BigDecimal availableBalance(String currency) {
        Balance balance;
        try {
            balance = stripeClient.retrieveBalance();
        } catch (StripeException e) {
            throw lookupFailed("the platform's balance", e);
        }
        long available = balance.getAvailable()
                .stream()
                .filter(entry -> entry.getCurrency().equalsIgnoreCase(currency))
                .mapToLong(StripeConnectPayouts::cardFunds)
                .sum();
        return toMajorUnits(available, currency);
    }

    @Override
    public PayoutCallResult<ProviderTransfer> createTransfer(
            String reference,
            String accountId,
            BigDecimal amount,
            String currency,
            int keyGen
    ) {
        var params = TransferCreateParams.builder()
                .setAmount(toMinorUnits(amount, currency))
                .setCurrency(currency.toLowerCase(Locale.ROOT))
                .setDestination(accountId)
                .setTransferGroup(transferGroup(reference))
                .putMetadata(META_WITHDRAWAL_REF, reference)
                .build();
        try {
            Transfer transfer = stripeClient.createTransfer(params, idempotencyKey("transfer", reference, keyGen));
            return new PayoutCallResult.Done<>(toTransfer(transfer));
        } catch (StripeException e) {
            return classify(e, TRANSFER_REFUSALS);
        }
    }

    @Override
    public List<ProviderTransfer> findTransfers(String reference) {
        var params = TransferListParams.builder()
                .setTransferGroup(transferGroup(reference))
                .setLimit(PAGE_SIZE)
                .build();
        try {
            return stripeClient.listTransfers(params)
                    .stream()
                    .map(StripeConnectPayouts::toTransfer)
                    .toList();
        } catch (StripeException e) {
            throw lookupFailed("the transfers of " + reference, e);
        }
    }

    @Override
    public ProviderTransfer retrieveTransfer(String transferId) {
        try {
            return toTransfer(stripeClient.retrieveTransfer(transferId));
        } catch (StripeException e) {
            throw lookupFailed("transfer " + transferId, e);
        }
    }

    @Override
    public PayoutCallResult<ProviderPayout> createPayout(
            String accountId,
            String reference,
            BigDecimal amount,
            String currency,
            int keyGen
    ) {
        var params = PayoutCreateParams.builder()
                .setAmount(toMinorUnits(amount, currency))
                .setCurrency(currency.toLowerCase(Locale.ROOT))
                .setMethod(PayoutCreateParams.Method.STANDARD)
                .putMetadata(META_WITHDRAWAL_REF, reference)
                .build();
        try {
            Payout payout = stripeClient.createPayout(params, idempotencyKey("payout", reference, keyGen), accountId);
            return new PayoutCallResult.Done<>(toPayout(payout));
        } catch (StripeException e) {
            return classify(e, PAYOUT_REFUSALS);
        }
    }

    @Override
    public List<ProviderPayout> findPayouts(String accountId, String reference, Instant createdSince) {
        var params = PayoutListParams.builder()
                .setCreated(PayoutListParams.Created.builder().setGte(createdSince.getEpochSecond()).build())
                .setLimit(PAGE_SIZE)
                .build();
        try {
            return stripeClient.listPayouts(params, accountId)
                    .stream()
                    .map(StripeConnectPayouts::toPayout)
                    .filter(payout -> reference.equals(payout.reference()))
                    .toList();
        } catch (StripeException e) {
            throw lookupFailed("the payouts of " + reference, e);
        }
    }

    @Override
    public ProviderPayout retrievePayout(String accountId, String payoutId) {
        try {
            return toPayout(stripeClient.retrievePayout(payoutId, accountId));
        } catch (StripeException e) {
            throw lookupFailed("payout " + payoutId, e);
        }
    }

    /**
     * Retrieves the transfer first, because its reversed amount is the only record of an earlier attempt that
     * succeeded, and stripe-java's static API creates a reversal only from the retrieved transfer. Only the part not
     * yet reversed is reversed, so a retry under the same key sends the same amount.
     */
    @Override
    public PayoutCallResult<String> reverseTransfer(
            String transferId,
            String reference,
            BigDecimal amount,
            String currency,
            int keyGen
    ) {
        Transfer transfer;
        try {
            transfer = stripeClient.retrieveTransfer(transferId);
        } catch (StripeException e) {
            return new PayoutCallResult.Unknown<>("the transfer could not be read: " + describe(e));
        }

        long target = toMinorUnits(amount, currency);
        long reversed = transfer.getAmountReversed() == null ? 0 : transfer.getAmountReversed();
        if (reversed >= target) {
            List<TransferReversal> reversals = transfer.getReversals() == null
                    ? List.of()
                    : transfer.getReversals().getData();
            return reversalFor(reversals, reference)
                    .<PayoutCallResult<String>>map(found -> new PayoutCallResult.Done<>(found.getId()))
                    .orElseGet(() -> new PayoutCallResult.Unknown<>("the transfer is reversed but lists no reversal"));
        }

        var params = TransferReversalCollectionCreateParams.builder()
                .setAmount(target - reversed)
                .putMetadata(META_WITHDRAWAL_REF, reference)
                .build();
        try {
            TransferReversal reversal = stripeClient.createReversal(
                    transfer,
                    params,
                    idempotencyKey("reversal", reference, keyGen)
            );
            return new PayoutCallResult.Done<>(reversal.getId());
        } catch (StripeException e) {
            return classify(e, REVERSAL_REFUSALS);
        }
    }

    private static AccountCreateParams accountParams(
            String accountKey,
            Instant tosAcceptedAt,
            PayoutAccountHolder holder
    ) {
        var controller = AccountCreateParams.Controller.builder()
                .setStripeDashboard(AccountCreateParams.Controller.StripeDashboard.builder()
                        .setType(AccountCreateParams.Controller.StripeDashboard.Type.NONE)
                        .build())
                .setRequirementCollection(AccountCreateParams.Controller.RequirementCollection.APPLICATION)
                .setFees(AccountCreateParams.Controller.Fees.builder()
                        .setPayer(AccountCreateParams.Controller.Fees.Payer.APPLICATION)
                        .build())
                .setLosses(AccountCreateParams.Controller.Losses.builder()
                        .setPayments(AccountCreateParams.Controller.Losses.Payments.APPLICATION)
                        .build())
                .build();
        var individual = AccountCreateParams.Individual.builder()
                .setFirstName(holder.firstName())
                .setLastName(holder.lastName())
                .setEmail(holder.email())
                .setPhone(holder.phone())
                .setDob(AccountCreateParams.Individual.Dob.builder()
                        .setDay((long) holder.dateOfBirth().getDayOfMonth())
                        .setMonth((long) holder.dateOfBirth().getMonthValue())
                        .setYear((long) holder.dateOfBirth().getYear())
                        .build())
                .setAddress(AccountCreateParams.Individual.Address.builder()
                        .setLine1(holder.addressLine1())
                        .setCity(holder.city())
                        .setPostalCode(holder.postalCode())
                        .setCountry(holder.country())
                        .build())
                .build();
        var payoutSchedule = AccountCreateParams.Settings.Payouts.Schedule.builder()
                .setInterval(AccountCreateParams.Settings.Payouts.Schedule.Interval.MANUAL)
                .build();
        return AccountCreateParams.builder()
                .setCountry(holder.country())
                .setEmail(holder.email())
                .setBusinessType(AccountCreateParams.BusinessType.INDIVIDUAL)
                .setController(controller)
                .setCapabilities(AccountCreateParams.Capabilities.builder()
                        .setTransfers(AccountCreateParams.Capabilities.Transfers.builder().setRequested(true).build())
                        .build())
                .setTosAcceptance(AccountCreateParams.TosAcceptance.builder()
                        .setDate(tosAcceptedAt.getEpochSecond())
                        .setIp(holder.tosIp())
                        .setServiceAgreement("full")
                        .build())
                .setIndividual(individual)
                .setBusinessProfile(AccountCreateParams.BusinessProfile.builder()
                        .setMcc(holder.merchantCategoryCode())
                        .setProductDescription(holder.productDescription())
                        .build())
                .setSettings(AccountCreateParams.Settings.builder()
                        .setPayouts(AccountCreateParams.Settings.Payouts.builder().setSchedule(payoutSchedule).build())
                        .build())
                .putMetadata(META_PAYOUT_ACCOUNT, accountKey)
                .build();
    }

    /**
     * The bank account Stripe pays the currency out to now: the account's default for it, read after the attach. A
     * late retry of an older token or a concurrent request can leave another bank account the default, and the row
     * records that one rather than the one this request attached. Ready when the account pays out and the bank
     * account's status is one Stripe pays out to.
     * See docs/adr/0035-payouts-run-as-a-step-machine-on-stripe-connect.md.
     */
    private static Optional<ProviderBankAccount> defaultBankAccount(Account account, String currency) {
        if (account.getExternalAccounts() == null) {
            return Optional.empty();
        }
        boolean paysOut = Boolean.TRUE.equals(account.getPayoutsEnabled());
        return account.getExternalAccounts()
                .getData()
                .stream()
                .filter(BankAccount.class::isInstance)
                .map(BankAccount.class::cast)
                .filter(listed -> Boolean.TRUE.equals(listed.getDefaultForCurrency()))
                .filter(listed -> currency.equalsIgnoreCase(listed.getCurrency()))
                .findFirst()
                .map(listed -> new ProviderBankAccount(
                        listed.getId(),
                        listed.getLast4(),
                        listed.getCurrency().toUpperCase(Locale.ROOT),
                        paysOut && PAYABLE_BANK_STATUSES.contains(listed.getStatus())
                ));
    }

    /**
     * The reversal labelled with the reference, or else the newest, which Stripe lists first. The transfer embeds
     * only its latest reversals, and one made by hand carries no label.
     */
    private static Optional<TransferReversal> reversalFor(List<TransferReversal> reversals, String reference) {
        return reversals.stream()
                .filter(reversal -> reversal.getMetadata() != null)
                .filter(reversal -> reference.equals(reversal.getMetadata().get(META_WITHDRAWAL_REF)))
                .findFirst()
                .or(() -> reversals.stream().findFirst());
    }

    /**
     * A transfer without a {@code source_type} draws on the card balance alone, so only that part covers it.
     * Deposits take cards only, so the two match unless funds of another kind arrive.
     * See docs/adr/0028-deposits-accept-cards-only.md.
     */
    private static long cardFunds(Balance.Available entry) {
        Balance.Available.SourceTypes sourceTypes = entry.getSourceTypes();
        return sourceTypes == null || sourceTypes.getCard() == null ? entry.getAmount() : sourceTypes.getCard();
    }

    /**
     * The prefix keeps a withdrawal's keys apart from a deposit's, which is the bare reference.
     */
    private static String idempotencyKey(String step, String reference, int keyGen) {
        return "wd-" + step + "-" + reference + "-" + keyGen;
    }

    private static String transferGroup(String reference) {
        return "wd-" + reference;
    }

    /**
     * A 400 {@code InvalidRequestException} or a 402 {@code CardException} with a listed code is definite. stripe-java
     * also maps a 404 to {@code InvalidRequestException}, which points at a wrong id rather than the request, and a
     * 409 to {@code ApiException}, which is a concurrent request under the same key; neither is on the list.
     */
    private static <T> PayoutCallResult<T> classify(StripeException e, Map<String, PayoutRefusal> refusals) {
        if (e instanceof IdempotencyException) {
            return new PayoutCallResult.Defect<>(describe(e));
        }
        PayoutRefusal refusal = e.getCode() == null ? null : refusals.get(e.getCode());
        Integer status = e.getStatusCode();
        boolean definite = refusal != null
                && ((e instanceof InvalidRequestException && Integer.valueOf(400).equals(status))
                || (e instanceof CardException && Integer.valueOf(402).equals(status)));
        return definite
                ? new PayoutCallResult.Refused<>(refusal, e.getCode())
                : new PayoutCallResult.Unknown<>(describe(e));
    }

    private static String describe(StripeException e) {
        return "%s, status %s, code %s: %s".formatted(
                e.getClass().getSimpleName(),
                e.getStatusCode(),
                e.getCode(),
                e.getMessage()
        );
    }

    private static PayoutLookupException lookupFailed(String what, StripeException e) {
        return new PayoutLookupException("Stripe could not be asked about " + what + ": " + describe(e), e);
    }

    private static ProviderTransfer toTransfer(Transfer transfer) {
        String currency = transfer.getCurrency();
        long reversed = transfer.getAmountReversed() == null ? 0 : transfer.getAmountReversed();
        return new ProviderTransfer(
                transfer.getId(),
                transfer.getDestination(),
                toMajorUnits(transfer.getAmount(), currency),
                toMajorUnits(reversed, currency),
                currency.toUpperCase(Locale.ROOT)
        );
    }

    private static ProviderPayout toPayout(Payout payout) {
        String currency = payout.getCurrency();
        return new ProviderPayout(
                payout.getId(),
                status(payout.getStatus()),
                payout.getFailureCode(),
                payout.getMetadata() == null ? null : payout.getMetadata().get(META_WITHDRAWAL_REF),
                toMajorUnits(payout.getAmount(), currency),
                currency.toUpperCase(Locale.ROOT)
        );
    }

    private static ProviderPayoutStatus status(@Nullable String status) {
        return switch (status == null ? "" : status) {
            case "pending" -> ProviderPayoutStatus.PENDING;
            case "in_transit" -> ProviderPayoutStatus.IN_TRANSIT;
            case "paid" -> ProviderPayoutStatus.PAID;
            case "failed" -> ProviderPayoutStatus.FAILED;
            case "canceled" -> ProviderPayoutStatus.CANCELED;
            default -> ProviderPayoutStatus.UNRECOGNIZED;
        };
    }

    /**
     * Exact: the caller holds the amount to the payout grid, so the shift never rounds. See
     * docs/adr/0015-currency-precision-and-no-rounding.md.
     */
    private static long toMinorUnits(BigDecimal amount, String currency) {
        return amount.movePointRight(StripeCurrencyRules.of(currency).transmitExponent()).longValueExact();
    }

    private static BigDecimal toMajorUnits(long minor, String currency) {
        return BigDecimal.valueOf(minor).movePointLeft(StripeCurrencyRules.of(currency).transmitExponent());
    }
}
