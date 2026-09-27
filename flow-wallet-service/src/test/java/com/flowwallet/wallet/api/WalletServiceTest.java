package com.flowwallet.wallet.api;

import com.flowwallet.wallet.balance.BalanceHistory;
import com.flowwallet.wallet.balance.BalanceHistoryRepository;
import com.flowwallet.wallet.balance.Wallet;
import com.flowwallet.wallet.balance.WalletRepository;
import com.flowwallet.wallet.dto.BalanceHistoryResponse;
import com.flowwallet.wallet.dto.HistoryPage;
import com.flowwallet.wallet.enums.TransactionType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mapstruct.factory.Mappers;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Limit;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.stream.IntStream;
import java.util.stream.LongStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
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
        // Guards a count query or an extra round trip to find the end, and a cursor taken from the extra row,
        // which would make the next page skip the movement just below this one.
        when(wallets.findByUserIdAndCurrency("erin", "USD")).thenReturn(Optional.of(Wallet.open("erin", "USD")));
        when(movements.findNewest(any(), any())).thenReturn(movements(9, 5));

        HistoryPage page = service.history("erin", "USD", null, 4);

        verify(movements).findNewest(any(), eq(Limit.of(5)));
        assertThat(page.items()).extracting(BalanceHistoryResponse::entryNo).containsExactly(9L, 8L, 7L, 6L);
        assertThat(page.nextBefore()).isEqualTo(6L);
    }

    @Test
    void aLaterPageStartsBelowTheCursorItWasGiven() {
        // Guards the cursor being dropped, which would serve the first page again, and a later page going
        // through the query with no bound.
        when(wallets.findByUserIdAndCurrency("erin", "USD")).thenReturn(Optional.of(Wallet.open("erin", "USD")));
        when(movements.findPageBefore(any(), anyLong(), any())).thenReturn(movements(5, 3));

        HistoryPage page = service.history("erin", "USD", 6L, 4);

        verify(movements).findPageBefore(any(), eq(6L), eq(Limit.of(5)));
        verify(movements, never()).findNewest(any(), any());
        assertThat(page.items()).extracting(BalanceHistoryResponse::entryNo).containsExactly(5L, 4L, 3L);
        assertThat(page.nextBefore()).isNull();
    }

    @Test
    void theLastPageReportsNoCursor() {
        when(wallets.findByUserIdAndCurrency("erin", "USD")).thenReturn(Optional.of(Wallet.open("erin", "USD")));
        when(movements.findNewest(any(), any())).thenReturn(movements(3, 3));

        HistoryPage page = service.history("erin", "USD", null, 4);

        assertThat(page.items()).hasSize(3);
        assertThat(page.nextBefore()).isNull();
    }

    @Test
    void pagingByEntryNumberNeitherSkipsNorRepeatsAMovementWhileCreditsArrive() {
        // Guards the cursor being taken from the row id. With two instances each holding a block of pooled ids,
        // a newer movement can carry a lower id, so a cursor on ids would skip or repeat movements. The ids here
        // run against the entry numbers on purpose. Credits land between the page reads, at the newest end, and
        // must not shift what the older pages hold. The repository stub applies the query contract: at most
        // limit rows below the bound, newest first by entry number.
        List<BalanceHistory> ledger = new ArrayList<>();
        IntStream.rangeClosed(1, 10).forEach(n -> ledger.add(movement(n, 1_000L - 49L * n)));
        when(wallets.findByUserIdAndCurrency("erin", "USD")).thenReturn(Optional.of(Wallet.open("erin", "USD")));
        when(movements.findNewest(any(), any())).thenAnswer(call -> below(ledger, Long.MAX_VALUE, call.getArgument(1)));
        when(movements.findPageBefore(any(), anyLong(), any()))
                .thenAnswer(call -> below(ledger, call.getArgument(1), call.getArgument(2)));

        List<Long> seen = new ArrayList<>();
        HistoryPage page = service.history("erin", "USD", null, 3);
        seen.addAll(page.items().stream().map(BalanceHistoryResponse::entryNo).toList());
        while (page.nextBefore() != null) {
            ledger.add(movement(ledger.size() + 1, 1L + ledger.size()));
            page = service.history("erin", "USD", page.nextBefore(), 3);
            seen.addAll(page.items().stream().map(BalanceHistoryResponse::entryNo).toList());
        }

        assertThat(seen).containsExactly(10L, 9L, 8L, 7L, 6L, 5L, 4L, 3L, 2L, 1L);
    }

    @Test
    void historyShowsWhoIsOnTheOtherSideOfATransfer() {
        // The build sets MapStruct's unmappedTargetPolicy to IGNORE, so a rename on either side would silently
        // drop the counterparty from every history item instead of failing the build. This uses the real
        // mapper, as the rest of the class does. A deposit has no user on the other side and must show null.
        Wallet erin = Wallet.builder()
                .id(1L)
                .userId("erin")
                .currency("USD")
                .balance(new BigDecimal("85"))
                .build();
        when(wallets.findByUserIdAndCurrency("erin", "USD")).thenReturn(Optional.of(erin));
        when(movements.findNewest(any(), any())).thenReturn(List.of(
                BalanceHistory.transferIn(erin, "ref-3", "gina", new BigDecimal("10"), new BigDecimal("75")),
                BalanceHistory.transferOut(erin, "ref-2", "frank", new BigDecimal("25"), new BigDecimal("100")),
                BalanceHistory.deposit(erin, "ref-1", "evt-1", new BigDecimal("100"), BigDecimal.ZERO)
        ));

        HistoryPage page = service.history("erin", "USD", null, 20);

        assertThat(page.items())
                .extracting(BalanceHistoryResponse::type, BalanceHistoryResponse::counterpartyUserId)
                .containsExactly(
                        tuple("TRANSFER_IN", "gina"),
                        tuple("TRANSFER_OUT", "frank"),
                        tuple("DEPOSIT", null)
                );
    }

    /**
     * {@code count} movements, newest first, starting at entry number {@code newest}.
     */
    private List<BalanceHistory> movements(long newest, int count) {
        return LongStream.range(0, count)
                .mapToObj(i -> movement(newest - i, newest - i))
                .toList();
    }

    private static BalanceHistory movement(long entryNo, long id) {
        return BalanceHistory.builder()
                .id(id)
                .walletId(1L)
                .entryNo(entryNo)
                .transactionReference("ref-" + entryNo)
                .type(TransactionType.DEPOSIT)
                .amount(new BigDecimal("10.00"))
                .balanceBefore(BigDecimal.ZERO)
                .balanceAfter(new BigDecimal("10.00"))
                .build();
    }

    private static List<BalanceHistory> below(List<BalanceHistory> ledger, long before, Limit limit) {
        return ledger.stream()
                .filter(h -> h.getEntryNo() < before)
                .sorted(Comparator.comparing(BalanceHistory::getEntryNo).reversed())
                .limit(limit.max())
                .toList();
    }
}
