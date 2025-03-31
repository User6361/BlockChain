package dev.ParseJson;

public class TransactionsConfigurat {
    private String ownerHash;
    private String recipient;
    private int amount;

    public TransactionsConfigurat(String ownerHash, String recipient, int amount){
        this.amount = amount;
        this.ownerHash = ownerHash;
        this.recipient = recipient;
    }
}