package dev.Block.network;



import java.io.*;
import java.net.Socket;
import java.net.SocketException;
import java.util.Map;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

import dev.Block.Peer;
import dev.Block.util.ConsoleLogger;

/**
 * Обрабатывает одно P2P соединение (входящее или исходящее).
 * Отвечает за обмен никами, чтение/запись сообщений, синхронизацию состояния
 * и взаимодействие с родительским объектом Peer.
 */
public class PeerConnectionHandler implements Runnable {

    public final Socket socket;
    private final Peer parentPeer; // Ссылка на основной объект Peer
    private DataInputStream in;
    private DataOutputStream out;
    private volatile String peerNickname = "UNKNOWN";
    private final boolean isIncoming;
    private volatile boolean handlerRunning = true;
    private final String connectionId;

    // Флаги и буферы для сборки состояния при синхронизации
    private boolean isSyncingBalances = false;
    private boolean isSyncingLedger = false;
    private Map<String, Integer> syncBalancesBuffer = new ConcurrentHashMap<>();
    private List<String> syncLedgerBuffer = new CopyOnWriteArrayList<>();

    public PeerConnectionHandler(Socket socket, Peer parentPeer, boolean isIncoming) {
        this.socket = socket;
        this.parentPeer = parentPeer;
        this.isIncoming = isIncoming;
        this.connectionId = (isIncoming ? "IN" : "OUT") + "@" + socket.getRemoteSocketAddress();

        try {
            this.in = new DataInputStream(new BufferedInputStream(socket.getInputStream()));
            this.out = new DataOutputStream(new BufferedOutputStream(socket.getOutputStream()));
            // Установка имени потока происходит в методе run после обмена никами
        } catch (IOException e) {
            ConsoleLogger.print("ERROR [" + connectionId + "]: Failed to create streams: " + e.getMessage());
            close(); // Закрываемся, если не удалось создать потоки
        }
    }

    public String getPeerInfo() {
        return peerNickname + "@" + (socket != null ? socket.getRemoteSocketAddress() : "disconnected");
    }

    public String getPeerNickname() {
        return peerNickname;
    }

