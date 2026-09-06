package com.flowwallet.wallet.api;

import com.flowwallet.wallet.balance.BalanceHistory;
import com.flowwallet.wallet.balance.Wallet;
import com.flowwallet.wallet.dto.BalanceHistoryResponse;
import com.flowwallet.wallet.dto.WalletResponse;
import org.mapstruct.Mapper;

import java.util.List;

@Mapper(componentModel = "spring")
public interface WalletMapper {

    WalletResponse toResponse(Wallet wallet);

    List<WalletResponse> toResponses(List<Wallet> wallets);

    BalanceHistoryResponse toResponse(BalanceHistory movement);

    List<BalanceHistoryResponse> toHistoryResponses(List<BalanceHistory> movements);
}
