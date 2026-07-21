package com.pos.sync;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.pos.config.ConfigManager;
import io.socket.client.IO;
import io.socket.client.Socket;
import io.socket.emitter.Emitter;
import org.json.JSONObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.util.Arrays;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

/**
 * WebSocket client for real-time synchronization with the backend.
 * 
 * <h2>Features</h2>
 * <ul>
 *   <li>Socket.IO client for real-time communication</li>
 *   <li>Automatic reconnection with exponential backoff</li>
 *   <li>Heartbeat monitoring to detect connection issues</li>
 *   <li>Event-based message handling</li>
 *   <li>Thread-safe connection management</li>
 * </ul>
 * 
 * <h2>Connection Flow</h2>
 * <pre>
 * 1. Initialize with API credentials
 * 2. Connect to /pos namespace
 * 3. Authenticate via handshake query params
 * 4. Register event listeners
 * 5. Start heartbeat monitoring
 * </pre>
 */
public class WebSocketClient {
    private static final Logger logger = LoggerFactory.getLogger(WebSocketClient.class);
    private static WebSocketClient instance;

    // Connection configuration
    private static final int HEARTBEAT_INTERVAL_MS = 25000; // 25 seconds
    private static final int INITIAL_RECONNECT_DELAY_MS = 1000; // 1 second
    private static final int MAX_RECONNECT_DELAY_MS = 30000; // 30 seconds
    private static final int DEFAULT_MAX_RECONNECT_ATTEMPTS = 10;

    private final ConfigManager config;
    private final int maxReconnectAttempts;
    private final Gson gson;
    private final ScheduledExecutorService scheduler;
    private final WebSocketMessageHandler messageHandler;
    private volatile ScheduledFuture<?> heartbeatTask;

    private Socket socket;
    private final AtomicBoolean isConnected = new AtomicBoolean(false);
    private final AtomicBoolean isConnecting = new AtomicBoolean(false);
    private final AtomicBoolean isShuttingDown = new AtomicBoolean(false);
    private final AtomicInteger reconnectAttempts = new AtomicInteger(0);
    private final AtomicBoolean reconnectExhausted = new AtomicBoolean(false);

    // Connection status listeners
    private Consumer<Boolean> connectionStatusListener;
    private Consumer<String> errorListener;

    private WebSocketClient() {
        this.config = ConfigManager.getInstance();
        this.gson = new GsonBuilder()
                .setDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'")
                .create();
        ThreadFactory threadFactory = runnable -> {
            Thread thread = new Thread(runnable, "WebSocketClientScheduler");
            thread.setDaemon(true);
            return thread;
        };
        this.scheduler = Executors.newScheduledThreadPool(2, threadFactory);
        this.messageHandler = new WebSocketMessageHandler();
        this.maxReconnectAttempts = config.getIntProperty(
                "websocket.reconnect.maxAttempts", DEFAULT_MAX_RECONNECT_ATTEMPTS);
    }

    public static synchronized WebSocketClient getInstance() {
        if (instance == null) {
            instance = new WebSocketClient();
        }
        return instance;
    }

    /**
     * Get the message handler for registering event listeners.
     * @return The WebSocketMessageHandler instance
     */
    public WebSocketMessageHandler getMessageHandler() {
        return messageHandler;
    }

