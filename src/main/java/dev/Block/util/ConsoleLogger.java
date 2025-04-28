package dev.Block.util;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

public class ConsoleLogger {

    private static final DateTimeFormatter formatter = DateTimeFormatter.ofPattern("HH:mm:ss");

    /**
     * Выводит сообщение в консоль с временной меткой и именем потока.
     * @param entry Сообщение для вывода.
     */
    public static void print(String entry) {
        LocalDateTime now = LocalDateTime.now();
        String formattedNow = now.format(formatter);
        // Использование System.out.printf для выравнивания и потокобезопасности вывода
        System.out.printf("%s [%s] %s%n", formattedNow, Thread.currentThread().getName(), entry);
    }
}