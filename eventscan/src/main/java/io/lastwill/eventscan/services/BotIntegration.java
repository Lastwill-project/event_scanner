package io.lastwill.eventscan.services;

import io.lastwill.eventscan.events.NewBlockEvent;
import io.mywish.bot.service.MyWishBot;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.telegram.telegrambots.exceptions.TelegramApiRequestException;
import org.web3j.protocol.core.methods.response.Transaction;

import javax.annotation.PostConstruct;
import java.util.Collections;
import java.util.List;

@Component
@ConditionalOnBean(MyWishBot.class)
public class BotIntegration {
    @Autowired
    private MyWishBot bot;

    @Value("${io.lastwill.eventscan.contract.crowdsale-address}")
    private String crowdsaleAddress;

    @EventListener
    public void onNewBlock(NewBlockEvent newBlockEvent) {
        List<Transaction> transactions = newBlockEvent.getTransactionsByAddress().getOrDefault(crowdsaleAddress, Collections.emptyList());

        for (Transaction transaction: transactions) {
            bot.onInvestment(transaction.getFrom(), transaction.getValue());
        }
    }
}