    /**
     * Initialize and connect to the WebSocket server.
     * 
     * @return true if connection initiated successfully
     */
    public boolean connect() {
        if (isShuttingDown.get()) {
            logger.debug("Skipping WebSocket connect because shutdown is in progress");
            return false;
        }

        if (reconnectExhausted.get()) {
            logger.debug("WebSocket reconnect exhausted — using API polling only");
            return false;
        }

        if (isConnected.get()) {
            logger.info("WebSocket already connected");
            return true;
        }

        if (isConnecting.get()) {
            logger.debug("WebSocket connection already in progress");
            return true;
        }

        String apiKey = config.getProperty("api.key", "");
        String apiSecret = config.getProperty("api.secret", "");
        String storeId = config.getProperty("store.id", "");
        String deviceId = config.getProperty("device.id", "");
        String baseUrl = config.getProperty("backend.api.url", "http://localhost:3000/api/v1");

        logger.info("🔌 WebSocket credentials check: apiKey={}, deviceId={}, storeId={}", 
                apiKey.isEmpty() ? "MISSING" : "✓", 
                deviceId.isEmpty() ? "MISSING" : "✓", 
                storeId.isEmpty() ? "MISSING" : "✓");

        if (apiKey.isEmpty() || storeId.isEmpty() || deviceId.isEmpty()) {
            logger.error("❌ Cannot connect to WebSocket: Missing credentials (apiKey={}, storeId={}, deviceId={})",
                    apiKey.isEmpty() ? "MISSING" : "present",
                    storeId.isEmpty() ? "MISSING" : "present",
                    deviceId.isEmpty() ? "MISSING" : "present");
            notifyError("WebSocket credentials missing. Please register device first.");
            return false;
        }

        // Extract WebSocket URL from API URL
        String wsUrl = baseUrl.replace("/api/v1", "").replace("http://", "ws://").replace("https://", "wss://");
        
        // Ensure no trailing slash in wsUrl
        if (wsUrl.endsWith("/")) {
            wsUrl = wsUrl.substring(0, wsUrl.length() - 1);
        }
        
        // Tear down any previous socket so repeated connect() calls do not stack clients.
        tearDownSocket();

        isConnecting.set(true);
        isShuttingDown.set(false);

        try {
            int connectTimeoutMs = config.getIntProperty("websocket.connect.timeoutMs", 10000);

            // Configure Socket.IO options
            IO.Options options = IO.Options.builder()
                    .setPath("/socket.io/")
                    .setQuery("apiKey=" + apiKey + "&deviceId=" + deviceId + "&storeId=" + storeId)
                    .setTransports(new String[]{"websocket", "polling"})
                    .setReconnection(true)
                    .setReconnectionAttempts(maxReconnectAttempts)
                    .setReconnectionDelay(INITIAL_RECONNECT_DELAY_MS)
                    .setReconnectionDelayMax(MAX_RECONNECT_DELAY_MS)
                    .setTimeout(connectTimeoutMs)
                    .build();

            // Connect to /pos namespace
            String finalPath = wsUrl + "/pos";
            logger.info("🔌 Connecting to WebSocket server: {} (deviceId={})", finalPath, deviceId);
            
            socket = IO.socket(URI.create(finalPath), options);

            // Register event handlers
            registerEventHandlers();

            socket.connect();

            // Start heartbeat scheduler
            startHeartbeat();

            logger.info("✅ WebSocket connection initiated successfully");
            return true;
        } catch (Exception e) {
            logger.error("❌ Failed to create WebSocket connection", e);
            isConnecting.set(false);
            notifyError("Failed to connect: " + e.getMessage());
            return false;
        }
    }

    /**
     * Disconnect from the WebSocket server.
     */
    public void disconnect() {
        logger.info("Disconnecting from WebSocket server");
        tearDownSocket();
        notifyConnectionStatus(false);
    }

    /**
     * Whether automatic WebSocket reconnection has been exhausted for this session.
     */
    public boolean isReconnectExhausted() {
        return reconnectExhausted.get();
    }

    /**
     * Reset reconnect backoff so a fresh connection attempt can be made (e.g. after
     * regaining network or from Settings).
     */
    public void resetReconnectState() {
        reconnectExhausted.set(false);
        reconnectAttempts.set(0);
    }

    private void tearDownSocket() {
        stopHeartbeat();
        if (socket != null) {
            try {
                socket.io().reconnection(false);
            } catch (Exception e) {
                logger.debug("Could not disable socket reconnection", e);
            }
            try {
                socket.disconnect();
                socket.close();
            } catch (Exception e) {
                logger.debug("Error closing WebSocket socket", e);
            }
            socket = null;
        }
        isConnected.set(false);
        isConnecting.set(false);
    }

    private void handleReconnectExhausted() {
        if (!reconnectExhausted.compareAndSet(false, true)) {
            return;
        }
        logger.error(
                "❌ Max reconnection attempts ({}) reached. Giving up. Using API polling only.",
                maxReconnectAttempts);
        notifyError("Max reconnection attempts reached");
        tearDownSocket();
    }

    /**
     * Check if WebSocket is connected.
     */
    public boolean isConnected() {
        return isConnected.get() && socket != null && socket.connected();
    }

