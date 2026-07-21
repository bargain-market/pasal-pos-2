package com.pos.service;

import com.pos.config.ConfigManager;
import com.pos.model.SaleItem;
import com.pos.ui.CustomerDisplayView;
import javafx.application.Platform;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.geometry.Rectangle2D;
import javafx.scene.Scene;
import javafx.stage.Screen;
import javafx.stage.Stage;
import javafx.stage.StageStyle;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.math.BigDecimal;
import java.util.List;

/**
 * Service to manage the Customer Facing Display (CFD).
 * Handles detection of secondary screens and updates the customer view.
 */
public class CustomerDisplayService {
    private static final Logger logger = LoggerFactory.getLogger(CustomerDisplayService.class);
    private static CustomerDisplayService instance;

    private Stage customerStage;
    private CustomerDisplayView customerView;
    private ObservableList<SaleItem> sharedCartItems;
    private boolean isEnabled = false;

    private CustomerDisplayService() {
    }

    public static synchronized CustomerDisplayService getInstance() {
        if (instance == null) {
            instance = new CustomerDisplayService();
        }
        return instance;
    }

    /**
     * Initialize the customer display on a secondary screen if available.
     * 
     * @param sharedCartItems The observable list of items from the main
     *                        SalesScreen.
     */
    public void initialize(ObservableList<SaleItem> sharedCartItems) {
        this.sharedCartItems = sharedCartItems;

        // Check if customer display is enabled in configuration
        ConfigManager config = ConfigManager.getInstance();
        boolean displayEnabled = config.getBooleanProperty("customer.display.enabled", false);
        
        if (!displayEnabled) {
            logger.info("Customer display is disabled in configuration (customer.display.enabled=false)");
            // Close any existing customer display
            if (customerStage != null) {
                Platform.runLater(() -> {
                    if (customerView != null) {
                        customerView.stop();
                    }
                    customerStage.close();
                    customerStage = null;
                    customerView = null;
                    isEnabled = false;
                });
            }
            return;
        }
        
        List<Screen> screens = Screen.getScreens();
        Screen primaryScreen = Screen.getPrimary();

        if (screens.size() < 2) {
            logger.info("No secondary screen detected. Customer display disabled.");
            // Ensure any existing customer stage is closed
            if (customerStage != null) {
                Platform.runLater(() -> {
                    if (customerView != null) {
                        customerView.stop();
                    }
                    customerStage.close();
                    customerStage = null;
                    customerView = null;
                    isEnabled = false;
                });
            }
            return;
        }

        // Find a secondary screen that is different from the primary screen
        Screen secondaryScreen = null;
        for (Screen screen : screens) {
            if (!screen.equals(primaryScreen)) {
                secondaryScreen = screen;
                break;
            }
        }

        // If no secondary screen found (shouldn't happen if screens.size() >= 2, but be
        // safe)
        if (secondaryScreen == null) {
            logger.warn("No secondary screen found that differs from primary. Customer display disabled.");
            // Ensure any existing customer stage is closed
            if (customerStage != null) {
                Platform.runLater(() -> {
                    if (customerView != null) {
                        customerView.stop();
                    }
                    customerStage.close();
                    customerStage = null;
                    customerView = null;
                    isEnabled = false;
                });
            }
            return;
        }

        Rectangle2D primaryBounds = primaryScreen.getVisualBounds();
        Rectangle2D secondaryBounds = secondaryScreen.getVisualBounds();

        // Verify the secondary screen is actually different (not overlapping with
        // primary)
        if (primaryBounds.equals(secondaryBounds)) {
            logger.warn("Secondary screen bounds match primary screen. Customer display disabled.");
            // Ensure any existing customer stage is closed
            if (customerStage != null) {
                Platform.runLater(() -> {
                    if (customerView != null) {
                        customerView.stop();
                    }
                    customerStage.close();
                    customerStage = null;
                    customerView = null;
                    isEnabled = false;
                });
            }
            return;
        }

        final Screen finalSecondaryScreen = secondaryScreen;
        final Rectangle2D bounds = secondaryBounds;

        Platform.runLater(() -> {
            try {
                // Close any existing stage first
                if (customerStage != null && customerStage.isShowing()) {
                    if (customerView != null) {
                        customerView.stop();
                    }
                    customerStage.close();
                }

                customerStage = new Stage();
                customerStage.initStyle(StageStyle.UNDECORATED);
                customerStage.setTitle("Customer Display");

                // Set the stage to appear only on the secondary screen
                customerStage.setX(bounds.getMinX());
                customerStage.setY(bounds.getMinY());
                customerStage.setWidth(bounds.getWidth());
                customerStage.setHeight(bounds.getHeight());

                // Ensure the stage doesn't appear on primary screen
                // Set it to the secondary screen explicitly
                customerStage.setAlwaysOnTop(true);

                customerView = new CustomerDisplayView(sharedCartItems);
                Scene scene = new Scene(customerView, bounds.getWidth(), bounds.getHeight());

                // Apply same CSS if available
                try {
                    scene.getStylesheets().add(getClass().getResource("/styles/application.css").toExternalForm());
                } catch (Exception e) {
                    logger.warn("Could not load styles for customer display");
                }

                customerStage.setScene(scene);

                // Verify the stage is positioned on the secondary screen, not primary
                double stageX = customerStage.getX();
                double stageY = customerStage.getY();
                if (primaryBounds.contains(stageX, stageY)) {
                    logger.warn("Customer stage would appear on primary screen. Adjusting position.");
                    // Force position to secondary screen
                    customerStage.setX(bounds.getMinX());
                    customerStage.setY(bounds.getMinY());
                }

                customerStage.show();

                // Double-check after showing that it's on the secondary screen
                Platform.runLater(() -> {
                    Screen currentScreen = Screen.getScreens().stream()
                            .filter(screen -> {
                                Rectangle2D screenBounds = screen.getVisualBounds();
                                double centerX = customerStage.getX() + customerStage.getWidth() / 2;
                                double centerY = customerStage.getY() + customerStage.getHeight() / 2;
                                return screenBounds.contains(centerX, centerY);
                            })
                            .findFirst()
                            .orElse(null);

                    if (currentScreen != null && currentScreen.equals(Screen.getPrimary())) {
                        logger.error("Customer display appeared on primary screen! Closing it.");
                        customerStage.close();
                        customerStage = null;
                        customerView = null;
                        isEnabled = false;
                        return;
                    }
                });

                isEnabled = true;
                logger.info("Customer display initialized on secondary screen: {} (bounds: {})",
                        finalSecondaryScreen, bounds);

            } catch (Exception e) {
                logger.error("Failed to initialize customer display", e);
                isEnabled = false;
                // Clean up on error
                if (customerStage != null) {
                    customerStage.close();
                    customerStage = null;
                    customerView = null;
                }
            }
        });
    }

