package dev;

import jdk.jfr.Description;

import javax.management.Descriptor;
import javax.xml.crypto.Data;
import java.math.BigInteger;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;

public class Main {

    public static final String FIRST_HASH = "00000000000000000000000000000000000000000000000000000000000000001";



    public ArrayList<Block> blocks = new ArrayList<>();

    public static void main(String[] args)  {

        LocalDate date = LocalDate.now();
        Transactions transactionsForBlock1 = new Transactions("Sasha", 101, "Misha");
        Transactions transactionsForBlock2 = new Transactions("Igor", 101, "Misha");
        Block block1 = new Block(FIRST_HASH, transactionsForBlock1, date);
        System.out.println(block1.getHashOfBlock());
        Block block2 = new Block(block1.getHashOfBlock(), transactionsForBlock2, date);
        System.out.println(block2.getHashOfBlock());

    }
}