package io.lastwill.eventscan.events.model;

import io.lastwill.eventscan.events.model.contract.swaps2.DepositEvent;
import io.lastwill.eventscan.model.NetworkType;
import io.lastwill.eventscan.model.Swaps2Order;
import io.mywish.blockchain.WrapperTransaction;
import lombok.Getter;

import java.math.BigInteger;

@Getter
public class Swaps2OrderDepositEvent extends BaseEvent {

    private final String token;
    private final String userAddress;
    private final BigInteger amount;
    private final BigInteger balance;
    private final Swaps2Order order;
    private final WrapperTransaction transaction;

    public Swaps2OrderDepositEvent(NetworkType networkType,
                                   Swaps2Order order,
                                   WrapperTransaction transaction,
                                   DepositEvent event
    ) {
        super(networkType);
        this.order = order;
        this.transaction = transaction;
        this.token = event.getToken();
        this.userAddress = event.getUser();
        this.amount = event.getAmount();
        this.balance = event.getBalance();
    }
}
