package io.lastwill.eventscan.model;

import lombok.Getter;

import javax.persistence.*;
import java.math.BigInteger;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;

@Entity
@Table(name = "contracts_contract")
@Getter
public class Contract {
    @Id
    private Integer id;
    private String address;
    private String ownerAddress;
    private String userAddress;
    @Column(nullable = false)
    @Enumerated(EnumType.STRING)
    private ContractState state;
    // TODO: add convertors
//    @Column(name = "activeTo", nullable = false)
//    private OffsetDateTime activeUntil;
    @Column(nullable = false)
    private int checkInterval;
    private BigInteger balance;
    @Column(nullable = false)
    private BigInteger cost;
}
