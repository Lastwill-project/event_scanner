package io.lastwill.eventscan.services.handlers;

import io.lastwill.eventscan.events.model.*;
import io.lastwill.eventscan.events.model.contract.*;
import io.lastwill.eventscan.events.model.contract.crowdsale.FinalizedEvent;
import io.lastwill.eventscan.events.model.contract.crowdsale.TimesChangedEvent;
import io.lastwill.eventscan.events.model.contract.crowdsale.WhitelistedAddressAddedEvent;
import io.lastwill.eventscan.events.model.contract.crowdsale.WhitelistedAddressRemovedEvent;
import io.lastwill.eventscan.events.model.contract.erc20.ApprovalEvent;
import io.lastwill.eventscan.events.model.contract.erc20.TransferEvent;
import io.lastwill.eventscan.events.model.contract.investmentPool.*;
import io.lastwill.eventscan.events.model.contract.swaps.CancelEvent;
import io.lastwill.eventscan.events.model.contract.swaps.DepositSwapEvent;
import io.lastwill.eventscan.events.model.contract.swaps.RefundSwapEvent;
import io.lastwill.eventscan.events.model.contract.swaps.SwapEvent;
import io.lastwill.eventscan.events.model.contract.tokenProtector.SelfdestructionEvent;
import io.lastwill.eventscan.events.model.contract.tokenProtector.TokensToSaveEvent;
import io.lastwill.eventscan.events.model.contract.tokenProtector.TransactionInfoEvent;
import io.lastwill.eventscan.messages.*;
import io.lastwill.eventscan.messages.swaps.DepositSwapNotify;
import io.lastwill.eventscan.messages.swaps.RefundSwapNotify;
import io.lastwill.eventscan.model.*;
import io.lastwill.eventscan.repositories.ProductRepository;
import io.lastwill.eventscan.services.BalanceProvider;
import io.lastwill.eventscan.services.ExternalNotifier;
import io.lastwill.eventscan.services.handlers.events.TransferOwnershipHandler;
import io.mywish.blockchain.ContractEvent;
import io.mywish.blockchain.ContractEventDefinition;
import io.mywish.scanner.services.EventPublisher;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.stream.Collectors;

@Slf4j
@Component
@ConditionalOnBean(ExternalNotifier.class)
public class ContractEventHandler {

    @Autowired
    private EventPublisher eventPublisher;

    @Autowired
    private ExternalNotifier externalNotifier;

    @Autowired
    private ProductRepository productRepository;

    @Autowired
    private BalanceProvider balanceProvider;

    @Autowired
    private TransferOwnershipHandler transferOwnershipHandler;

