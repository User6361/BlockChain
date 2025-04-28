package dev.Block;


import java.io.*;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import java.util.Scanner;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.stream.Collectors;

import dev.Block.network.PeerConnectionHandler;
import dev.Block.util.ConsoleLogger;

/**
 * Основной класс P2P узла (пира) в сети.
 * Управляет серверным сокетом, исходящими соединениями,
 * балансами, реестром транзакций и обработкой пользовательского ввода.
 */
public class Peer {

    private final String nickname;
    private final int port;
    private ServerSocket serverSocket;
    private final List<PeerConnectionHandler> connections = new CopyOnWriteArrayList<>();
    private final ExecutorService connectionExecutor = Executors.newCachedThreadPool();
    private volatile boolean running = true;

    // Blockchain state
    private final Map<String, Integer> balances = new ConcurrentHashMap<>();
    private final List<String> transactionLedger = new CopyOnWriteArrayList<>();
    private static final int INITIAL_BALANCE = 100;

    // Message Protocol Prefixes (сделаем их public static, чтобы Handler их видел)
    public static final String MSG_PREFIX_CHAT = "CHAT:";
    public static final String MSG_PREFIX_TXN = "TXN:";
    public static final String MSG_PREFIX_REQ_SYNC = "REQ_SYNC:";
    public static final String MSG_PREFIX_SYNC_BAL_START = "SYNC_BAL_START:";
    public static final String MSG_PREFIX_SYNC_BAL_ENTRY = "SYNC_BAL:";
    public static final String MSG_PREFIX_SYNC_BAL_END = "SYNC_BAL_END:";
    public static final String MSG_PREFIX_SYNC_LED_START = "SYNC_LED_START:";
    public static final String MSG_PREFIX_SYNC_LED_ENTRY = "SYNC_LED:";
    public static final String MSG_PREFIX_SYNC_LED_END = "SYNC_LED_END:";
    public static final String MSG_PREFIX_PEER_JOINED = "PEER_JOINED:";

    public Peer(String nickname, int port) {
        this.nickname = nickname;
        this.port = port;
        this.balances.put(nickname, INITIAL_BALANCE);
        ConsoleLogger.print("Welcome, " + nickname + "! Your initial balance is " + INITIAL_BALANCE);
    }

    // --- Server Lifecycle ---

    public void startServer() {
        try {
            serverSocket = new ServerSocket(port);
            ConsoleLogger.print("Server listening on port " + port);

            Thread serverThread = new Thread(() -> {
                while (running && !serverSocket.isClosed()) {
                    try {
                        Socket clientSocket = serverSocket.accept();
                        ConsoleLogger.print("Incoming connection from " + clientSocket.getRemoteSocketAddress());
                        // Создаем обработчик для нового соединения
                        PeerConnectionHandler handler = new PeerConnectionHandler(clientSocket, this, true);
                        connectionExecutor.submit(handler); // Запускаем обработчик в отдельном потоке
                    } catch (SocketException e) {
                        if (running) ConsoleLogger.print("Server socket closed or error accepting connection: " + e.getMessage());
                    } catch (IOException e) {
                        if (running) ConsoleLogger.print("Error accepting connection: " + e.getMessage());
                    } catch (Exception e){
                        if(running) ConsoleLogger.print("Unexpected error in server accept loop: " + e.getMessage());
                    }
                }
                ConsoleLogger.print("Server listener stopped.");
            }, "ServerAccept-" + port); // Имя потока
            serverThread.start();

        } catch (IOException e) {
            ConsoleLogger.print("FATAL: Could not start server on port " + port + ": " + e.getMessage());
            // В реальном приложении здесь может быть более сложная обработка ошибки
             System.exit(1); // Завершаем работу, если не удалось запустить сервер
        }
    }

    // --- Client Actions ---