    /**
     * Register all Socket.IO event handlers.
     */
    private void registerEventHandlers() {
        // Connection events
        socket.on(Socket.EVENT_CONNECT, args -> {
            logger.info("✅ WebSocket connected successfully - Real-time sync active");
            isConnected.set(true);
            isConnecting.set(false);
            reconnectAttempts.set(0);
            reconnectExhausted.set(false);
            notifyConnectionStatus(true);
        });

        socket.on(Socket.EVENT_DISCONNECT, args -> {
            String reason = args.length > 0 ? args[0].toString() : "unknown";
            logger.warn("⚠️ WebSocket disconnected: {} - Falling back to API polling", reason);
            isConnected.set(false);
            notifyConnectionStatus(false);
        });

        socket.on(Socket.EVENT_CONNECT_ERROR, args -> {
            if (reconnectExhausted.get()) {
                return;
            }

            String error = args.length > 0 ? args[0].toString() : "unknown error";
            logger.error("❌ WebSocket connection error: {}", error);
            isConnecting.set(false);
            notifyError("Connection error: " + error);

            int attempts = reconnectAttempts.incrementAndGet();
            logger.warn("WebSocket reconnect attempt {}/{}", attempts, maxReconnectAttempts);
            if (attempts >= maxReconnectAttempts) {
                handleReconnectExhausted();
            }
        });

        // Server welcome message
        socket.on("connected", args -> {
            if (args.length > 0) {
                logger.info("📡 Server welcome message: {}", args[0]);
            }
        });

        // Pong response to heartbeat
        socket.on("pong", args -> {
            logger.trace("💓 Heartbeat acknowledged by server");
        });

        // === Real-time sync events ===

        // Product events
        socket.on("product_created", args -> {
            logger.info("📦 Received product_created event via WebSocket");
            handleEvent("product_created", args);
        });

        socket.on("product_updated", args -> {
            logger.info("✏️ Received product_updated event via WebSocket");
            handleEvent("product_updated", args);
        });

        socket.on("product_deleted", args -> {
            logger.info("🗑️ Received product_deleted event via WebSocket");
            handleEvent("product_deleted", args);
        });

        // Price change event (high priority)
        socket.on("price_changed", args -> {
            logger.info("💰 Received price_changed event via WebSocket");
            handleEvent("price_changed", args);
        });

        // Stock update event
        socket.on("stock_updated", args -> {
            logger.info("📊 Received stock_updated event via WebSocket");
            handleEvent("stock_updated", args);
        });

        // Department events
        socket.on("department_updated", args -> {
            logger.info("🏷️ Received department_updated event via WebSocket");
            handleEvent("department_updated", args);
        });

        socket.on("department_created", args -> {
            logger.info("🏷️ Received department_created event via WebSocket");
            handleEvent("department_updated", args);  // Reuse department_updated handler
        });

        // Vendor events
        socket.on("vendor_created", args -> {
            logger.info("🏪 Received vendor_created event via WebSocket");
            handleEvent("vendor_created", args);
        });

        socket.on("vendor_updated", args -> {
            logger.info("✏️ Received vendor_updated event via WebSocket");
            handleEvent("vendor_updated", args);
        });

        socket.on("vendor_deleted", args -> {
            logger.info("🗑️ Received vendor_deleted event via WebSocket");
            handleEvent("vendor_deleted", args);
        });

        // Sale completion (from other devices)
        socket.on("sale_completed", args -> {
            logger.info("💵 Received sale_completed event via WebSocket");
            handleEvent("sale_completed", args);
        });

        // Shift events
        socket.on("shift_event", args -> {
            logger.info("⏰ Received shift_event via WebSocket");
            handleEvent("shift_event", args);
        });

        // Force sync command
        socket.on("force_sync", args -> {
            logger.info("🔄 Received force_sync command via WebSocket");
            handleEvent("force_sync", args);
        });

        // Settings update
        socket.on("settings_updated", args -> {
            logger.info("⚙️ Received settings_updated event via WebSocket");
            handleEvent("settings_updated", args);
        });

        // Global settings update (card pricing, etc.)
        socket.on("global_settings_updated", args -> {
            logger.info("🌐 Received global_settings_updated event via WebSocket");
            handleEvent("global_settings_updated", args);
        });

        // General notification
        socket.on("notification", args -> {
            logger.info("🔔 Received notification via WebSocket");
            handleEvent("notification", args);
        });

        // Stock alert from other devices
        socket.on("stock_alert", args -> {
            handleEvent("stock_alert", args);
        });
    }

