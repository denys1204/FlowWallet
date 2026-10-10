package com.flowwallet.payment.provider.dto;

import java.time.LocalDate;

/**
 * The person and business a connected account is opened for. In test mode every account carries the same fake
 * identity, because Payment Service never learns who the user is.
 *
 * @param country ISO 3166-1 alpha-2 code of the account's country
 * @param tosIp   the address recorded as accepting the provider's terms; the services never see the client's own
 */
public record PayoutAccountHolder(
        String country,
        String firstName,
        String lastName,
        String email,
        LocalDate dateOfBirth,
        String addressLine1,
        String city,
        String postalCode,
        String phone,
        String productDescription,
        String merchantCategoryCode,
        String tosIp
) {
}
