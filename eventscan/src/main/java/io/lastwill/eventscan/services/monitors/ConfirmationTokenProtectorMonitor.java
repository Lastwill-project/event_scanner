package io.lastwill.eventscan.services.monitors;

import io.lastwill.eventscan.events.model.ContractEventsEvent;
import io.lastwill.eventscan.events.model.ContractEventsEventWithConfirmation;
import io.lastwill.eventscan.events.model.ContractTransactionFailedEvent;
import io.lastwill.eventscan.events.model.contract.erc20.ApprovalEvent;
import io.lastwill.eventscan.model.Contract;
import io.lastwill.eventscan.model.EventConfirmation;
import io.lastwill.eventscan.model.NetworkType;
import io.lastwill.eventscan.model.ProductTokenProtector;
import io.lastwill.eventscan.repositories.ContractRepository;
import io.lastwill.eventscan.repositories.ProductRepository;
import io.lastwill.eventscan.services.EventConfirmationDbPersister;
import io.lastwill.eventscan.services.EventConfirmationPersister;
import io.lastwill.eventscan.services.TransactionProvider;
import io.mywish.blockchain.ContractEvent;
import io.mywish.blockchain.WrapperBlock;
import io.mywish.blockchain.WrapperTransaction;
import io.mywish.blockchain.WrapperTransactionReceipt;
import io.mywish.scanner.model.NewLastBlockEvent;
import io.mywish.scanner.services.EventPublisher;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.util.*;

@Slf4j
@Component
public class ConfirmationTokenProtectorMonitor {
    @Autowired
    private TransactionProvider transactionProvider;
    @Autowired
    private ContractRepository contractRepository;
    @Autowired
    private EventPublisher eventPublisher;
    @Autowired
    private ProductRepository productRepository;
    private List<ProductTokenProtector> tokenProtectorProducts;

    private Map<ProductTokenProtector, Contract> protectorByContract = new HashMap<>();

    private List <EventConfirmation> eventsToConfirm ;
    public Long confirmationNumber = Long.valueOf(5);
    EventConfirmationDbPersister eventConfirmationDbPersister = new EventConfirmationDbPersister();
    private Long blocksConfirmed;

    @EventListener
    private void onNewBlockEvent(final NewLastBlockEvent event) {
        if (event.getNetworkType() != NetworkType.ETHEREUM_MAINNET) {
            return;
        }

        Set<String> addresses = new HashSet<>(event.getTransactionsByAddress().keySet());
        if (addresses.isEmpty()) {
            return;
        }

        tokenProtectorProducts = productRepository.findProtectorsByOwner(addresses);
        if (tokenProtectorProducts.isEmpty()) {
            return;
        }
        for (ProductTokenProtector tokenProtectorProduct : tokenProtectorProducts) {
            protectorByContract.put(tokenProtectorProduct, contractRepository.findFirstById(tokenProtectorProduct.getEthContract()));
        }

        for (Map.Entry<ProductTokenProtector, Contract> product : protectorByContract.entrySet()) {
            if (product.getValue() == null || product.getValue().getAddress() == null) {
//                log.warn("Contract, or contract address is empty by Token Protector Product id {}", product.getKey().getId());
                continue;
            }
            final List<WrapperTransaction> transactions = event
                    .getTransactionsByAddress()
                    .get(product.getKey().getOwner().toLowerCase());
            if (transactions == null || transactions.isEmpty()) {
                log.warn("Transactions is empty by by Token Protector Product id = {}, blockHash = {}", product.getKey().getId(), event.getBlock().getHash());
                continue;
            }
            for (final WrapperTransaction transaction : transactions) {
                if (transaction.getOutputs().size() == 0) {
                    continue;
                }
                try {
                    WrapperTransactionReceipt transactionReceipt = transactionProvider.getTransactionReceipt(event.getNetworkType(), transaction);
                    List<ContractEvent> events = transactionReceipt.getLogs();
                    for (ContractEvent contractEvent : events) {
                        if (contractEvent.getDefinition().getName().equals("Approval")
                                && ((ApprovalEvent) contractEvent).getSpender().equals(product.getValue().getAddress().toLowerCase())) {
                            handleReceiptAndContract(event.getNetworkType(), product.getValue(), transaction, transactionReceipt, event.getBlock());
                        }
                    }
                } catch (Exception e) {
                    log.error("ContractEventsEvent handling cause exception.", e);
                }
            }
        }

        if (eventsToConfirm.isEmpty()) {
            return;
        }

        try  {
            for (EventConfirmation entry : eventConfirmationDbPersister.getAllByNetwork(NetworkType.ETHEREUM_MAINNET)) {
                if (event.getBlock().getNumber() - entry.getBlockNumber() >= confirmationNumber) {
                    //Достигнуто нужное количество подтверждений
                    eventsToConfirm.remove(entry.getHash());
                }
                // Отправить информацию по MQ
                eventPublisher.publish(
                        new ContractEventsEventWithConfirmation(
                                event.getNetworkType(), entry.getHash(), blocksConfirmed
                        )
                );

            }
        } catch ( Exception e){
            log.error("EventConfirmationDbPersister handling cause exception.", e);
        }

    }

    private void handleReceiptAndContract(
            final NetworkType networkType,
            final Contract contract,
            final WrapperTransaction transaction,
            final WrapperTransactionReceipt transactionReceipt,
            final WrapperBlock block
    ) {
        if (!transactionReceipt.isSuccess()) {
            eventPublisher.publish(new ContractTransactionFailedEvent(
                    networkType,
                    contract,
                    transaction,
                    transactionReceipt,
                    block
            ));
        }

        List<ContractEvent> events = transactionReceipt.getLogs();
        if (events.isEmpty()) {
            return;
        }

        eventPublisher.publish(
                new ContractEventsEvent(
                        networkType,
                        contract,
                        events,
                        transaction,
                        transactionReceipt,
                        block
                )
        );

        eventConfirmationDbPersister.tryAdd(transaction.getHash(), block.getNumber(),NetworkType.ETHEREUM_MAINNET);



    }
}