    @EventListener
    private void eventsHandler(final ContractEventsEvent event) {
        // catch airdrop events
        Product product = event.getContract().getProduct();
        if (product instanceof ProductAirdrop || product instanceof ProductAirdropTron) {
            List<AirdropEntry> airdropAddresses = event.getEvents()
                    .stream()
                    .filter(contractEvent -> contractEvent instanceof TransferEvent)
                    .map(contractEvent -> (TransferEvent) contractEvent)
                    .map(transferEvent -> new AirdropEntry(transferEvent.getTo(), transferEvent.getTokens()))
                    .collect(Collectors.toList());

            externalNotifier.send(event.getNetworkType(),
                    new AirdropNotify(
                            event.getContract().getId(),
                            PaymentStatus.COMMITTED,
                            event.getTransaction().getHash(),
                            airdropAddresses
                    )
            );
            return;
        }
        if (product instanceof ProductTokenProtector) {
            for (ContractEvent contractEvent : event.getEvents()) {
                if (contractEvent instanceof ApprovalEvent) {
                    eventPublisher.publish(
                            new TokenProtectorApproveEvent(event.getNetworkType(), event.getTransaction(),
                                    (ApprovalEvent) contractEvent, event.getContract().getId()));
                    externalNotifier.send(
                            event.getNetworkType(),
                            new ApproveTokenProtectorNotify(
                                    event.getContract().getId(),
                                    PaymentStatus.COMMITTED,
                                    event.getTransaction().getHash(),
                                    (ApprovalEvent) contractEvent
                            )
                    );
                }
            }
        }
        for (ContractEvent contractEvent : event.getEvents()) {
            // skip event if event.address != contract.address (it might be when internal transaction occurs)
            if (!contractEvent.getAddress().equalsIgnoreCase(event.getContract().getAddress())) {
                log.warn("There is skipped internal transaction event to address {} with name {}.", contractEvent.getAddress(), contractEvent.getEventName());
                continue;
            }

            if (contractEvent instanceof CheckedEvent) {
                externalNotifier.send(event.getNetworkType(),
                        new CheckedNotify(event.getContract().getId(), event.getTransaction().getHash()));
            }
            else if (contractEvent instanceof NeedRepeatCheckEvent) {
                externalNotifier.send(event.getNetworkType(),
                        new RepeatCheckNotify(event.getContract().getId(), event.getTransaction().getHash()));
            }
            else if (contractEvent instanceof KilledEvent) {
                externalNotifier.send(event.getNetworkType(),
                        new ContractKilledNotify(event.getContract().getId(), event.getTransaction().getHash()));
            }
            else if (contractEvent instanceof TriggeredEvent) {
                externalNotifier.send(event.getNetworkType(),
                        new ContractTriggeredNotify(event.getContract().getId(), event.getTransaction().getHash()));
            }
            else if (contractEvent instanceof FundsAddedEvent) {
                FundsAddedEvent fundsAddedEvent = (FundsAddedEvent) contractEvent;
                balanceProvider.getBalanceAsync(
                        event.getNetworkType(),
                        event.getContract().getAddress(),
                        event.getBlock().getNumber()
                )
                        .thenAccept(balance -> {
                            try {
                                externalNotifier.send(event.getNetworkType(), new FundsAddedNotify(
                                        event.getContract().getId(),
                                        event.getTransaction().getHash(),
                                        fundsAddedEvent.getAmount(),
                                        balance
                                ));
                            }
                            catch (Exception e) {
                                log.error("Sending notification failed.", e);
                            }

                            log.debug("Update balance in db for contract {} to {}.", event.getContract().getId(), balance);
                            try {
                                productRepository.updateBalance(event.getContract().getProduct().getId(), balance);
                            }
                            catch (Throwable e) {
                                log.error("Updating balance for contract {} failed.", event.getContract().getId(), e);
                            }
                        })
                        .exceptionally(throwable -> {
                            log.error("Getting balance for handling FundsAddedEvent failed.", throwable);
                            return null;
                        });
            }
            else if (contractEvent instanceof InvestEvent && event.getContract().getProduct() instanceof ProductInvestmentPool) {
                balanceProvider.getBalanceAsync(
                        event.getNetworkType(),
                        event.getContract().getAddress(),
                        event.getBlock().getNumber()
                )
                        .thenAccept(balance -> externalNotifier.send(event.getNetworkType(),
                                new ExFundsAddedNotify(
                                        event.getContract().getId(),
                                        event.getTransaction().getHash(),
                                        ((InvestEvent) contractEvent).getAmount(),
                                        balance,
                                        ((InvestEvent) contractEvent).getInvestorAddress()
                                )
                        ))
                        .exceptionally(throwable -> {
                            log.error("Getting balance for handling InvestEvent failed.", throwable);
                            return null;
                        });



            }
            else if (contractEvent instanceof WithdrawTokensEvent && event.getContract().getProduct() instanceof ProductInvestmentPool) {
                externalNotifier.send(event.getNetworkType(),
                        new TokensSentNotify(
                                event.getContract().getId(),
                                event.getTransaction().getHash(),
                                ((WithdrawTokensEvent) contractEvent).getInvestorAddress(),
                                ((WithdrawTokensEvent) contractEvent).getAmount()
                        ));
            }
            else if (contractEvent instanceof WithdrawRewardEvent && event.getContract().getProduct() instanceof ProductInvestmentPool) {
                externalNotifier.send(event.getNetworkType(),
                        new TokensSentNotify(
                                event.getContract().getId(),
                                event.getTransaction().getHash(),
                                ((WithdrawRewardEvent) contractEvent).getAdminAddress(),
                                ((WithdrawRewardEvent) contractEvent).getAmount()
                        ));
            }
            else if (contractEvent instanceof SetInvestmentAddressEvent && event.getContract().getProduct() instanceof ProductInvestmentPool) {
                externalNotifier.send(event.getNetworkType(),
                        new InvestmentPoolSetupNotify(
                                event.getContract().getId(),
                                event.getTransaction().getHash(),
                                ((SetInvestmentAddressEvent) contractEvent).getInvestmentAddress(),
                                null
                        ));
            }
            else if (contractEvent instanceof SetTokenAddressEvent && event.getContract().getProduct() instanceof ProductInvestmentPool) {
                externalNotifier.send(event.getNetworkType(),
                        new InvestmentPoolSetupNotify(
                                event.getContract().getId(),
                                event.getTransaction().getHash(),
                                null,
                                ((SetTokenAddressEvent) contractEvent).getTokenAddress()
                        ));
            }
            else if (contractEvent instanceof InitializedEvent) {
                externalNotifier.send(event.getNetworkType(),
                        new InitializedNotify(event.getContract().getId(), event.getTransaction().getHash()));
            }
            else if (contractEvent instanceof OwnershipTransferredEvent) {
                transferOwnershipHandler.handle(event.getNetworkType(), (OwnershipTransferredEvent) contractEvent, event.getTransactionReceipt());
            }
            else if (contractEvent instanceof FinalizedEvent || contractEvent instanceof MintFinishedEvent) {
                externalNotifier.send(event.getNetworkType(),
                        new FinalizedNotify(event.getContract().getId(), event.getTransaction().getHash()));
            }
            else if (contractEvent instanceof NotifiedEvent) {
                externalNotifier.send(event.getNetworkType(),
                        new NotifiedNotify(event.getContract().getId(), event.getTransaction().getHash()));
            }
            else if (contractEvent instanceof TimesChangedEvent) {
                externalNotifier.send(
                        event.getNetworkType(),
                        new TimesChangedNotify(
                                event.getContract().getId(),
                                event.getTransaction().getHash(),
                                ((TimesChangedEvent) contractEvent).getStartTime(),
                                ((TimesChangedEvent) contractEvent).getEndTime()
                        )
                );
            }
            else if (contractEvent instanceof WhitelistedAddressAddedEvent) {
                externalNotifier.send(
                        event.getNetworkType(),
                        new WhitelistAddedNotify(
                                event.getContract().getId(),
                                event.getTransaction().getHash(),
                                ((WhitelistedAddressAddedEvent) contractEvent).getWhitelistedAddress()
                        )
                );
            }
            else if (contractEvent instanceof WhitelistedAddressRemovedEvent) {
                externalNotifier.send(
                        event.getNetworkType(),
                        new WhitelistRemovedNotify(
                                event.getContract().getId(),
                                event.getTransaction().getHash(),
                                ((WhitelistedAddressRemovedEvent) contractEvent).getWhitelistedAddress()
                        )
                );
            }
            else if (contractEvent instanceof CancelledEvent) {
                externalNotifier.send(
                        event.getNetworkType(),
                        new CancelledNotify(
                                event.getContract().getId(),
                                PaymentStatus.COMMITTED,
                                event.getTransaction().getHash()
                        )
                );
            }
            else if (contractEvent instanceof ClaimRefundEvent) {
                externalNotifier.send(
                        event.getNetworkType(),
                        new RefundNotify(
                                event.getContract().getId(),
                                PaymentStatus.COMMITTED,
                                event.getTransaction().getHash(),
                                ((ClaimRefundEvent) contractEvent).getAmount()
                        )
                );
            } else if (contractEvent instanceof SwapEvent && product instanceof ProductSwaps) {
                eventPublisher.publish(new SwapCompletedEvent(event.getNetworkType(), event.getTransaction(),
                        event.getContract().getId(), product.getId()));
                externalNotifier.send(
                        event.getNetworkType(),
                        new FinalizedNotify(
                                event.getContract().getId(),
                                PaymentStatus.COMMITTED,
                                event.getTransaction().getHash()
                        )
                );
            } else if (contractEvent instanceof CancelEvent && product instanceof ProductSwaps) {
                eventPublisher.publish(new SwapCancelEvent(event.getNetworkType(), event.getTransaction(),
                        event.getContract().getId(), product.getId()));
                externalNotifier.send(
                        event.getNetworkType(),
                        new CancelledNotify(
                                event.getContract().getId(),
                                PaymentStatus.COMMITTED,
                                event.getTransaction().getHash()
                        )
                );
            } else if (contractEvent instanceof DepositSwapEvent) {
                eventPublisher.publish(new SwapDepositEvent(event.getNetworkType(), event.getTransaction(), (DepositSwapEvent) contractEvent));
                externalNotifier.send(
                        event.getNetworkType(),
                        new DepositSwapNotify(
                                event.getContract().getId(),
                                PaymentStatus.COMMITTED,
                                event.getTransaction().getHash(),
                                (DepositSwapEvent) contractEvent
                        )
                );
            } else if (contractEvent instanceof RefundSwapEvent) {
                eventPublisher.publish(new SwapRefundEvent(event.getNetworkType(), event.getTransaction(), (RefundSwapEvent) contractEvent));
                externalNotifier.send(
                        event.getNetworkType(),
                        new RefundSwapNotify(
                                event.getContract().getId(),
                                PaymentStatus.COMMITTED,
                                event.getTransaction().getHash(),
                                (RefundSwapEvent) contractEvent
                        )
                );
            } else if (contractEvent instanceof TokensToSaveEvent) {
                externalNotifier.send(
                        event.getNetworkType(),
                        new TokensToSaveNotify(
                                event.getContract().getId(),
                                PaymentStatus.COMMITTED,
                                event.getTransaction().getHash(),
                                (TokensToSaveEvent) contractEvent
                        )
                );
            } else if (contractEvent instanceof TransactionInfoEvent) {
                externalNotifier.send(
                        event.getNetworkType(),
                        new TransactionInfoNotify(
                                event.getContract().getId(),
                                PaymentStatus.COMMITTED,
                                event.getTransaction().getHash(),
                                (TransactionInfoEvent) contractEvent
                        )
                );
            } else if (contractEvent instanceof SelfdestructionEvent) {
                externalNotifier.send(
                        event.getNetworkType(),
                        new SelfdestructionNotify(
                                event.getContract().getId(),
                                PaymentStatus.COMMITTED,
                                event.getTransaction().getHash(),
                                (SelfdestructionEvent) contractEvent
                        )
                );
            } else if (contractEvent instanceof TransactionConfirmationEvent) {
                externalNotifier.send(
                        event.getNetworkType(),
                        new BlocksConfirmedNotify(
                                event.getContract().getId(),
                                PaymentStatus.COMMITTED,
                                event.getTransaction().getHash(),
                                event.getBlocksConfirmed(),
                                (TransactionConfirmationEvent) contractEvent
                        )
                );
            }
        }
    }
}