    /**
     * Update the totals shown on the customer display.
     */
    public void updateTotals(BigDecimal subtotal, BigDecimal discount, BigDecimal tax, BigDecimal total) {
        if (!isEnabled || customerView == null)
            return;

        Platform.runLater(() -> {
            customerView.updateTotals(subtotal, discount, tax, total);
        });
    }

    /**
     * Clear the display or show welcome message.
     */
    public void resetDisplay() {
        if (!isEnabled || customerView == null)
            return;

        Platform.runLater(() -> {
            customerView.setWelcomeMessage("Welcome to Our Store");
            customerView.updateTotals(BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO);
        });
    }

    /**
     * Close the customer display window.
     */
    public void shutdown() {
        if (customerStage != null) {
            Platform.runLater(() -> {
                if (customerView != null) {
                    customerView.stop();
                }
                customerStage.close();
                customerStage = null;
                customerView = null;
                isEnabled = false;
            });
        }
    }

    public boolean isEnabled() {
        return isEnabled;
    }

    public boolean isConfiguredEnabled() {
        return ConfigManager.getInstance().getBooleanProperty("customer.display.enabled", false);
    }

    /**
     * Enable or disable the customer display from settings and apply immediately.
     */
    public void setDisplayEnabled(boolean enabled) {
        ConfigManager.getInstance().setProperty("customer.display.enabled", String.valueOf(enabled));

        if (!enabled) {
            shutdown();
            return;
        }

        ObservableList<SaleItem> items = sharedCartItems != null
                ? sharedCartItems
                : FXCollections.observableArrayList();
        initialize(items);
    }

    public boolean isSecondaryScreenAvailable() {
        return Screen.getScreens().size() >= 2;
    }
}
