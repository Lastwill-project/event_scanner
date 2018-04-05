package io.lastwill.eventscan.services;

import io.lastwill.eventscan.events.ContractCreatedEvent;
import io.lastwill.eventscan.events.UserPaymentEvent;
import io.lastwill.eventscan.model.Contract;
import io.lastwill.eventscan.model.Product;
import io.lastwill.eventscan.model.ProductStatistics;
import io.lastwill.eventscan.model.UserProfile;
import io.mywish.bot.service.MyWishBot;
import io.mywish.scanner.model.NetworkType;
import io.mywish.scanner.model.NewBlockEvent;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.web3j.protocol.core.methods.response.Transaction;

import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Slf4j
@Component
@ConditionalOnBean(MyWishBot.class)
public class BotIntegration {
    @Autowired
    private MyWishBot bot;

    @Value("${io.lastwill.eventscan.contract.crowdsale-address}")
    private String crowdsaleAddress;

    private final Map<NetworkType, String> networkName = new HashMap<NetworkType, String>() {{
        put(NetworkType.ETHEREUM_MAINNET, "Eth");
        put(NetworkType.ETHEREUM_ROPSTEN, "tEth");
    }};

    private final String defaultNetwork = "unknown";

    private final Map<NetworkType, String> etherscanHosts = new HashMap<NetworkType, String>() {{
        put(NetworkType.ETHEREUM_MAINNET, "etherscan.io");
        put(NetworkType.ETHEREUM_ROPSTEN, "ropsten.etherscan.io");
    }};

    private final String defaultEtherscan = "etherscan.io";

    @EventListener
    public void onNewBlock(final NewBlockEvent newBlockEvent) {
        List<Transaction> transactions = newBlockEvent.getTransactionsByAddress().getOrDefault(crowdsaleAddress, Collections.emptyList());

        if (!transactions.isEmpty()) {
            log.info("Transactions {} from crowdsale {} detected in block {}.", transactions.size(), crowdsaleAddress, newBlockEvent.getBlock().getNumber());
        }
        for (Transaction transaction: transactions) {
            bot.onInvestment(transaction.getFrom(), transaction.getValue());
        }
    }

    @EventListener
    public void onContractCrated(final ContractCreatedEvent contractCreatedEvent) {
        final Contract contract = contractCreatedEvent.getContract();
        final Product product = contract.getProduct();
        final String type = ProductStatistics.PRODUCT_TYPES.get(product.getContractType());
        final String network = networkName.getOrDefault(product.getNetwork().getType(), defaultNetwork);
        final String etherscan = etherscanHosts.getOrDefault(product.getNetwork().getType(), defaultEtherscan);
        if (contractCreatedEvent.isSuccess()) {
            bot.onContract(network, product.getId(), type, contract.getId(), product.getCost(), contract.getAddress(), etherscan);
        }
        else {
            bot.onContractFailed(network, product.getId(), type, contract.getId(), contractCreatedEvent.getTransaction().getHash(), etherscan);
        }
    }

    @EventListener
    public void onOwnerBalanceChanged(final UserPaymentEvent event) {
        final UserProfile userProfile = event.getUserProfile();
        final String network = networkName.getOrDefault(event.getNetworkType(), defaultNetwork);
        final String etherscan = etherscanHosts.getOrDefault(event.getNetworkType(), defaultEtherscan);
        bot.onBalance(
                network,
                userProfile.getUser().getId(),
                event.getAmount(),
                event.getCurrency().toString(),
                event.getTransaction().getHash(),
                etherscan
        );
    }
}
