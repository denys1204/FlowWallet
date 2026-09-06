package com.flowwallet.wallet.api;

import com.flowwallet.wallet.balance.BalanceHistory;
import com.flowwallet.wallet.balance.BalanceHistoryRepository;
import com.flowwallet.wallet.balance.Wallet;
import com.flowwallet.wallet.balance.WalletRepository;
import com.flowwallet.wallet.dto.HistoryPage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mapstruct.factory.Mappers;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Limit;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class WalletServiceTest {
    private final WalletRepository wallets = mock(WalletRepository.class);
    private final BalanceHistoryRepository movements = mock(BalanceHistoryRepository.class);
    private final WalletService service =
            new WalletService(wallets, movements, Mappers.getMapper(WalletMapper.class));

    @ParameterizedTest(name = "{0} resolves to the USD wallet")
    @ValueSource(strings = {"USD", "usd", "Usd"})
    void currencyIsUpperCasedBeforeItIsLookedUp(String written) {
        // Currency.getInstance is case-sensitive, so validating before normalising would answer a casing
        // mistake with "not a valid currency code". The column also carries a CHECK that it equals its own
        // upper-case, so a lower-case lookup would otherwise miss a wallet that exists.
        when(wallets.findByUserIdAndCurrency("erin", "USD")).thenReturn(Optional.of(Wallet.open("erin", "USD")));

        assertThat(service.read("erin", written).currency()).isEqualTo("USD");
    }

    @ParameterizedTest(name = "{0} is refused as a currency")
    @ValueSource(strings = {"ZZZ", "US", "dollars", "1"})
    void anythingThatIsNotAnIsoCodeIsRefused(String written) {
        assertThatThrownBy(() -> service.read("erin", written))
                .isInstanceOf(InvalidCurrencyException.class);

        verifyNoInteractions(wallets);
    }

    @Test
    void aWalletTheCallerDoesNotHoldIsNotFound() {
        // The only query the service can issue is scoped to the caller, so "not yours" and "does not exist"
        // are one result. Answering 403 would need a deliberately wider read, and would tell a stranger that
        // someone else's wallet exists.
        when(wallets.findByUserIdAndCurrency("frank", "USD")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.read("frank", "USD"))
                .isInstanceOf(WalletNotFoundException.class);
    }

    @Test
    void aSecondWalletInOneCurrencyIsRefusedByTheConstraintRatherThanByAPriorCheck() {
        // Two concurrent first requests would both pass a check-then-insert and one would still fail on the
        // insert, so the check would buy nothing and hide what actually decides.
        when(wallets.saveAndFlush(any())).thenThrow(new DataIntegrityViolationException("user_id, currency"));

        assertThatThrownBy(() -> service.open("erin", "USD"))
                .isInstanceOf(WalletAlreadyExistsException.class);

        verify(wallets, never()).findByUserIdAndCurrency(any(), any());
    }

    @Test
    void historyAsksForOneMoreThanItNeedsToLearnWhetherAnOlderPageExists() {
        when(wallets.findByUserIdAndCurrency("erin", "USD")).thenReturn(Optional.of(Wallet.open("erin", "USD")));
        when(movements.findPageBefore(any(), any(), any())).thenReturn(movements(5));

        HistoryPage page = service.history("erin", "USD", null, 4);

        verify(movements).findPageBefore(any(), eq(null), eq(Limit.of(5)));
        assertThat(page.items()).hasSize(4);
        assertThat(page.nextBefore()).isEqualTo(4L);
    }

    @Test
    void theLastPageReportsNoCursor() {
        when(wallets.findByUserIdAndCurrency("erin", "USD")).thenReturn(Optional.of(Wallet.open("erin", "USD")));
        when(movements.findPageBefore(any(), any(), any())).thenReturn(movements(3));

        HistoryPage page = service.history("erin", "USD", null, 4);

        assertThat(page.items()).hasSize(3);
        assertThat(page.nextBefore()).isNull();
    }

    private List<BalanceHistory> movements(int count) {
        return IntStream.rangeClosed(1, count)
                .mapToObj(i -> BalanceHistory.builder()
                        .id((long) i)
                        .walletId(1L)
                        .transactionReference("ref-" + i)
                        .amount(new BigDecimal("10.00"))
                        .balanceBefore(BigDecimal.ZERO)
                        .balanceAfter(new BigDecimal("10.00"))
                        .build())
                .toList();
    }
}
