package dev.Block;

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
    private Block lastBlock;

    public Block(String previousHash, Transactions transactions, LocalDate timeStamp, Block lastBlock) {
        this.transactions = transactions;
        this.timeStamp = timeStamp;
        this.previousHash = previousHash;
        this.lastBlock = lastBlock;
        this.hashOfBlock = calculateHash(transactions, previousHash);

        //THIS IS'NT FIRST BLOCK
        if (lastBlock != null) {
            lastBlock.setHashOfBlock(calculateHash(transactions, previousHash + hashOfBlock));
        }
    }


    /*
     * 
     * Calculate HASH
     * 
     */

    private static String calculateHash(Transactions transactions, String previousHash) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            String input = transactions + previousHash;
            byte[] hash = md.digest(input.getBytes(StandardCharsets.UTF_8));
            return new BigInteger(1, hash).toString(16);
        } catch (NoSuchAlgorithmException e) {
            e.printStackTrace();
        }
        return null;
    }



    /*
     * To update all bloks hashes if one of this has new ( or just update ) the transaction
     * 
     * It takes new new transaction and the block 
     * 
     * then set in this block new transaction with new hash
     * 
     * then go all  other block until end and change the hashes of theme
     *
     */
    
    public static void updateTransactionInBlock(Transactions newTransaction, Block updatedBlock) {
       
        updatedBlock.setTransactions(newTransaction);
            
        updatedBlock.setHashOfBlock(calculateHash(updatedBlock.getTransactions(), updatedBlock.getPreviousHash()));

        Block currentBlock = updatedBlock;
        while (currentBlock.getLastBlock() != null) {
            currentBlock = currentBlock.getLastBlock();
            currentBlock.setHashOfBlock(calculateHash(currentBlock.getTransactions(), currentBlock.getPreviousHash()));
        }
    }
    

    public String getHashOfBlock() {
        return "The hash of this block is " + hashOfBlock;
    }


    public void setTransactions(Transactions transactions) {
        this.transactions = transactions;
    }

    public void setHashOfBlock(String hashOfBlock) {
        this.hashOfBlock = hashOfBlock;
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

    public Block getLastBlock() {
        return lastBlock;
    }
}