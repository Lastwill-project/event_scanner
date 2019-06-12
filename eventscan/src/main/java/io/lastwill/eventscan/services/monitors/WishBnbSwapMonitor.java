package io.lastwill.eventscan.services.monitors;

import com.binance.dex.api.client.BinanceDexApiNodeClient;
import com.binance.dex.api.client.BinanceDexConstants;
import com.binance.dex.api.client.Wallet;
import com.binance.dex.api.client.domain.Account;
import com.binance.dex.api.client.domain.Balance;
import com.binance.dex.api.client.domain.TransactionMetadata;
import com.binance.dex.api.client.domain.broadcast.TransactionOption;
import com.binance.dex.api.client.domain.broadcast.Transfer;
import io.lastwill.eventscan.events.model.contract.BnbWishPutEvent;
import io.lastwill.eventscan.events.model.contract.erc20.TransferEvent;
import io.lastwill.eventscan.events.model.wishbnbswap.LowBalanceEvent;
import io.lastwill.eventscan.events.model.wishbnbswap.TokensBurnedEvent;
import io.lastwill.eventscan.events.model.wishbnbswap.TokensTransferErrorEvent;
import io.lastwill.eventscan.events.model.wishbnbswap.TokensTransferredEvent;
import io.lastwill.eventscan.model.CryptoCurrency;
import io.lastwill.eventscan.model.NetworkType;
import io.lastwill.eventscan.model.WishToBnbLinkEntry;
import io.lastwill.eventscan.model.WishToBnbSwapEntry;
import io.lastwill.eventscan.repositories.WishToBnbLinkEntryRepository;
import io.lastwill.eventscan.repositories.WishToBnbSwapEntryRepository;
import io.lastwill.eventscan.services.TransactionProvider;
import io.mywish.scanner.model.NewBlockEvent;
import io.mywish.scanner.services.EventPublisher;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.security.NoSuchAlgorithmException;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Objects;

@Slf4j
@Component
public class WishBnbSwapMonitor {
    private static final BigInteger TRANSFER_FEE = BigInteger.valueOf(62500);

    @Autowired
    private EventPublisher eventPublisher;

    @Autowired
    private BinanceDexApiNodeClient binanceClient;

    @Autowired
    private TransactionProvider transactionProvider;

    @Autowired
    private WishToBnbLinkEntryRepository linkRepository;

    @Autowired
    private WishToBnbSwapEntryRepository swapRepository;

    @Autowired
    private Wallet binanceWallet;

    @Value("${io.lastwill.eventscan.binance.wish-swap.linker-address}")
    private String linkerAddress;

    @Value("${io.lastwill.eventscan.binance.wish-swap.burner-address}")
    private String burnerAddress;

    @Value("${io.lastwill.eventscan.contract.token-address.wish}")
    private String tokenAddressWish;

    @Value("${io.lastwill.eventscan.binance-wish.token-symbol}")
    private String bnbWishSymbol;

    @Value("${io.lastwill.eventscan.binance.token-symbol}")
    private String bnbSymbol;

    @Value("${io.lastwill.eventscan.binance.wish-swap.max-limit:#{null}}")
    private BigInteger wishMaxLimit;

    @EventListener
    public void onLink(final NewBlockEvent newBlockEvent) {
        if (newBlockEvent.getNetworkType() != NetworkType.ETHEREUM_MAINNET) {
            return;
        }

        newBlockEvent.getTransactionsByAddress()
                .entrySet()
                .stream()
                .filter(entry -> linkerAddress.equalsIgnoreCase(entry.getKey()))
                .map(Map.Entry::getValue)
                .flatMap(Collection::stream)
                .forEach(transaction -> transactionProvider.getTransactionReceiptAsync(newBlockEvent.getNetworkType(), transaction)
                        .thenAccept(receipt -> receipt.getLogs()
                                .stream()
                                .filter(event -> event instanceof BnbWishPutEvent)
                                .map(event -> (BnbWishPutEvent) event)
                                .map(putEvent -> {
                                    String eth = putEvent.getEth().toLowerCase();
                                    String bnb = putEvent.getBnb().toLowerCase();

                                    if (linkRepository.existsByEthAddress(eth)) {
                                        log.warn("\"{} : {}\" already linked.", eth, bnb);
                                        return null;
                                    }

                                    return new WishToBnbLinkEntry(eth, bnb);
                                })
                                .filter(Objects::nonNull)
                                .map(linkRepository::save)
                                .forEach(linkEntry -> log.info("Linked \"{} : {}\"", linkEntry.getEthAddress(), linkEntry.getBnbAddress()))
                        )
                );
    }