    /**
     * Handle incoming WebSocket event.
     */
    private void handleEvent(String eventType, Object[] args) {
        if (args.length == 0) {
            logger.warn("Received empty {} event", eventType);
            return;
        }

        try {
            String jsonData = args[0].toString();
            logger.debug("Received {} event: {}", eventType, jsonData);

            // Delegate to message handler
            messageHandler.handleMessage(eventType, jsonData);

            // Send acknowledgment for critical events
            if (shouldAcknowledge(eventType)) {
                sendAcknowledgment(jsonData);
            }
        } catch (Exception e) {
            logger.error("Error handling {} event", eventType, e);
        }
    }

    /**
     * Check if event type requires acknowledgment.
     */
    private boolean shouldAcknowledge(String eventType) {
        return Arrays.asList(
                "product_created", 
                "product_updated", 
                "product_deleted",
                "price_changed",
                "stock_updated",
                "vendor_created",
                "vendor_updated",
                "vendor_deleted",
                "force_sync"
        ).contains(eventType);
    }

    /**
     * Send acknowledgment for a message.
     */
    private void sendAcknowledgment(String messageJson) {
        try {
            JSONObject received = new JSONObject(messageJson);
            String messageId = received.optString("id", null);
            
            if (messageId != null && socket != null && isConnected.get()) {
                JSONObject ack = new JSONObject();
                ack.put("messageId", messageId);
                ack.put("received", true);
                ack.put("processed", true);
                
                socket.emit("ack", ack);
                logger.debug("Sent ACK for message: {}", messageId);
            }
        } catch (Exception e) {
            logger.warn("Failed to send acknowledgment", e);
        }
    }

    /**
     * Start heartbeat scheduler.
     */
    private void startHeartbeat() {
        if (isShuttingDown.get() || scheduler.isShutdown() || scheduler.isTerminated()) {
            logger.debug("Skipping heartbeat startup because WebSocketClient is shutting down");
            return;
        }

        if (heartbeatTask != null && !heartbeatTask.isCancelled() && !heartbeatTask.isDone()) {
            return;
        }

        heartbeatTask = scheduler.scheduleAtFixedRate(() -> {
            if (isConnected.get() && socket != null) {
                try {
                    JSONObject status = new JSONObject();
                    status.put("batteryLevel", 100);
                    status.put("networkType", "ethernet");
                    status.put("pendingSales", getPendingSalesCount());
                    
                    JSONObject heartbeat = new JSONObject();
                    heartbeat.put("deviceId", config.getProperty("device.id", ""));
                    heartbeat.put("timestamp", java.time.Instant.now().toString());
                    heartbeat.put("status", status);
                    
                    socket.emit("heartbeat", heartbeat);
                    logger.debug("Sent heartbeat");
                } catch (Exception e) {
                    logger.warn("Failed to send heartbeat", e);
                }
            }
        }, HEARTBEAT_INTERVAL_MS, HEARTBEAT_INTERVAL_MS, TimeUnit.MILLISECONDS);
    }

    private void stopHeartbeat() {
        ScheduledFuture<?> currentTask = heartbeatTask;
        if (currentTask != null) {
            currentTask.cancel(true);
            heartbeatTask = null;
        }
    }

    /**
     * Get count of pending offline sales.
     */
    private int getPendingSalesCount() {
        return com.pos.service.SalesService.getInstance().getPendingSalesCount();
    }

    /**
     * Emit a custom event to the server.
     */
    public void emit(String event, Object data) {
        if (socket != null && isConnected.get()) {
            try {
                JSONObject jsonData = new JSONObject(gson.toJson(data));
                socket.emit(event, jsonData);
                logger.debug("Emitted event: {}", event);
            } catch (Exception e) {
                logger.warn("Failed to emit {}: {}", event, e.getMessage());
            }
        } else {
            logger.warn("Cannot emit {}: WebSocket not connected", event);
        }
    }