    @Override
    public void run() {
        Thread.currentThread().setName("Handler-" + connectionId + "-Init"); // Начальное имя потока
        ConsoleLogger.print("Handler thread started for " + connectionId);
        try {
            // 1. Обмен никнеймами
            if (!exchangeNicknames()) {
                ConsoleLogger.print("ERROR [" + connectionId + "]: Nickname exchange failed. Closing connection.");
                close();
                return;
            }
            // Теперь у нас есть peerNickname, обновляем имя потока
            Thread.currentThread().setName("Handler-" + getPeerInfo());

            // 2. Добавляем в список соединений родителя (это инициирует PEER_JOINED gossip)
            parentPeer.addConnection(this);

            // 3. Если МЫ инициировали соединение, запрашиваем синхронизацию
            if (!isIncoming) {
                ConsoleLogger.print("Requesting state sync from " + peerNickname);
                sendMessage(Peer.MSG_PREFIX_REQ_SYNC + parentPeer.getNickname()); // Используем константы из Peer
            }

            // 4. Основной цикл чтения сообщений
            while (handlerRunning && socket != null && !socket.isClosed() && in != null) {
                String message = in.readUTF();
                // ConsoleLogger.print("DEBUG [" + parentPeer.getNickname() + "]: Raw msg from " + peerNickname + ": " + message);

                try { // Обертка для обработки ошибок внутри цикла
                    // --- Обработка сообщений по префиксам ---
                    if (message.startsWith(Peer.MSG_PREFIX_TXN)) {
                        parentPeer.processTransaction(message, this);
                    } else if (message.startsWith(Peer.MSG_PREFIX_REQ_SYNC)) {
                        parentPeer.sendFullState(this);
                    } else if (message.startsWith(Peer.MSG_PREFIX_PEER_JOINED)) {
                        String data = message.substring(Peer.MSG_PREFIX_PEER_JOINED.length());
                        String[] parts = data.split(":", 2);
                        if (parts.length == 2) {
                            try {
                                int balance = Integer.parseInt(parts[1]);
                                parentPeer.learnAboutPeer(parts[0], balance, this); // Обработка и ретрансляция
                            } catch (NumberFormatException e) { ConsoleLogger.print("WARN [" + parentPeer.getNickname() + "]: Invalid balance in PEER_JOINED msg from " + peerNickname + ": " + parts[1]); }
                        } else { ConsoleLogger.print("WARN [" + parentPeer.getNickname() + "]: Invalid PEER_JOINED format from " + peerNickname + ": " + data); }
                    }
                    // --- Обработка сообщений синхронизации ---
                     else if (message.startsWith(Peer.MSG_PREFIX_SYNC_BAL_START)) {
                         isSyncingBalances = true; syncBalancesBuffer.clear();
                         ConsoleLogger.print("Receiving balance state from " + peerNickname + "...");
                     } else if (message.startsWith(Peer.MSG_PREFIX_SYNC_BAL_ENTRY)) {
                          if (isSyncingBalances) {
                               String data = message.substring(Peer.MSG_PREFIX_SYNC_BAL_ENTRY.length()); String[] parts = data.split(":", 2);
                               if (parts.length == 2) { try { syncBalancesBuffer.put(parts[0], Integer.parseInt(parts[1])); } catch (NumberFormatException e) { ConsoleLogger.print("WARN [" + parentPeer.getNickname() + "]: Invalid balance amount in SYNC_BAL from " + peerNickname + ": " + parts[1]); } }
                               else { ConsoleLogger.print("WARN [" + parentPeer.getNickname() + "]: Invalid SYNC_BAL_ENTRY format from " + peerNickname + ": " + data); }
                          } else { ConsoleLogger.print("WARN [" + parentPeer.getNickname() + "]: Received SYNC_BAL_ENTRY from " + peerNickname + " while not syncing balances."); }
                     } else if (message.startsWith(Peer.MSG_PREFIX_SYNC_BAL_END)) {
                         isSyncingBalances = false;
                         ConsoleLogger.print("Balance state received from " + peerNickname + " (" + syncBalancesBuffer.size() + " entries).");
                         tryApplyFullState();
                     } else if (message.startsWith(Peer.MSG_PREFIX_SYNC_LED_START)) {
                         isSyncingLedger = true; syncLedgerBuffer.clear();
                         ConsoleLogger.print("Receiving ledger state from " + peerNickname + "...");
                     } else if (message.startsWith(Peer.MSG_PREFIX_SYNC_LED_ENTRY)) {
                          if (isSyncingLedger) { String entry = message.substring(Peer.MSG_PREFIX_SYNC_LED_ENTRY.length()).replace(";", ":"); syncLedgerBuffer.add(entry); }
                          else { ConsoleLogger.print("WARN [" + parentPeer.getNickname() + "]: Received SYNC_LED_ENTRY from " + peerNickname + " while not syncing ledger."); }
                     } else if (message.startsWith(Peer.MSG_PREFIX_SYNC_LED_END)) {
                         isSyncingLedger = false;
                         ConsoleLogger.print("Ledger state received from " + peerNickname + " (" + syncLedgerBuffer.size() + " entries).");
                         tryApplyFullState();
                     } else if (message.startsWith(Peer.MSG_PREFIX_CHAT)){
                         ConsoleLogger.print("(Chat) " + message.substring(Peer.MSG_PREFIX_CHAT.length()));
                     }
                     else {
                         ConsoleLogger.print("WARN [" + parentPeer.getNickname() + "]: Received unknown message format from " + peerNickname + ": " + message.substring(0, Math.min(message.length(), 60)) + "...");
                     }
                 } catch (Exception e) {
                     // Ошибка при обработке КОНКРЕТНОГО сообщения
                      ConsoleLogger.print("ERROR [" + parentPeer.getNickname() + "]: Failed to process message from " + peerNickname + ": " + e.getMessage());
                      // e.printStackTrace(); // Раскомментировать для полного стека
                 }
            } // end while loop
        } catch (EOFException e) {
             // Нормальное завершение, если другая сторона закрыла соединение
             if (handlerRunning) ConsoleLogger.print("Connection closed by peer: " + getPeerInfo());
        } catch (SocketException e) {
             // Ошибка сокета (например, сеть упала, reset by peer)
             if (handlerRunning) ConsoleLogger.print("Socket error with " + getPeerInfo() + ": " + e.getMessage());
        } catch (IOException e) {
             // Другие ошибки ввода/вывода
             if (handlerRunning) ConsoleLogger.print("IO error with " + getPeerInfo() + ": " + e.getMessage());
        } catch (Exception e) {
             // Неожиданные ошибки в потоке обработчика
             if (handlerRunning) ConsoleLogger.print("Unexpected error in handler for " + getPeerInfo() + ": " + e.getMessage());
             // e.printStackTrace(); // Раскомментировать для полного стека
        } finally {
            // Гарантированное закрытие ресурсов и удаление из списка
            close();
        }
        ConsoleLogger.print("Handler thread finished for " + getPeerInfo());
    }