    @EventListener
    public void onBurn(final NewBlockEvent newBlockEvent) {
        if (newBlockEvent.getNetworkType() != NetworkType.ETHEREUM_MAINNET) {
            return;
        }

        newBlockEvent.getTransactionsByAddress()
                .entrySet()
                .stream()
                .filter(entry -> tokenAddressWish.equalsIgnoreCase(entry.getKey()))
                .map(Map.Entry::getValue)
                .flatMap(Collection::stream)
                .forEach(transaction -> transactionProvider.getTransactionReceiptAsync(newBlockEvent.getNetworkType(), transaction)
                        .thenAccept(receipt -> receipt.getLogs()
                                .stream()
                                .filter(event -> event instanceof TransferEvent)
                                .map(event -> (TransferEvent) event)
                                .filter(event -> burnerAddress.equalsIgnoreCase(event.getTo()))
                                .map(transferEvent -> {
                                    String ethAddress = transferEvent.getFrom().toLowerCase();
                                    BigInteger amount = convertEthWishToBnbWish(transferEvent.getTokens());
                                    String bnbAddress = null;

                                    WishToBnbLinkEntry linkEntry = linkRepository.findByEthAddress(ethAddress);
                                    if (linkEntry != null) {
                                        bnbAddress = linkEntry.getBnbAddress();
                                    } else {
                                        log.warn("\"{}\" not linked", ethAddress);
                                    }

                                    WishToBnbSwapEntry swapEntry = new WishToBnbSwapEntry(
                                            linkEntry,
                                            amount,
                                            transaction.getHash()
                                    );
                                    swapEntry = swapRepository.save(swapEntry);

                                    log.info("{} burned {} WISH", ethAddress, toString(amount));

                                    eventPublisher.publish(new TokensBurnedEvent(
                                            "WISH",
                                            CryptoCurrency.WISH.getDecimals(),
                                            swapEntry,
                                            ethAddress,
                                            bnbAddress
                                    ));

                                    return swapEntry;
                                })
                                .map(swapEntry -> {
                                    if (swapEntry.getLinkEntry() == null) {
                                        return null;
                                    }

                                    if (wishMaxLimit != null) {
                                        if (swapEntry.getAmount().compareTo(wishMaxLimit) >= 0) {
                                            return null;
                                        }
                                    }

                                    Account account = binanceClient.getAccount(binanceWallet.getAddress());
                                    BigInteger bnbBalance = getBalance(account, bnbSymbol);
                                    if (bnbBalance.compareTo(TRANSFER_FEE) < 0) {
                                        eventPublisher.publish(new LowBalanceEvent(
                                                bnbSymbol,
                                                CryptoCurrency.BBNB.getDecimals(),
                                                swapEntry,
                                                TRANSFER_FEE,
                                                bnbBalance
                                        ));
                                        return null;
                                    }

                                    BigInteger wishBalance = getBalance(account, bnbWishSymbol);
                                    if (wishBalance.compareTo(BigInteger.ZERO) == 0) {
                                        return null;
                                    }

                                    if (wishBalance.compareTo(swapEntry.getAmount()) < 0) {
                                        eventPublisher.publish(new LowBalanceEvent(
                                                bnbWishSymbol,
                                                CryptoCurrency.BWISH.getDecimals(),
                                                swapEntry,
                                                swapEntry.getAmount(),
                                                wishBalance
                                        ));
                                        return null;
                                    }

                                    return swapEntry;
                                })
                                .filter(Objects::nonNull)
                                .forEach(swapEntry -> {
                                    WishToBnbLinkEntry link = swapEntry.getLinkEntry();
                                    try {
                                        List<TransactionMetadata> results = transfer(
                                                link.getEthAddress(),
                                                link.getBnbAddress(),
                                                swapEntry.getAmount()
                                        );

                                        String txHash = results.get(0).getHash();
                                        swapEntry.setBnbTxHash(txHash);
                                        swapRepository.save(swapEntry);
                                        log.info("Bep-2 tokens transferred: {}", txHash);

                                        eventPublisher.publish(new TokensTransferredEvent(
                                                bnbWishSymbol,
                                                CryptoCurrency.BWISH.getDecimals(),
                                                swapEntry
                                        ));
                                    } catch (Exception e) {
                                        log.error("Error when transferring BEP-2 WISH.");

                                        eventPublisher.publish(new TokensTransferErrorEvent(
                                                bnbWishSymbol,
                                                CryptoCurrency.BWISH.getDecimals(),
                                                swapEntry
                                        ));
                                    }
                                })
                        )
                );
    }

    private List<TransactionMetadata> transfer(String ethAddress, String bnbAddress, BigInteger amount)
            throws IOException, NoSuchAlgorithmException {
        Transfer transferObject = buildTransfer(binanceWallet.getAddress(), bnbAddress, bnbWishSymbol, amount);
        TransactionOption transactionOption = buildTransactionOption(buildMemo(ethAddress));
        return binanceClient.transfer(transferObject, binanceWallet, transactionOption, false);
    }

    private String buildMemo(String ethAddress) {
        return "swap from " + ethAddress;
    }

    private TransactionOption buildTransactionOption(String memo) {
        return new TransactionOption(memo, BinanceDexConstants.BINANCE_DEX_API_CLIENT_JAVA_SOURCE, null);
    }

    private Transfer buildTransfer(String from, String to, String coin, BigInteger amount) {
        Transfer transfer = new Transfer();
        transfer.setFromAddress(from);
        transfer.setToAddress(to);
        transfer.setCoin(coin);
        transfer.setAmount(amount.toString());
        return transfer;
    }

    private String toString(BigInteger bnbWishAmount) {
        return new BigDecimal(bnbWishAmount, CryptoCurrency.BWISH.getDecimals()).toString();
    }

    private BigInteger convertEthWishToBnbWish(BigInteger amount) {
        int ethWishDecimals = CryptoCurrency.WISH.getDecimals();
        int bnbWishDecimals = CryptoCurrency.BWISH.getDecimals();

        return amount
                .multiply(BigInteger.TEN.pow(bnbWishDecimals))
                .divide(BigInteger.TEN.pow(ethWishDecimals));
    }

    private BigInteger getBalance(Account account, String coin) {
        return account.getBalances()
                .stream()
                .filter(balance -> Objects.equals(balance.getSymbol(), coin))
                .map(Balance::getFree)
                .map(BigInteger::new)
                .findFirst()
                .orElse(BigInteger.ZERO);
    }
}
