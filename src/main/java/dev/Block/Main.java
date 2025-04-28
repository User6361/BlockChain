package dev.Block;



import java.util.Scanner;

import dev.Block.util.ConsoleLogger;

/**
 * Точка входа в приложение P2P Blockchain Peer.
 * Запрашивает у пользователя никнейм и порт, создает и запускает Peer.
 */
public class Main {

    public static void main(String[] args) {
        try (Scanner scanner = new Scanner(System.in)) {
            Thread.currentThread().setName("PeerMain-Setup"); // Имя потока для настройки

            String nickname = "";
            // Валидация никнейма
            while (nickname.isEmpty() || nickname.contains(":") || nickname.contains(" ") || nickname.equalsIgnoreCase("UNKNOWN")) {
                 System.out.print("Enter your nickname (no spaces or ':'): ");
                 nickname = scanner.nextLine().trim();
                 if (nickname.isEmpty() || nickname.contains(":") || nickname.contains(" ") || nickname.equalsIgnoreCase("UNKNOWN")) {
                     System.out.println("Invalid nickname.");
                     nickname = "";
                 }
            }

            int port = 0;
            // Валидация порта
            while (port <= 1024 || port > 65535) { // Используем порты выше 1024
                System.out.print("Enter the port number to listen on (1025-65535, e.g., 8080): ");
                try {
                    port = Integer.parseInt(scanner.nextLine().trim());
                    if (port <= 1024 || port > 65535) System.out.println("Port must be between 1025 and 65535.");
                } catch (NumberFormatException e) {
                    System.out.println("Invalid port number.");
                    port = 0; // Сброс для повторного ввода
                }
            }

            // Создаем и запускаем пир
            Peer peer = new Peer(nickname, port);

            // Добавляем Shutdown Hook для корректного завершения по Ctrl+C
            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                 ConsoleLogger.print("\nCtrl+C detected or JVM shutting down, initiating peer shutdown...");
                 peer.shutdown();
                 ConsoleLogger.print("Shutdown hook finished.");
             }, "ShutdownHook"));

            // Запускаем серверную часть и обработку ввода пользователя
            peer.startServer();
            peer.startUserInput(); // Этот метод будет работать до вызова /exit или shutdown
        }
        ConsoleLogger.print("Main setup thread finished. Peer is running.");
        // scanner здесь закрывается внутри startUserInput
    }
}