    // Метод для попытки применить полное состояние
    private void tryApplyFullState() {
         if (!isSyncingBalances && !isSyncingLedger) {
             if (!syncBalancesBuffer.isEmpty() || !syncLedgerBuffer.isEmpty()) {
                 // ConsoleLogger.print("DEBUG [" + parentPeer.getNickname() + "]: Both sync flags down. Applying full state from " + peerNickname);
                 // Передаем КОПИИ буферов для безопасности
                 parentPeer.applyFullState(new ConcurrentHashMap<>(syncBalancesBuffer),
                                            new CopyOnWriteArrayList<>(syncLedgerBuffer),
                                            this.peerNickname); // Передаем ник источника
                 syncBalancesBuffer.clear(); syncLedgerBuffer.clear();
              } else {
                 // ConsoleLogger.print("DEBUG [" + parentPeer.getNickname() + "]: Both sync flags down, but buffers are empty. Skipping applyFullState.");
              }
         }
    }

    // Обмен никнеймами
    private boolean exchangeNicknames() {
        try {
            String remoteNickname;
            if (isIncoming) {
                remoteNickname = in.readUTF();
                if (remoteNickname == null || remoteNickname.isEmpty() || remoteNickname.equalsIgnoreCase("UNKNOWN")) throw new IOException("Received invalid nickname");
                this.peerNickname = remoteNickname;
                ConsoleLogger.print("[" + connectionId + "] Received nickname '" + this.peerNickname + "'. Sending ours '" + parentPeer.getNickname() + "'");
                out.writeUTF(parentPeer.getNickname()); out.flush();
            } else {
                ConsoleLogger.print("[" + connectionId + "] Sending our nickname '" + parentPeer.getNickname() + "'");
                out.writeUTF(parentPeer.getNickname()); out.flush();
                remoteNickname = in.readUTF();
                if (remoteNickname == null || remoteNickname.isEmpty() || remoteNickname.equalsIgnoreCase("UNKNOWN")) throw new IOException("Received invalid nickname");
                this.peerNickname = remoteNickname;
                ConsoleLogger.print("[" + connectionId + "] Received nickname '" + this.peerNickname + "'");
            }
            return true;
        } catch (IOException e) { ConsoleLogger.print("ERROR [" + connectionId + "]: IOException during nickname exchange: " + e.getMessage()); return false; }
        catch (Exception e){ ConsoleLogger.print("ERROR [" + connectionId + "]: Unexpected error during nickname exchange: " + e.getMessage()); return false; }
    }

    // Отправка сообщения этому пиру
    public void sendMessage(String message) {
        if (!handlerRunning || socket == null || socket.isClosed() || out == null) { return; }
        try {
            // ConsoleLogger.print("DEBUG: Sending to " + peerNickname + ": " + message.substring(0, Math.min(50, message.length())) + "...");
            out.writeUTF(message); out.flush();
        } catch (SocketException e) { ConsoleLogger.print("WARN: Failed to send message to " + getPeerInfo() + " (Socket closed/error): " + e.getMessage()); close(); }
        catch (IOException e) { ConsoleLogger.print("ERROR: IOException sending message to " + getPeerInfo() + ": " + e.getMessage()); close(); }
        catch (Exception e){ ConsoleLogger.print("ERROR: Unexpected error sending message to " + getPeerInfo() + ": " + e.getMessage()); close(); }
    }

    // Синхронизированный метод закрытия ресурсов
    public synchronized void close() {
        if (!handlerRunning) return;
        handlerRunning = false;
        String info = getPeerInfo();
        ConsoleLogger.print("Closing connection handler for " + info + "...");
        parentPeer.removeConnection(this); // Уведомляем родителя
        try { if (out != null) out.close(); } catch (IOException e) { /* ignore */ }
        try { if (in != null) in.close(); } catch (IOException e) { /* ignore */ }
        try { if (socket != null && !socket.isClosed()) socket.close(); } catch (IOException e) { /* ignore */ }
        out = null; in = null;
        ConsoleLogger.print("Connection handler closed for " + info);
    }
}