    public void connectToPeer(String host, int peerPort) {
        // Проверка на самоподключение
        InetSocketAddress localAddress = serverSocket == null ? null : (InetSocketAddress) serverSocket.getLocalSocketAddress();
        if ((host.equals("localhost") || host.equals("127.0.0.1")) && localAddress != null && localAddress.getPort() == peerPort) {
             ConsoleLogger.print("Cannot connect to self.");
             return;
        }
        // Проверка на уже существующее соединение
        for (PeerConnectionHandler handler : connections) {
             if (handler.getPeerInfo().contains(host + ":" + peerPort) || handler.getPeerInfo().contains("/"+host + ":" + peerPort)) {
                 ConsoleLogger.print("Already connected or connecting to " + host + ":" + peerPort);
                 return;
             }
        }

        ConsoleLogger.print("Attempting to connect to " + host + ":" + peerPort + "...");
        try {
            // Устанавливаем соединение
            Socket socket = new Socket(host, peerPort);
            ConsoleLogger.print("Successfully connected to " + host + ":" + peerPort);
            // Создаем обработчик для нового соединения
            PeerConnectionHandler handler = new PeerConnectionHandler(socket, this, false);
            connectionExecutor.submit(handler); // Запускаем обработчик
        } catch (IOException e) {
            ConsoleLogger.print("Could not connect to " + host + ":" + peerPort + ": " + e.getMessage());
        } catch (Exception e) {
            ConsoleLogger.print("Unexpected error connecting to " + host + ":" + peerPort + ": " + e.getMessage());
        }
    }

    // --- Network Message Handling & State Management ---

    /**
     * Обрабатывает входящее сообщение о транзакции.
     * Валидирует, применяет локально и ретранслирует другим пирам.
     */
    public synchronized void processTransaction(String txnMessage, PeerConnectionHandler sourceHandler) {
        String sourceInfo = (sourceHandler != null) ? sourceHandler.getPeerInfo() : "LOCAL";

        String[] parts = txnMessage.split(":");
        if (parts.length != 4 || !parts[0].equals("TXN")) {
            ConsoleLogger.print("WARN: Received invalid transaction message format from " + sourceInfo + ": " + txnMessage);
            return;
        }

        String sender = parts[1];
        String recipient = parts[2];
        int amount;
        try { amount = Integer.parseInt(parts[3]); }
        catch (NumberFormatException e) { ConsoleLogger.print("WARN: Invalid amount in transaction from " + sourceInfo + ": " + parts[3]); return; }

        // Валидация
        if (amount <= 0) { ConsoleLogger.print("WARN: Transaction amount must be positive from " + sourceInfo + ": " + amount); return; }
        if (sender.equals(recipient)) { ConsoleLogger.print("WARN: Cannot send coins to yourself (from " + sourceInfo + ")"); return; }
        int senderBalance = balances.getOrDefault(sender, -1);
        if (senderBalance == -1) { ConsoleLogger.print("WARN: Transaction sender '" + sender + "' not found. Rejecting TXN from " + sourceInfo); return; }
        if (senderBalance < amount) { ConsoleLogger.print("WARN: Transaction failed from " + sourceInfo + ". Sender '" + sender + "' insufficient funds (Needs " + amount + ", has " + senderBalance + ")"); return; }
        if (!balances.containsKey(recipient)) { ConsoleLogger.print("WARN: Transaction failed from " + sourceInfo + ". Recipient '" + recipient + "' not found."); return; }

        // Применение
        balances.compute(sender, (k, v) -> v - amount);
        balances.compute(recipient, (k, v) -> v + amount);

        // Добавление в реестр
        LocalDateTime now = LocalDateTime.now();
        DateTimeFormatter formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
        String formattedNow = now.format(formatter);
        String ledgerEntry = formattedNow + " | " + sender + " sent " + amount + " coins to " + recipient;
        if (!transactionLedger.contains(ledgerEntry)) {
             transactionLedger.add(ledgerEntry);
        }

        ConsoleLogger.print("Transaction applied: " + sender + " -> " + amount + " -> " + recipient + " (Validated TXN from " + sourceInfo + ")");
        if (sender.equals(this.nickname) || recipient.equals(this.nickname)) {
             ConsoleLogger.print("Your new balance: " + balances.getOrDefault(this.nickname, 0));
        }

        // Ретрансляция
        if (sourceHandler != null) {
            // ConsoleLogger.print("Relaying transaction '" + txnMessage + "' received from " + sourceHandler.getPeerNickname() + " to other peers...");
            broadcastMessage(txnMessage, sourceHandler);
        }
    }

