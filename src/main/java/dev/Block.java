package dev;


import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDate;

public class Block {

    private Transactions transactions;
    private String previousHash;
    private LocalDate timeStamp;
    private String hashOfBlock;

    public Block(String previoushash, Transactions transactions, LocalDate timeStamp) {
        this.transactions = transactions;
        this.timeStamp = timeStamp;
        this.previousHash = previoushash;
        this.hashOfBlock = hash(transactions, previoushash);
    }

    private final static String hash(Transactions transactions, String previousHash) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            String input = transactions + previousHash;
            byte[] hash =md.digest( input.getBytes(StandardCharsets.UTF_8));
            return new BigInteger(1, hash).toString(16);
        } catch (NoSuchAlgorithmException e) {
            e.printStackTrace();
        }
        return null;
    }

    public String getHashOfBlock() {
        return "The hash of this block is " + hashOfBlock;

    }

    public String getPreviousHash() {
        return previousHash;
    }

    public Transactions getTransactions() {
        return transactions;
    }

    public LocalDate getTimeStamp() {
        return timeStamp;
    }
}