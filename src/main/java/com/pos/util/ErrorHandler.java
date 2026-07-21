package com.pos.util;

import com.pos.api.ApiClient;
import com.pos.ui.components.ToastNotification;
import javafx.scene.control.Alert;
import javafx.stage.Window;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.SQLException;

/**
 * Centralized error handling utility
 * Provides consistent error handling and user-friendly error messages
 */
public class ErrorHandler {
    private static final Logger logger = LoggerFactory.getLogger(ErrorHandler.class);

    /**
     * Handle an exception and show appropriate error message to user
     * 
     * @param e           The exception to handle
     * @param context     Context description for logging (e.g., "Login failed")
     * @param ownerWindow Window to show toast/alert relative to (can be null)
     * @param useToast    If true, shows toast notification; if false, shows alert
     *                    dialog
     */
    public static void handleError(Exception e, String context, Window ownerWindow, boolean useToast) {
        String userMessage = getUserFriendlyMessage(e);
        String logMessage = context + ": " + e.getMessage();

        // Log the error
        if (e instanceof ApiClient.ApiException) {
            ApiClient.ApiException apiEx = (ApiClient.ApiException) e;
            logger.error("{} [Status: {}, Code: {}]", logMessage, apiEx.getStatusCode(), apiEx.getErrorCode(), e);
        } else {
            logger.error(logMessage, e);
        }

        // Skip showing UI notification if message is null (suppressed internal error)
        if (userMessage == null) {
            logger.debug("Suppressed internal error notification: {}", e.getMessage());
            return;
        }

        // Show to user
        if (useToast) {
            ToastNotification.showError(userMessage, ownerWindow);
        } else {
            showErrorAlert(userMessage, context, ownerWindow);
        }
    }

    /**
     * Handle an exception with toast notification (non-blocking)
     */
    public static void handleErrorWithToast(Exception e, String context, Window ownerWindow) {
        handleError(e, context, ownerWindow, true);
    }

    /**
     * Handle an exception with alert dialog (blocking)
     */
    public static void handleErrorWithAlert(Exception e, String context, Window ownerWindow) {
        handleError(e, context, ownerWindow, false);
    }

    /**
     * Handle an exception and show toast, then return user-friendly message
     * Useful for cases where you need the message string
     */
    public static String handleErrorAndGetMessage(Exception e, String context, Window ownerWindow) {
        String userMessage = getUserFriendlyMessage(e);
        handleError(e, context, ownerWindow, true);
        return userMessage;
    }

    /**
     * Show a success message
     */
    public static void showSuccess(String message, Window ownerWindow, boolean useToast) {
        if (useToast) {
            ToastNotification.showSuccess(message, ownerWindow);
        } else {
            showSuccessAlert(message, ownerWindow);
        }
    }

    /**
     * Show a warning message
     */
    public static void showWarning(String message, Window ownerWindow, boolean useToast) {
        if (useToast) {
            ToastNotification.showWarning(message, ownerWindow);
        } else {
            showWarningAlert(message, ownerWindow);
        }
    }

    /**
     * Show an info message
     */
    public static void showInfo(String message, Window ownerWindow, boolean useToast) {
        if (useToast) {
            ToastNotification.showInfo(message, ownerWindow);
        } else {
            showInfoAlert(message, ownerWindow);
        }
    }

    /**
     * Get user-friendly error message from exception
     */
    private static String getUserFriendlyMessage(Exception e) {
        if (e instanceof ApiClient.ApiException) {
            ApiClient.ApiException apiEx = (ApiClient.ApiException) e;
            return getUserFriendlyApiMessage(apiEx);
        } else if (e instanceof SQLException) {
            return getUserFriendlySqlMessage((SQLException) e);
        } else if (e instanceof IllegalArgumentException) {
            return e.getMessage() != null ? e.getMessage() : "Invalid input provided";
        } else if (e instanceof IllegalStateException) {
            return e.getMessage() != null ? e.getMessage() : "Operation not allowed in current state";
        } else if (e instanceof NullPointerException) {
            return "Required information is missing";
        } else {
            String message = e.getMessage();
            if (message != null && !message.isEmpty()) {
                // Check for common error patterns
                if (message.contains("database") || message.contains("Database")) {
                    return "Database error. Please ensure the database is initialized.";
                } else if (message.contains("connection") || message.contains("Connection")) {
                    return "Connection error. Please check your network connection.";
                } else if (message.contains("timeout") || message.contains("Timeout")) {
                    return "Request timed out. Please try again.";
                }
                return message;
            }
            return "An unexpected error occurred. Please try again.";
        }
    }

