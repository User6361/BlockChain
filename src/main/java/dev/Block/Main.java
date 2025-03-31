package dev.Block;

import java.time.LocalDate;
import java.util.ArrayList;



public class Main {

    public static final String FIRST_HASH = "0000000000000000000000000000000000000000000000000000000000000001";



    public ArrayList<Block> blocks = new ArrayList<>();

    public static void main(String[] args)  {

        LocalDate date = LocalDate.now();
        Transactions transactions1 = new Transactions("Igor", 112, "Misha");
        Block block1 = new Block(FIRST_HASH, transactions1, date, null);
    
    
        Transactions transactions2 = new Transactions("Igor", 10, "Misha");
        Block block2 = new Block(block1.getHashOfBlock(), transactions2, date, block1);
    
    
        Transactions transactions3 = new Transactions("Igor", 121, "Misha");
        Block block3 = new Block(block2.getHashOfBlock(), transactions3, date, block2);
    
        System.out.println("Updated hash of block 1: " + block1.getHashOfBlock());
        System.out.println("Updated hash of block 2: " + block2.getHashOfBlock());
        System.out.println("Updated hash of block 3: " + block3.getHashOfBlock());


        Transactions transactionsUpdate2 = new Transactions("Igor", 10, "Misha");
    
        Block.updateTransactionInBlock(transactionsUpdate2, block2);


        System.out.println("Была произведена замена транзацкии во 2 блоке");
    
        System.out.println("Updated hash of block 1: " + block1.getHashOfBlock());
        System.out.println("Updated hash of block 2: " + block2.getHashOfBlock());
        System.out.println("Updated hash of block 3: " + block3.getHashOfBlock());
        
    }
}