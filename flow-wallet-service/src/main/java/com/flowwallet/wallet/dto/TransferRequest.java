package com.flowwallet.wallet.dto;

import com.flowwallet.platform.security.CurrentUserIdResolver;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;

import java.math.BigDecimal;

/**
 * Client request to move money from the caller's wallet to another user's wallet in the same currency.
 * <p>
 * There is no currency field. The path names the caller's wallet, and that wallet's currency applies to both
 * sides, so a transfer between currencies cannot even be expressed. A currency here would only be a way for the
 * body and the path to disagree.
 * <p>
 * The recipient is named by user id and never searched for by anything else. The wallet keeps no user
 * registry, and a lookup by name or e-mail would make it a directory of who holds a wallet.
 * <p>
 * {@code to} is checked with {@code CurrentUserIdResolver}'s own expression, the identity rule it applies to the
 * caller: a version 4 or 7 UUID of the RFC variant, in either case, in ASCII hex digits. Hibernate Validator's
 * {@code @UUID} is not used, although it can be configured to state the same rule, because its validator does not
 * keep it. It counts dashes without bounding them, so a 36-character value with a fifth dash throws inside
 * validation and reaches the caller as a 500, and it reads digits with {@code Character.digit}, which accepts
 * non-ASCII digits the resolver refuses. An id that can never be a caller can never own a wallet, so it is
 * refused with a 400 before any query. Unlike the header, surrounding whitespace is refused rather than
 * stripped.
 * <p>
 * The amount's precision is not checked here. It depends on the currency, which only the path carries, and
 * {@code @Digits} counts a {@code BigDecimal}'s trailing zeros, so the service applies
 * {@code AmountPrecision} instead.
 *
 * @param to     the recipient's user id
 * @param amount amount in major currency units
 */
public record TransferRequest(
        @NotNull(message = "Recipient is required")
        @Pattern(
                regexp = CurrentUserIdResolver.RANDOM_UUID_REGEX,
                message = "Recipient must be a user id: a version 4 or 7 UUID"
        )
        String to,

        @NotNull(message = "Amount is required")
        @Positive(message = "Amount must be greater than zero")
        BigDecimal amount
) {}