    /**
     * Рассылает сообщение всем активным соединениям, кроме источника.
     */
    public void broadcastMessage(String message, PeerConnectionHandler source) {
        int relayedTo = 0;
        for (PeerConnectionHandler handler : connections) {
            if (handler != source) {
                handler.sendMessage(message);
                relayedTo++;
            }
        }
        // if (relayedTo > 0 && source != null) {
        //     ConsoleLogger.print("DEBUG: Relayed message '" + message.substring(0, Math.min(30, message.length())) + "...' to " + relayedTo + " peer(s).");
        // }
    }

    /**
     * Отправляет текущее полное состояние (балансы и реестр) запросившему пиру.
     */
    public void sendFullState(PeerConnectionHandler requesterHandler) {
        String requesterInfo = requesterHandler.getPeerInfo();
        ConsoleLogger.print("Sending full state to " + requesterInfo + "...");

        try {
            // Балансы
            requesterHandler.sendMessage(MSG_PREFIX_SYNC_BAL_START);
            Map<String, Integer> balancesCopy = new ConcurrentHashMap<>(this.balances);
            for (Map.Entry<String, Integer> entry : balancesCopy.entrySet()) {
                requesterHandler.sendMessage(MSG_PREFIX_SYNC_BAL_ENTRY + entry.getKey() + ":" + entry.getValue());
            }
            requesterHandler.sendMessage(MSG_PREFIX_SYNC_BAL_END);

            // Реестр
            requesterHandler.sendMessage(MSG_PREFIX_SYNC_LED_START);
            List<String> ledgerCopy = new CopyOnWriteArrayList<>(this.transactionLedger);
            for (String entry : ledgerCopy) {
                 String safeEntry = entry.replace(":", ";"); // Защита от разделителя
                requesterHandler.sendMessage(MSG_PREFIX_SYNC_LED_ENTRY + safeEntry);
            }
            requesterHandler.sendMessage(MSG_PREFIX_SYNC_LED_END);
            // ConsoleLogger.print("Full state sent successfully to " + requesterInfo); // Handler сообщит об этом
        } catch (Exception e){
            ConsoleLogger.print("ERROR: Failed to send full state to " + requesterInfo + ": "+ e.getMessage());
        }
    }

    /**
     * Применяет полное состояние, полученное от другого пира во время синхронизации.
     * Полностью перезаписывает локальные балансы и реестр.
     */
     public synchronized void applyFullState(Map<String, Integer> receivedBalances, List<String> receivedLedger, String sourcePeerInfo) {
        ConsoleLogger.print("Applying received state from " + sourcePeerInfo + " (" + receivedBalances.size() + " balances, " + receivedLedger.size() + " ledger entries)...");

        if (receivedBalances.isEmpty() && receivedLedger.isEmpty()){
             // ConsoleLogger.print("DEBUG: Received empty state from " + sourcePeerInfo + ". No changes applied.");
             return;
         }
        // ConsoleLogger.print("DEBUG: Balances received:");
        // receivedBalances.forEach((nick, bal) -> System.out.println("  -> " + nick + ": " + bal));

        this.balances.clear();
        this.balances.putAll(receivedBalances);
        // Гарантируем свой баланс
        this.balances.put(this.nickname, receivedBalances.getOrDefault(this.nickname, INITIAL_BALANCE));

        this.transactionLedger.clear();
        this.transactionLedger.addAll(receivedLedger);

        ConsoleLogger.print("State synchronized successfully from " + sourcePeerInfo + ".");
        ConsoleLogger.print("Your current balance after sync: " + balances.getOrDefault(this.nickname, 0));
        ConsoleLogger.print("Ledger size after sync: " + transactionLedger.size());
        // ConsoleLogger.print("DEBUG: Current known peers after sync: " + this.balances.keySet().stream().collect(Collectors.joining(", ")));
    }