    /**
     * Emit sale completion to other devices.
     */
    public void emitSaleCompleted(String saleId, double total, int itemCount, String paymentMethod, String cashierName) {
        try {
            JSONObject message = new JSONObject();
            message.put("saleId", saleId);
            message.put("total", total);
            message.put("itemCount", itemCount);
            message.put("paymentMethod", paymentMethod);
            message.put("cashierName", cashierName);
            message.put("timestamp", java.time.Instant.now().toString());
            
            if (socket != null && isConnected.get()) {
                socket.emit("sale_completed", message);
                logger.debug("Emitted sale_completed: {}", saleId);
            }
        } catch (Exception e) {
            logger.warn("Failed to emit sale_completed", e);
        }
    }

    /**
     * Emit stock alert to other devices.
     */
    public void emitStockAlert(String productId, String productName, int currentStock, int reorderLevel) {
        try {
            JSONObject message = new JSONObject();
            message.put("productId", productId);
            message.put("productName", productName);
            message.put("currentStock", currentStock);
            message.put("reorderLevel", reorderLevel);
            message.put("alertType", currentStock == 0 ? "OUT_OF_STOCK" : "LOW_STOCK");
            
            if (socket != null && isConnected.get()) {
                socket.emit("stock_alert", message);
                logger.debug("Emitted stock_alert for product: {}", productId);
            }
        } catch (Exception e) {
            logger.warn("Failed to emit stock_alert", e);
        }
    }

    /**
     * Set connection status listener.
     */
    public void setConnectionStatusListener(Consumer<Boolean> listener) {
        this.connectionStatusListener = listener;
    }

    /**
     * Set error listener.
     */
    public void setErrorListener(Consumer<String> listener) {
        this.errorListener = listener;
    }

    private void notifyConnectionStatus(boolean connected) {
        if (isShuttingDown.get()) {
            return;
        }

        if (connectionStatusListener != null) {
            connectionStatusListener.accept(connected);
        }
    }

    private void notifyError(String error) {
        if (errorListener != null) {
            errorListener.accept(error);
        }
    }

    /**
     * Shutdown the WebSocket client and release resources.
     */
    public void shutdown() {
        if (!isShuttingDown.compareAndSet(false, true)) {
            logger.debug("WebSocketClient shutdown already in progress");
            return;
        }

        disconnect();
        scheduler.shutdown();
        try {
            if (!scheduler.awaitTermination(5, TimeUnit.SECONDS)) {
                scheduler.shutdownNow();
            }
        } catch (InterruptedException e) {
            scheduler.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }

    // === Inner classes for messages ===

    private static class AckMessage {
        private String messageId;

        public AckMessage(String messageId) {
            this.messageId = messageId;
        }
    }

    private static class HeartbeatMessage {
        private String deviceId;
        private Status status = new Status();

        public void setDeviceId(String deviceId) {
            this.deviceId = deviceId;
        }

        public void setBatteryLevel(int level) {
            this.status.batteryLevel = level;
        }

        public void setNetworkType(String type) {
            this.status.networkType = type;
        }

        public void setPendingSales(int count) {
            this.status.pendingSales = count;
        }

        private static class Status {
            int batteryLevel;
            String networkType;
            int pendingSales;
        }
    }

    private static class SaleCompletedMessage {
        private String saleId;
        private double total;
        private int itemCount;
        private String paymentMethod;
        private String cashierName;

        public void setSaleId(String saleId) {
            this.saleId = saleId;
        }

        public void setTotal(double total) {
            this.total = total;
        }

        public void setItemCount(int itemCount) {
            this.itemCount = itemCount;
        }

        public void setPaymentMethod(String paymentMethod) {
            this.paymentMethod = paymentMethod;
        }

        public void setCashierName(String cashierName) {
            this.cashierName = cashierName;
        }
    }

    private static class StockAlertMessage {
        private String productId;
        private String productName;
        private int currentStock;
        private int reorderLevel;
        private String alertType;

        public void setProductId(String productId) {
            this.productId = productId;
        }

        public void setProductName(String productName) {
            this.productName = productName;
        }

        public void setCurrentStock(int currentStock) {
            this.currentStock = currentStock;
        }

        public void setReorderLevel(int reorderLevel) {
            this.reorderLevel = reorderLevel;
        }

        public void setAlertType(String alertType) {
            this.alertType = alertType;
        }
    }
}