    /**
     * Get user-friendly message for API exceptions
     */
    private static String getUserFriendlyApiMessage(ApiClient.ApiException e) {
        int statusCode = e.getStatusCode();
        String errorCode = e.getErrorCode();
        String message = e.getMessage();

        // Handle specific error codes
        if (errorCode != null) {
            switch (errorCode) {
                case "POS_AUTH_001":
                    return "Invalid API key. Please check your device registration.";
                case "POS_AUTH_002":
                    return "Device not registered. Please register device first.";
                case "POS_BUS_001":
                    return "Insufficient stock available.";
                case "POS_BUS_002":
                    // Suppress "Product not found" from being shown to users
                    // This can occur during sync operations and is handled internally
                    return null;
                case "POS_BUS_003":
                    return "Sale already exists.";
                case "POS_BUS_004":
                    return "Invalid sale data.";
                case "POS_BUS_005":
                    return "Product is out of stock.";
            }
        }

        // Handle HTTP status codes
        switch (statusCode) {
            case 400:
                return message != null && !message.isEmpty() ? message : "Invalid request. Please check your input.";
            case 401:
                return "Authentication failed. Please check your credentials.";
            case 403:
                return "You don't have permission to perform this action.";
            case 404:
                return "Resource not found.";
            case 409:
                return "Conflict: This resource already exists or has been modified.";
            case 422:
                return message != null && !message.isEmpty() ? message : "Validation error. Please check your input.";
            case 500:
                return "Server error. Please try again later.";
            case 503:
                return "Service unavailable. Please try again later.";
            default:
                if (message != null && !message.isEmpty()) {
                    return message;
                }
                return "An error occurred while communicating with the server.";
        }
    }

    /**
     * Get user-friendly message for SQL exceptions
     */
    private static String getUserFriendlySqlMessage(SQLException e) {
        String message = e.getMessage();
        String sqlState = e.getSQLState();

        // Suppress internal system messages that shouldn't be shown to users
        // These are typically caught and handled at higher levels in the stack
        if (message != null) {
            // Suppress "Product not found" errors during stock operations
            // These can occur during WebSocket sync or sale stock deduction when products
            // haven't synced yet - the system handles these gracefully without user
            // intervention
            if (message.equalsIgnoreCase("Product not found")) {
                return null;
            }
        }

        // Handle common SQL states
        if (sqlState != null) {
            switch (sqlState) {
                case "23505": // Unique constraint violation
                    return "This record already exists.";
                case "23503": // Foreign key constraint violation
                    return "Cannot delete this record because it is referenced by other records.";
                case "23502": // Not null constraint violation
                    return "Required fields are missing.";
                case "42S02": // Table doesn't exist
                    return "Database table not found. Please ensure the database is initialized.";
                case "42S22": // Column doesn't exist
                    return "Database structure error. Please check database configuration.";
            }
        }

        // Check message for common patterns
        if (message != null) {
            if (com.pos.database.DatabaseManager.isCorruptionError(e)) {
                return "The local database file is corrupted. Restart Pasal POS 2 to restore automatically from backup.";
            } else if (message.contains("UNIQUE constraint") || message.contains("duplicate")) {
                return "This record already exists.";
            } else if (message.contains("FOREIGN KEY constraint") || message.contains("referenced")) {
                return "Cannot delete this record because it is referenced by other records.";
            } else if (message.contains("NOT NULL constraint") || message.contains("null")) {
                return "Required fields are missing.";
            } else if (message.contains("Table") && message.contains("doesn't exist")) {
                return "Database table not found. Please ensure the database is initialized.";
            } else if (message.contains("Connection") || message.contains("connection")) {
                return "Database connection error. Please check database configuration.";
            }
        }

        return "Database error occurred. Please try again.";
    }

    /**
     * Show error alert dialog
     */
    private static void showErrorAlert(String message, String title, Window ownerWindow) {
        javafx.application.Platform.runLater(() -> {
            Alert alert = new Alert(Alert.AlertType.ERROR);
            alert.setTitle(title != null ? title : "Error");
            alert.setHeaderText(null);
            alert.setContentText(message);
            DialogHelper.setAlertOwner(alert, ownerWindow);
            alert.showAndWait();
        });
    }

    /**
     * Show success alert dialog
     */
    private static void showSuccessAlert(String message, Window ownerWindow) {
        javafx.application.Platform.runLater(() -> {
            Alert alert = new Alert(Alert.AlertType.INFORMATION);
            alert.setTitle("Success");
            alert.setHeaderText(null);
            alert.setContentText(message);
            DialogHelper.setAlertOwner(alert, ownerWindow);
            alert.showAndWait();
        });
    }

    /**
     * Show warning alert dialog
     */
    private static void showWarningAlert(String message, Window ownerWindow) {
        javafx.application.Platform.runLater(() -> {
            Alert alert = new Alert(Alert.AlertType.WARNING);
            alert.setTitle("Warning");
            alert.setHeaderText(null);
            alert.setContentText(message);
            DialogHelper.setAlertOwner(alert, ownerWindow);
            alert.showAndWait();
        });
    }

    /**
     * Show info alert dialog
     */
    private static void showInfoAlert(String message, Window ownerWindow) {
        javafx.application.Platform.runLater(() -> {
            Alert alert = new Alert(Alert.AlertType.INFORMATION);
            alert.setTitle("Information");
            alert.setHeaderText(null);
            alert.setContentText(message);
            DialogHelper.setAlertOwner(alert, ownerWindow);
            alert.showAndWait();
        });
    }
}