    /**
     * Обрабатывает уведомление о новом пире, полученное от соседа.
     * Добавляет пира в локальную карту и ретранслирует уведомление.
     */
    public synchronized void learnAboutPeer(String newPeerNick, int initialBalance, PeerConnectionHandler sourceHandler) {
        if (newPeerNick.equals(this.nickname)) { return; } // Игнорируем себя

        if (balances.putIfAbsent(newPeerNick, initialBalance) == null) {
            ConsoleLogger.print("Learned about new peer '" + newPeerNick + "' from " + sourceHandler.getPeerNickname() + ". Added with initial balance.");
            // Ретранслируем сообщение дальше
            String joinMsg = MSG_PREFIX_PEER_JOINED + newPeerNick + ":" + initialBalance;
            // ConsoleLogger.print("Relaying PEER_JOINED message for '" + newPeerNick + "' to other peers...");
            broadcastMessage(joinMsg, sourceHandler); // Отправляем всем, кроме источника
        }
        // else { // Пир уже известен, не ретранслируем
             // ConsoleLogger.print("DEBUG: Already knew about peer '" + newPeerNick + "'. Ignoring PEER_JOINED gossip from " + sourceHandler.getPeerNickname());
        // }
    }

    // --- Connection Management ---

    /**
     * Добавляет новое активное соединение и рассылает уведомление PEER_JOINED.
     * Вызывается из PeerConnectionHandler после успешного обмена никами.
     */
    public void addConnection(PeerConnectionHandler handler) {
        if (handler == null || handler.getPeerNickname().equals("UNKNOWN")) {
            ConsoleLogger.print("WARN: Attempted to add connection before nickname exchange completed.");
            return;
        }
        String peerNickname = handler.getPeerNickname();
        balances.putIfAbsent(peerNickname, INITIAL_BALANCE); // Добавляем, если не было
        connections.add(handler);
        ConsoleLogger.print("Peer connected: " + handler.getPeerInfo() + ". Known balances: " + balances.size() + ". Active connections: " + connections.size());

        // Рассылаем уведомление о новом пире другим соседям
        String joinMsg = MSG_PREFIX_PEER_JOINED + peerNickname + ":" + INITIAL_BALANCE;
        // ConsoleLogger.print("Gossiping about peer join: " + joinMsg);
        broadcastMessage(joinMsg, handler);
    }

    /**
     * Удаляет соединение из списка активных.
     * Вызывается из PeerConnectionHandler при закрытии соединения.
     */
    public void removeConnection(PeerConnectionHandler handler) {
        if (handler == null) return;
        String handlerInfo = handler.getPeerInfo(); // Получаем инфо до удаления
        if (connections.remove(handler)) {
            ConsoleLogger.print("Peer disconnected: " + handlerInfo + ". Active connections: " + connections.size());
        }
    }

    // --- User Input ---

