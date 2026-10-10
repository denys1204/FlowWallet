package com.flowwallet.wallet.bankaccount;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClientException;
import tools.jackson.databind.ObjectMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@ExtendWith(OutputCaptureExtension.class)
class BankAccountServiceTest {
    private static final String USER = "0b9e5c1a-7d2f-4a3b-8c4d-5e6f7a8b9c0d";
    private static final BankAccountRequest REQUEST = new BankAccountRequest("btok_1", true);

    private final PayoutAccountClient payoutAccounts = mock(PayoutAccountClient.class);
    private final BankAccountService service = new BankAccountService(payoutAccounts, new ObjectMapper());

    private static HttpClientErrorException refusal(HttpStatus status, String body) {
        return HttpClientErrorException.create(
                status,
                status.getReasonPhrase(),
                HttpHeaders.EMPTY,
                body.getBytes(),
                null
        );
    }

    @Test
    void theCallersTokenAndAcceptanceReachPaymentServiceAndItsAnswerComesBack() {
        // Guards the relay sending another user's id or dropping the acceptance, which Payment Service would refuse.
        when(payoutAccounts.set(USER, new PayoutAccountCommand("btok_1", true)))
                .thenReturn(new PayoutAccountResult("READY", "2874", "PLN"));

        assertThat(service.set(USER, REQUEST)).isEqualTo(new BankAccountResponse("READY", "2874", "PLN"));
    }

    @Test
    void aRefusedTokenIsPassedOnAsA400InPaymentServicesWords() {
        // Guards a token Stripe refused reaching the user as a 502, which would send them to retry a dead token.
        when(payoutAccounts.set(anyString(), any())).thenThrow(refusal(
                HttpStatus.BAD_REQUEST,
                "{\"detail\": \"Stripe refused the bank account token. Create a new token and retry.\"}"
        ));

        assertThatThrownBy(() -> service.set(USER, REQUEST))
                .isInstanceOf(BankAccountRejectedException.class)
                .hasMessage("Stripe refused the bank account token. Create a new token and retry.");
    }

    @Test
    void anyOtherRefusalIsTheWalletsOwnFaultAndA502() {
        // Guards passing a 401 about the wallet's own header on to the user, who could do nothing about it.
        when(payoutAccounts.set(anyString(), any())).thenThrow(refusal(HttpStatus.UNAUTHORIZED, "{}"));

        assertThatThrownBy(() -> service.set(USER, REQUEST)).isInstanceOf(PayoutAccountUnavailableException.class);
    }

    @Test
    void aFailureOrTimeoutOrUnreadableAnswerIsA502ToRetryWithTheSameToken() {
        // Guards a 500 for an outage, and a timeout reported as a refusal although Payment Service may have attached
        // the token: the retry with the same token is answered from its row.
        when(payoutAccounts.set(anyString(), any()))
                .thenThrow(HttpServerErrorException.create(
                        HttpStatus.BAD_GATEWAY,
                        "Bad Gateway",
                        HttpHeaders.EMPTY,
                        new byte[0],
                        null
                ))
                .thenThrow(new ResourceAccessException("Read timed out"))
                .thenThrow(new RestClientException("unknown status"));

        for (int attempt = 0; attempt < 3; attempt++) {
            assertThatThrownBy(() -> service.set(USER, REQUEST))
                    .isInstanceOf(PayoutAccountUnavailableException.class)
                    .hasMessageContaining("same token");
        }
    }

    @Test
    void theBankAccountIsReadForTheCaller() {
        // Guards a read answered for another user than the caller.
        when(payoutAccounts.get(USER)).thenReturn(new PayoutAccountResult("NOT_SET", null, null));

        assertThat(service.get(USER)).isEqualTo(new BankAccountResponse("NOT_SET", null, null));
    }

    @Test
    void aFailedReadIsA502ThatAsksForAPlainRetry() {
        // Guards a read failure telling the caller to retry with a token the read never had.
        when(payoutAccounts.get(USER)).thenThrow(new ResourceAccessException("Read timed out"));

        assertThatThrownBy(() -> service.get(USER))
                .isInstanceOf(PayoutAccountUnavailableException.class)
                .hasMessage("Payment Service is unavailable. Retry.");
    }

    @Test
    void anAnswerWithoutABodyIsUnreadableAndA502() {
        // Guards a 2xx with an empty body reaching the caller as a 500 from a null result.
        when(payoutAccounts.set(anyString(), any())).thenReturn(null);

        assertThatThrownBy(() -> service.set(USER, REQUEST))
                .isInstanceOf(PayoutAccountUnavailableException.class)
                .hasMessageContaining("could not read");
    }

    @Test
    void noLogLineNamesTheUserOrTheToken(CapturedOutput output) {
        // Guards a user id, which spends the user's balance, or a bank token reaching a log line on any failure path.
        when(payoutAccounts.set(anyString(), any()))
                .thenThrow(new ResourceAccessException("Read timed out"))
                .thenThrow(HttpServerErrorException.create(
                        HttpStatus.BAD_GATEWAY,
                        "Bad Gateway",
                        HttpHeaders.EMPTY,
                        new byte[0],
                        null
                ))
                .thenThrow(refusal(HttpStatus.UNAUTHORIZED, "{}"))
                .thenThrow(new RestClientException("unknown status"));
        when(payoutAccounts.get(anyString())).thenThrow(new ResourceAccessException("Read timed out"));

        for (int attempt = 0; attempt < 4; attempt++) {
            assertThatThrownBy(() -> service.set(USER, REQUEST)).isInstanceOf(PayoutAccountUnavailableException.class);
        }
        assertThatThrownBy(() -> service.get(USER)).isInstanceOf(PayoutAccountUnavailableException.class);

        assertThat(output.getAll()).contains("bank account request").doesNotContain(USER).doesNotContain("btok_1");
    }
}
