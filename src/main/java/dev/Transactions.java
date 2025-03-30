package dev;

import java.util.Objects;

public class Transactions {

    private String owner;
    private int amount;
    private String recipient;

    public Transactions(String owner, int amount, String recipient) {
        this.owner = owner;
        this.amount = amount;
        this.recipient = recipient;
    }

    public String getOwner() {
        return owner;
    }

    public int getAmount() {
        return amount;
    }

    public String getRecipient() {
        return recipient;
    }

    @Override
    public boolean equals(Object o) {
        if (o == null || getClass() != o.getClass()) return false;
        Transactions that = (Transactions) o;
        return amount == that.amount && Objects.equals(owner, that.owner) && Objects.equals(recipient, that.recipient);
    }

    @Override
    public int hashCode() {
        return Objects.hash(owner, amount, recipient);
    }

    @Override
    public String toString(){
        return owner + " send " + amount + " to " + recipient;
    }
}