    public void startUserInput() {
        Scanner scanner = new Scanner(System.in);
        Thread.currentThread().setName("UserInput-" + nickname);
        ConsoleLogger.print("Enter commands: /connect <host> <port>, /send <nick> <amount>, /balance, /ledger, /peers, /exit");

        while (running) {
            System.out.print(nickname + "> ");
            if (!scanner.hasNextLine()) { ConsoleLogger.print("Input stream closed. Shutting down..."); shutdown(); break; }
            String input = scanner.nextLine().trim();
            if (input.isEmpty()) continue;

            String[] parts = input.split("\\s+");
            String command = parts[0].toLowerCase();

            try {
                switch (command) {
                    case "/exit": shutdown(); scanner.close(); return;
                    case "/connect":
                        if (parts.length == 3) {
                            try { connectToPeer(parts[1], Integer.parseInt(parts[2])); }
                            catch (NumberFormatException e) { ConsoleLogger.print("Invalid port number."); }
                        } else { ConsoleLogger.print("Usage: /connect <host> <port>"); }
                        break;
                    case "/send":
                        if (parts.length == 3) {
                            String recipientNick = parts[1];
                            try {
                                int amount = Integer.parseInt(parts[2]);
                                if (amount <= 0) { ConsoleLogger.print("Amount must be positive."); continue; }
                                if (recipientNick.equals(this.nickname)) { ConsoleLogger.print("Cannot send to yourself."); continue; }
                                if (!balances.containsKey(recipientNick)) { ConsoleLogger.print("Transaction failed: Recipient '" + recipientNick + "' is not known."); continue; }
                                int myBalance = balances.getOrDefault(this.nickname, 0);
                                if (myBalance < amount) { ConsoleLogger.print("Transaction failed: Insufficient funds. You have " + myBalance + ", need " + amount); }
                                else {
                                    String txnMessage = MSG_PREFIX_TXN + this.nickname + ":" + recipientNick + ":" + amount;
                                    ConsoleLogger.print("Initiating transaction: " + this.nickname + " -> " + amount + " -> " + recipientNick);
                                    processTransaction(txnMessage, null); // Локальная обработка
                                    broadcastMessage(txnMessage, null); // Рассылка всем соседям
                                }
                            } catch (NumberFormatException e) { ConsoleLogger.print("Invalid amount."); }
                        } else { ConsoleLogger.print("Usage: /send <recipient_nickname> <amount>"); }
                        break;
                    case "/balance": ConsoleLogger.print("Your current balance: " + balances.getOrDefault(this.nickname, 0)); break;
                    case "/ledger":
                        ConsoleLogger.print("--- Transaction Ledger (" + transactionLedger.size() + " entries) ---");
                        if (transactionLedger.isEmpty()) { System.out.println("  (Ledger is empty)"); } // Используем System.out для чистого вывода
                        else { int i = 1; for (String entry : transactionLedger) System.out.println("  " + (i++) + ". " + entry); }
                        ConsoleLogger.print("------------------------------------------");
                        break;
                     case "/peers":
                         ConsoleLogger.print("--- Known Peers and Balances (" + balances.size() + ") ---");
                         if (balances.isEmpty()){ System.out.println(" (None known yet)"); }
                         else {
                             balances.entrySet().stream()
                                .sorted(Map.Entry.comparingByKey())
                                .forEach(entry -> System.out.printf("  - %-15s: %d coins %s%n", entry.getKey(), entry.getValue(), (entry.getKey().equals(this.nickname) ? " (You)" : "")));
                         }
                         ConsoleLogger.print("--- Active Connections ("+ connections.size() +") ---");
                         if (connections.isEmpty()) { System.out.println("  (No active connections)"); }
                         else { connections.forEach(handler -> System.out.println("  - Connected to: " + handler.getPeerInfo())); }
                          ConsoleLogger.print("------------------------------------");
                         break;
                    default: ConsoleLogger.print("Unknown command: '" + command + "'. Available: /connect, /send, /balance, /ledger, /peers, /exit"); break;
                }
            } catch (Exception e) { ConsoleLogger.print("ERROR processing command '" + input + "': " + e.getMessage()); }
        }
        if (scanner != null) { try { scanner.close(); } catch (Exception e) {/*ignore*/} }
        ConsoleLogger.print("User input stopped.");
    }

    // --- Shutdown ---

    public void shutdown() {
        if (!running) return;
        ConsoleLogger.print("Shutdown initiated...");
        running = false;

        if (serverSocket != null && !serverSocket.isClosed()) { try { serverSocket.close(); } catch (IOException e) { /* ignore */ } }

        List<PeerConnectionHandler> connectionsToClose = new CopyOnWriteArrayList<>(connections);
        ConsoleLogger.print("Closing " + connectionsToClose.size() + " active connection(s)...");
        connectionsToClose.forEach(PeerConnectionHandler::close); // Используем method reference
        connections.clear();

        connectionExecutor.shutdown();
        try {
            // ConsoleLogger.print("Waiting for connection handlers to terminate...");
            if (!connectionExecutor.awaitTermination(2, java.util.concurrent.TimeUnit.SECONDS)) {
                // ConsoleLogger.print("Forcing shutdown of connection handlers...");
                connectionExecutor.shutdownNow();
            } // else { ConsoleLogger.print("Connection handlers terminated."); }
        } catch (InterruptedException e) {
            connectionExecutor.shutdownNow(); Thread.currentThread().interrupt();
        }
        ConsoleLogger.print("Shutdown complete.");
    }

    // --- Getters ---
    public String getNickname() { return nickname; }

} // Конец класса Peer