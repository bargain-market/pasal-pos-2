package com.pos.ui;

import com.pos.model.Ad;
import com.pos.model.SaleItem;
import com.pos.sync.inbound.AdsInboundSync;
import javafx.animation.KeyFrame;
import javafx.animation.Timeline;
import javafx.application.Platform;
import javafx.beans.property.SimpleStringProperty;
import javafx.collections.ListChangeListener;
import javafx.collections.ObservableList;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Label;
import javafx.scene.control.Separator;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.media.Media;
import javafx.scene.media.MediaPlayer;
import javafx.scene.media.MediaView;
import javafx.scene.text.Font;
import javafx.scene.text.FontWeight;
import javafx.scene.text.TextAlignment;
import javafx.util.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.math.BigDecimal;
import java.text.NumberFormat;
import java.util.ArrayList;
import java.util.List;

/**
 * Customer Facing Display View
 * Shows the current transaction details to the customer on a secondary screen.
 * 
 * Ads are loaded from the local database (synced from backend via
 * AdsInboundSync).
 * Supports text-based, image-based, and video-based ads.
 */
public class CustomerDisplayView extends BorderPane {

    private static final Logger logger = LoggerFactory.getLogger(CustomerDisplayView.class);

    private final ObservableList<SaleItem> cartItems;
    private TableView<SaleItem> cartTable;
    private Label subtotalLabel;
    private Label discountLabel;
    private Label taxLabel;
    private Label totalLabel;
    private Label statusLabel;
    private Label itemCountLabel;
    private VBox splitAdContainer;
    private StackPane fullScreenAdContainer;

    private Timeline adRotationTimeline;
    private Timeline adRefreshTimeline;
    private int currentAdIndex = 0;
    private List<Ad> ads = new ArrayList<>();
    
    // Video playback
    private MediaPlayer currentMediaPlayer;
    private MediaView currentMediaView;

    // Fallback ads when no ads are synced from backend
    private static final List<Ad> DEFAULT_ADS = createDefaultAds();

    private static List<Ad> createDefaultAds() {
        List<Ad> defaults = new ArrayList<>();
        defaults.add(createDefaultAd("Daily Specials", "Get 20% off on all fresh produce!", "#e3f2fd", "#1565c0"));
        defaults.add(createDefaultAd("New Arrivals", "Check out our new summer collection.", "#e8f5e9", "#2e7d32"));
        defaults.add(createDefaultAd("Loyalty Program", "Join today and earn points on every purchase.", "#fff3e0",
                "#ef6c00"));
        defaults.add(createDefaultAd("Gift Cards", "The perfect gift for your loved ones.", "#f3e5f5", "#7b1fa2"));
        return defaults;
    }

    private static Ad createDefaultAd(String title, String subtitle, String backgroundColor, String textColor) {
        Ad ad = new Ad();
        ad.setTitle(title);
        ad.setSubtitle(subtitle);
        ad.setBackgroundColor(backgroundColor);
        ad.setTextColor(textColor);
        ad.setActive(true);
        return ad;
    }

    public CustomerDisplayView(ObservableList<SaleItem> cartItems) {
        this.cartItems = cartItems;
        setStyle("-fx-background-color: #f5f7fa;");

        // Top: Welcome Message / Status
        statusLabel = new Label("Welcome to Our Store");
        statusLabel.setFont(Font.font("System", FontWeight.BOLD, 24));
        statusLabel.setTextFill(javafx.scene.paint.Color.web("#2a5298"));

        // Item Count Label
        itemCountLabel = new Label("0 Items");
        itemCountLabel.setFont(Font.font("System", FontWeight.BOLD, 20));
        itemCountLabel.setTextFill(javafx.scene.paint.Color.web("#1976d2"));
        itemCountLabel.setVisible(false);

        HBox topBox = new HBox(20, statusLabel, itemCountLabel);
        topBox.setAlignment(Pos.CENTER);
        topBox.setPadding(new Insets(20));
        topBox.setStyle("-fx-background-color: white; -fx-effect: dropshadow(gaussian, rgba(0,0,0,0.1), 0, 2, 5, 0);");
        setTop(topBox);

        // Initialize views FIRST before loading ads (ads may trigger updates)
        createSplitViewComponents();
        createFullScreenAdComponents();

        // Load ads from database (synced from backend) - AFTER components are
        // initialized
        loadAdsFromDatabase();

        // Initial state
        updateLayoutState();

        // Listen for changes
        cartItems.addListener((ListChangeListener<SaleItem>) c -> {
            Platform.runLater(() -> {
                updateLayoutState();
                updateItemCount();
            });
        });

        // Start ad rotation (if not already started by loadAdsFromDatabase)
        if (adRotationTimeline == null && !ads.isEmpty()) {
            startAdRotation();
        }
        startAdRefreshTimer();
    }

    /**
     * Load ads from the local database (synced from backend).
     * Falls back to default ads if no ads are available.
     * 
     * <p>
     * Ads are loaded in display order, which determines the rotation sequence.
     * The rotation will cycle through ads in the order they appear in the list,
     * which matches the display_order set in the backend management interface.
     */
    private void loadAdsFromDatabase() {
        try {
            List<Ad> dbAds = AdsInboundSync.getInstance().getActiveAds();
            if (dbAds != null && !dbAds.isEmpty()) {
                ads = dbAds;
                logger.info("Loaded {} ads from database (display order: {} to {})",
                        ads.size(),
                        ads.get(0).getDisplayOrder(),
                        ads.get(ads.size() - 1).getDisplayOrder());
            } else {
                ads = new ArrayList<>(DEFAULT_ADS);
                logger.info("No ads in database, using {} default ads", ads.size());
            }
        } catch (Exception e) {
            logger.warn("Error loading ads from database, using defaults", e);
            ads = new ArrayList<>(DEFAULT_ADS);
        }
        currentAdIndex = 0;
        // Restart rotation with new ads
        if (adRotationTimeline != null) {
            adRotationTimeline.stop();
            adRotationTimeline = null;
        }
        if (!ads.isEmpty()) {
            startAdRotation();
        }
    }

    /**
     * Start a timer to periodically refresh ads from the database.
     * This ensures new ads synced from backend are picked up.
     */
    private void startAdRefreshTimer() {
        if (adRefreshTimeline != null) {
            adRefreshTimeline.stop();
        }

        // Refresh ads every 60 seconds
        adRefreshTimeline = new Timeline(new KeyFrame(Duration.seconds(60), e -> {
            Platform.runLater(() -> {
                int previousCount = ads.size();
                loadAdsFromDatabase();
                if (ads.size() != previousCount) {
                    logger.info("Ads refreshed: {} -> {} ads", previousCount, ads.size());
                }
            });
        }));
        adRefreshTimeline.setCycleCount(Timeline.INDEFINITE);
        adRefreshTimeline.play();
    }

    private void updateLayoutState() {
        if (cartItems.isEmpty()) {
            setCenter(fullScreenAdContainer);
            statusLabel.setText("Welcome to Our Store");
            if (itemCountLabel != null) {
                itemCountLabel.setVisible(false);
            }
        } else {
            setCenter(createSplitView());
            statusLabel.setText("Current Transaction");
            updateItemCount();
            if (itemCountLabel != null) {
                itemCountLabel.setVisible(true);
            }
        }
    }

    private void updateItemCount() {
        if (itemCountLabel == null)
            return;

        int totalItems = cartItems.stream()
                .mapToInt(SaleItem::getQuantity)
                .sum();
        int uniqueItems = cartItems.size();

        if (totalItems == 1) {
            itemCountLabel.setText("1 Item");
        } else if (uniqueItems == 1) {
            itemCountLabel.setText(totalItems + " Items");
        } else {
            itemCountLabel.setText(totalItems + " Items (" + uniqueItems + " different)");
        }
    }

    private void createFullScreenAdComponents() {
        fullScreenAdContainer = new StackPane();
        fullScreenAdContainer.setStyle("-fx-background-color: white;");
        updateFullScreenAd();
    }

    private void updateFullScreenAd() {
        if (ads.isEmpty() || fullScreenAdContainer == null)
            return;

        Ad ad = ads.get(currentAdIndex);

        // Stop any currently playing video
        stopCurrentVideo();
        
        fullScreenAdContainer.getChildren().clear();

        if (ad.isVideoAd()) {
            // Video-based ad
            try {
                createVideoAdContent(ad, fullScreenAdContainer, true);
            } catch (Exception e) {
                logger.warn("Failed to load video ad: {}", ad.getVideoUrl(), e);
                // Fallback to text display
                createTextAdContent(ad, fullScreenAdContainer, true);
            }
        } else if (ad.isImageAd()) {
            // Image-based ad
            try {
                ImageView imageView = new ImageView(new Image(ad.getImageUrl(), true));
                imageView.setPreserveRatio(true);
                imageView.fitWidthProperty().bind(fullScreenAdContainer.widthProperty());
                imageView.fitHeightProperty().bind(fullScreenAdContainer.heightProperty());

                StackPane imageContainer = new StackPane(imageView);
                imageContainer.setStyle("-fx-background-color: #000;");

                // Add title overlay at the bottom
                if (ad.getTitle() != null && !ad.getTitle().isEmpty()) {
                    VBox overlay = new VBox(5);
                    overlay.setAlignment(Pos.BOTTOM_CENTER);
                    overlay.setPadding(new Insets(20));
                    overlay.setStyle("-fx-background-color: linear-gradient(to top, rgba(0,0,0,0.7), transparent);");

                    Label title = new Label(ad.getTitle());
                    title.setFont(Font.font("System", FontWeight.BOLD, 36));
                    title.setTextFill(javafx.scene.paint.Color.WHITE);

                    if (ad.getSubtitle() != null && !ad.getSubtitle().isEmpty()) {
                        Label subtitle = new Label(ad.getSubtitle());
                        subtitle.setFont(Font.font("System", 20));
                        subtitle.setTextFill(javafx.scene.paint.Color.WHITE);
                        subtitle.setWrapText(true);
                        subtitle.setTextAlignment(TextAlignment.CENTER);
                        overlay.getChildren().addAll(title, subtitle);
                    } else {
                        overlay.getChildren().add(title);
                    }

                    StackPane.setAlignment(overlay, Pos.BOTTOM_CENTER);
                    imageContainer.getChildren().add(overlay);
                }

                fullScreenAdContainer.getChildren().add(imageContainer);

            } catch (Exception e) {
                logger.warn("Failed to load ad image: {}", ad.getImageUrl(), e);
                // Fallback to text display
                createTextAdContent(ad, fullScreenAdContainer, true);
            }
        } else {
            // Text-based ad
            createTextAdContent(ad, fullScreenAdContainer, true);
        }
    }
    
    /**
     * Create video ad content
     */
    private void createVideoAdContent(Ad ad, StackPane container, boolean isFullScreen) {
        try {
            String videoUrl = ad.getVideoUrl();
            logger.info("Loading video ad: {}", videoUrl);
            
            Media media = new Media(videoUrl);
            currentMediaPlayer = new MediaPlayer(media);
            currentMediaView = new MediaView(currentMediaPlayer);
            
            // Preserve aspect ratio and fit to container
            currentMediaView.setPreserveRatio(true);
            if (isFullScreen) {
                currentMediaView.fitWidthProperty().bind(container.widthProperty());
                currentMediaView.fitHeightProperty().bind(container.heightProperty());
            } else {
                currentMediaView.setFitWidth(280);
                currentMediaView.setFitHeight(200);
            }
            
            // Set up video container
            StackPane videoContainer = new StackPane(currentMediaView);
            videoContainer.setStyle("-fx-background-color: #000;");
            
            // Add title overlay at the bottom if present
            if (ad.getTitle() != null && !ad.getTitle().isEmpty()) {
                VBox overlay = new VBox(5);
                overlay.setAlignment(Pos.BOTTOM_CENTER);
                overlay.setPadding(new Insets(isFullScreen ? 20 : 10));
                overlay.setStyle("-fx-background-color: linear-gradient(to top, rgba(0,0,0,0.7), transparent);");
                overlay.setMouseTransparent(true);

                Label title = new Label(ad.getTitle());
                title.setFont(Font.font("System", FontWeight.BOLD, isFullScreen ? 36 : 16));
                title.setTextFill(javafx.scene.paint.Color.WHITE);

                if (ad.getSubtitle() != null && !ad.getSubtitle().isEmpty()) {
                    Label subtitle = new Label(ad.getSubtitle());
                    subtitle.setFont(Font.font("System", isFullScreen ? 20 : 12));
                    subtitle.setTextFill(javafx.scene.paint.Color.WHITE);
                    subtitle.setWrapText(true);
                    subtitle.setTextAlignment(TextAlignment.CENTER);
                    overlay.getChildren().addAll(title, subtitle);
                } else {
                    overlay.getChildren().add(title);
                }

                StackPane.setAlignment(overlay, Pos.BOTTOM_CENTER);
                videoContainer.getChildren().add(overlay);
            }
            
            container.getChildren().add(videoContainer);
            
            // Handle video end - will rotate to next ad
            currentMediaPlayer.setOnEndOfMedia(() -> {
                logger.debug("Video ad ended: {}", ad.getTitle());
                // Video naturally ended, rotation handled by timer or manual advance
            });
            
            // Handle errors
            currentMediaPlayer.setOnError(() -> {
                logger.error("Error playing video: {}", currentMediaPlayer.getError());
                // Fallback to text display
                Platform.runLater(() -> {
                    container.getChildren().clear();
                    createTextAdContent(ad, container, isFullScreen);
                });
            });
            
            // Start playing
            currentMediaPlayer.setAutoPlay(true);
            currentMediaPlayer.setCycleCount(MediaPlayer.INDEFINITE); // Loop until rotation
            currentMediaPlayer.play();
            
            logger.info("Video ad started playing: {}", ad.getTitle());
            
        } catch (Exception e) {
            logger.error("Error creating video ad content: {}", e.getMessage(), e);
            throw e;
        }
    }
    
    /**
     * Stop currently playing video
     */
    private void stopCurrentVideo() {
        if (currentMediaPlayer != null) {
            try {
                currentMediaPlayer.stop();
                currentMediaPlayer.dispose();
            } catch (Exception e) {
                logger.warn("Error stopping video player: {}", e.getMessage());
            }
            currentMediaPlayer = null;
            currentMediaView = null;
        }
    }

    /**
     * Create text-based ad content with formatting support
     */
    private void createTextAdContent(Ad ad, StackPane container, boolean isFullScreen) {
        VBox slideContent = new VBox(isFullScreen ? 20 : 10);

        // Apply alignment
        String alignment = ad.getTextAlignment() != null ? ad.getTextAlignment() : "center";
        Pos alignmentPos = alignment.equals("left") ? Pos.CENTER_LEFT
                : alignment.equals("right") ? Pos.CENTER_RIGHT
                        : Pos.CENTER;
        slideContent.setAlignment(alignmentPos);
        slideContent.setPadding(new Insets(isFullScreen ? 40 : 20));

        String bgColor = ad.getBackgroundColor() != null ? ad.getBackgroundColor() : "#e3f2fd";
        String txtColor = ad.getTextColor() != null ? ad.getTextColor() : "#1565c0";

        slideContent.setStyle("-fx-background-color: " + bgColor + ";");

        // Title with formatting
        Label title = new Label(ad.getTitle());
        double titleFontSize = getFontSize(ad.getTitleFontSize(), isFullScreen, true);
        FontWeight titleWeight = ad.isTitleBold() ? FontWeight.BOLD : FontWeight.NORMAL;
        title.setFont(Font.font("System", titleWeight, titleFontSize));
        title.setTextFill(javafx.scene.paint.Color.web(txtColor));
        title.setWrapText(true);
        title.setTextAlignment(getTextAlignment(alignment));

        slideContent.getChildren().add(title);

        // Subtitle with formatting
        if (ad.getSubtitle() != null && !ad.getSubtitle().isEmpty()) {
            Label subtitle = new Label(ad.getSubtitle());
            double subtitleFontSize = getFontSize(ad.getSubtitleFontSize(), isFullScreen, false);
            subtitle.setFont(Font.font("System", subtitleFontSize));
            subtitle.setTextFill(javafx.scene.paint.Color.web(txtColor));
            subtitle.setWrapText(true);
            subtitle.setTextAlignment(getTextAlignment(alignment));
            slideContent.getChildren().add(subtitle);
        }

        container.getChildren().clear();
        container.getChildren().add(slideContent);
    }

    /**
     * Get font size based on size string and screen type
     */
    private double getFontSize(String sizeStr, boolean isFullScreen, boolean isTitle) {
        String size = sizeStr != null ? sizeStr : (isTitle ? "large" : "medium");

        if (isFullScreen) {
            // Full screen sizes
            switch (size) {
                case "small":
                    return isTitle ? 32 : 18;
                case "medium":
                    return isTitle ? 40 : 22;
                case "large":
                    return isTitle ? 48 : 26;
                case "xlarge":
                    return isTitle ? 56 : 30;
                default:
                    return isTitle ? 48 : 24;
            }
        } else {
            // Split view sizes
            switch (size) {
                case "small":
                    return isTitle ? 18 : 12;
                case "medium":
                    return isTitle ? 22 : 16;
                case "large":
                    return isTitle ? 24 : 18;
                case "xlarge":
                    return isTitle ? 28 : 20;
                default:
                    return isTitle ? 24 : 16;
            }
        }
    }

    /**
     * Convert alignment string to TextAlignment enum
     */
    private TextAlignment getTextAlignment(String alignment) {
        if (alignment == null)
            return TextAlignment.CENTER;
        switch (alignment.toLowerCase()) {
            case "left":
                return TextAlignment.LEFT;
            case "right":
                return TextAlignment.RIGHT;
            default:
                return TextAlignment.CENTER;
        }
    }

    private void createSplitViewComponents() {
        // Cart Table
        cartTable = new TableView<>();
        cartTable.setItems(cartItems);
        Label placeholder = new Label("Start scanning items...");
        placeholder.setFont(Font.font("System", 20));
        cartTable.setPlaceholder(placeholder);
        cartTable.setStyle(
                "-fx-font-size: 20px; -fx-selection-bar: transparent; -fx-selection-bar-non-focused: transparent; " +
                        "-fx-table-cell-border-color: transparent;");
        cartTable.setFocusTraversable(false); // Read-only look
        // Set fixed row height via CSS instead of row factory to avoid layout loops
        cartTable.setFixedCellSize(50);

        setupCartColumns();

        // Totals Labels
        subtotalLabel = createTotalLabel("Subtotal: $0.00", 20);
        discountLabel = createTotalLabel("", 20);
        discountLabel.setTextFill(javafx.scene.paint.Color.web("#d32f2f"));
        discountLabel.setVisible(false);
        discountLabel.setManaged(false);
        taxLabel = createTotalLabel("Tax: $0.00", 20);
        totalLabel = createTotalLabel("Total: $0.00", 32);
        totalLabel.setTextFill(javafx.scene.paint.Color.web("#2a5298"));

        // Split Ad Container (Smaller version)
        splitAdContainer = new VBox();
        splitAdContainer.setPrefWidth(300);
        splitAdContainer.setMinWidth(300);
        splitAdContainer.setStyle(
                "-fx-background-color: white; -fx-background-radius: 5; -fx-effect: dropshadow(gaussian, rgba(0,0,0,0.1), 0, 0, 5, 0);");
        splitAdContainer.setAlignment(Pos.CENTER);
        updateSplitAd();
    }

    private void updateSplitAd() {
        if (ads.isEmpty() || splitAdContainer == null)
            return;

        Ad ad = ads.get(currentAdIndex);

        splitAdContainer.getChildren().clear();

        String bgColor = ad.getBackgroundColor() != null ? ad.getBackgroundColor() : "#e3f2fd";
        String txtColor = ad.getTextColor() != null ? ad.getTextColor() : "#1565c0";

        if (ad.isVideoAd()) {
            // Video-based ad in split view - show thumbnail with play icon
            try {
                VBox videoContainer = new VBox(10);
                videoContainer.setAlignment(Pos.CENTER);
                videoContainer.setPadding(new Insets(10));
                videoContainer.setStyle("-fx-background-color: #000; -fx-background-radius: 5;");
                
                // Create a smaller video player for split view (no sound)
                Media media = new Media(ad.getVideoUrl());
                MediaPlayer splitMediaPlayer = new MediaPlayer(media);
                MediaView splitMediaView = new MediaView(splitMediaPlayer);
                
                splitMediaView.setPreserveRatio(true);
                splitMediaView.setFitWidth(280);
                splitMediaView.setFitHeight(180);
                
                splitMediaPlayer.setMute(true); // Mute in split view
                splitMediaPlayer.setCycleCount(MediaPlayer.INDEFINITE);
                splitMediaPlayer.setAutoPlay(true);
                
                videoContainer.getChildren().add(splitMediaView);

                // Add title below video if present
                if (ad.getTitle() != null && !ad.getTitle().isEmpty()) {
                    Label title = new Label(ad.getTitle());
                    title.setFont(Font.font("System", FontWeight.BOLD, 14));
                    title.setTextFill(javafx.scene.paint.Color.WHITE);
                    title.setWrapText(true);
                    title.setTextAlignment(TextAlignment.CENTER);
                    videoContainer.getChildren().add(title);
                }

                splitAdContainer.setStyle("-fx-background-color: #000; -fx-background-radius: 5; " +
                        "-fx-effect: dropshadow(gaussian, rgba(0,0,0,0.1), 0, 0, 5, 0);");
                splitAdContainer.getChildren().add(videoContainer);

            } catch (Exception e) {
                logger.warn("Failed to load split video ad: {}", ad.getVideoUrl(), e);
                // Fallback to text display
                createSplitTextAd(ad, bgColor, txtColor);
            }
        } else if (ad.isImageAd()) {
            // Image-based ad in split view
            try {
                ImageView imageView = new ImageView(new Image(ad.getImageUrl(), true));
                imageView.setPreserveRatio(true);
                imageView.setFitWidth(280);
                imageView.setFitHeight(200);

                VBox imageContainer = new VBox(10);
                imageContainer.setAlignment(Pos.CENTER);
                imageContainer.setPadding(new Insets(10));
                imageContainer.setStyle("-fx-background-color: #000; -fx-background-radius: 5;");
                imageContainer.getChildren().add(imageView);

                // Add title below image if present
                if (ad.getTitle() != null && !ad.getTitle().isEmpty()) {
                    Label title = new Label(ad.getTitle());
                    title.setFont(Font.font("System", FontWeight.BOLD, 16));
                    title.setTextFill(javafx.scene.paint.Color.WHITE);
                    title.setWrapText(true);
                    title.setTextAlignment(TextAlignment.CENTER);
                    imageContainer.getChildren().add(title);
                }

                splitAdContainer.setStyle("-fx-background-color: #000; -fx-background-radius: 5; " +
                        "-fx-effect: dropshadow(gaussian, rgba(0,0,0,0.1), 0, 0, 5, 0);");
                splitAdContainer.getChildren().add(imageContainer);

            } catch (Exception e) {
                logger.warn("Failed to load split ad image: {}", ad.getImageUrl(), e);
                // Fallback to text display
                createSplitTextAd(ad, bgColor, txtColor);
            }
        } else {
            // Text-based ad
            createSplitTextAd(ad, bgColor, txtColor);
        }
    }

    private void createSplitTextAd(Ad ad, String bgColor, String txtColor) {
        splitAdContainer.setStyle("-fx-background-color: " + bgColor +
                "; -fx-background-radius: 5; -fx-effect: dropshadow(gaussian, rgba(0,0,0,0.1), 0, 0, 5, 0);");

        // Apply alignment
        String alignment = ad.getTextAlignment() != null ? ad.getTextAlignment() : "center";
        Pos alignmentPos = alignment.equals("left") ? Pos.CENTER_LEFT
                : alignment.equals("right") ? Pos.CENTER_RIGHT
                        : Pos.CENTER;

        Label title = new Label(ad.getTitle());
        double titleFontSize = getFontSize(ad.getTitleFontSize(), false, true);
        FontWeight titleWeight = ad.isTitleBold() ? FontWeight.BOLD : FontWeight.NORMAL;
        title.setFont(Font.font("System", titleWeight, titleFontSize));
        title.setTextFill(javafx.scene.paint.Color.web(txtColor));
        title.setWrapText(true);
        title.setTextAlignment(getTextAlignment(alignment));

        VBox content = new VBox(10);
        content.setAlignment(alignmentPos);
        content.setPadding(new Insets(20));
        content.getChildren().add(title);

        if (ad.getSubtitle() != null && !ad.getSubtitle().isEmpty()) {
            Label subtitle = new Label(ad.getSubtitle());
            double subtitleFontSize = getFontSize(ad.getSubtitleFontSize(), false, false);
            subtitle.setFont(Font.font("System", subtitleFontSize));
            subtitle.setTextFill(javafx.scene.paint.Color.web(txtColor));
            subtitle.setWrapText(true);
            subtitle.setTextAlignment(getTextAlignment(alignment));
            content.getChildren().add(subtitle);
        }

        splitAdContainer.getChildren().add(content);
    }

    private Node createSplitView() {
        HBox centerBox = new HBox(20);
        centerBox.setPadding(new Insets(20));
        centerBox.setAlignment(Pos.CENTER);

        // Cart Section
        VBox cartBox = new VBox(10);
        HBox.setHgrow(cartBox, Priority.ALWAYS);
        VBox.setVgrow(cartTable, Priority.ALWAYS);

        // Totals Section
        VBox totalsBox = new VBox(10);
        totalsBox.setPadding(new Insets(20));
        totalsBox.setStyle(
                "-fx-background-color: white; -fx-background-radius: 5; -fx-border-color: #ddd; -fx-border-width: 2; " +
                        "-fx-effect: dropshadow(gaussian, rgba(0,0,0,0.15), 0, 2, 5, 0);");

        totalsBox.getChildren().clear(); // Clear before adding to avoid duplicates if reused (though we create new
                                         // Node)
        totalsBox.getChildren().addAll(subtotalLabel, discountLabel, taxLabel, new Separator(), totalLabel);

        cartBox.getChildren().clear();
        cartBox.getChildren().addAll(cartTable, totalsBox);

        centerBox.getChildren().addAll(cartBox, splitAdContainer);
        return centerBox;
    }

    /**
     * Start ad rotation timer.
     * 
     * <p>
     * Ads rotate using their individual displayDuration values (in seconds).
     * Each ad can have a custom display duration (3-30 seconds, default 5).
     * The rotation cycles through ads in display order (set in backend management).
     * Lower display_order values appear first in the rotation cycle.
     */
    private void startAdRotation() {
        if (adRotationTimeline != null) {
            adRotationTimeline.stop();
        }

        if (ads.isEmpty()) {
            return;
        }

        // Start by displaying the first ad, then schedule rotation
        updateFullScreenAd();
        updateSplitAd();

        // Schedule first rotation based on current ad's duration
        Ad currentAd = ads.get(currentAdIndex);
        int duration = currentAd.getDisplayDuration();
        if (duration < 3)
            duration = 3;
        if (duration > 30)
            duration = 30;

        adRotationTimeline = new Timeline(new KeyFrame(Duration.seconds(duration), e -> {
            rotateToNextAd();
        }));
        adRotationTimeline.setCycleCount(1);
        adRotationTimeline.play();
    }

    /**
     * Rotate to the next ad and schedule the next rotation based on the new current
     * ad's duration.
     */
    private void rotateToNextAd() {
        if (ads.isEmpty()) {
            return;
        }

        // Move to next ad first
        currentAdIndex = (currentAdIndex + 1) % ads.size();
        updateFullScreenAd();
        updateSplitAd();

        // Get the new current ad's duration for the next rotation
        Ad currentAd = ads.get(currentAdIndex);
        int duration = currentAd.getDisplayDuration();
        // Clamp duration to valid range (3-30 seconds)
        if (duration < 3)
            duration = 3;
        if (duration > 30)
            duration = 30;

        // Schedule next rotation based on the current ad's duration
        adRotationTimeline = new Timeline(new KeyFrame(Duration.seconds(duration), e -> {
            rotateToNextAd();
        }));
        adRotationTimeline.setCycleCount(1); // Run once, then rotateToNextAd schedules the next
        adRotationTimeline.play();
    }

    public void stop() {
        if (adRotationTimeline != null) {
            adRotationTimeline.stop();
        }
        if (adRefreshTimeline != null) {
            adRefreshTimeline.stop();
        }
        // Stop any playing video
        stopCurrentVideo();
    }

    /**
     * Force refresh ads from database.
     * Called when ads are synced from backend.
     */
    public void refreshAds() {
        Platform.runLater(() -> {
            loadAdsFromDatabase();
            updateFullScreenAd();
            updateSplitAd();
        });
    }

    private void setupCartColumns() {
        // Item Name
        TableColumn<SaleItem, String> nameCol = new TableColumn<>("Item");
        nameCol.setCellValueFactory(data -> data.getValue().productNameProperty());
        nameCol.setPrefWidth(300);
        nameCol.setStyle("-fx-font-size: 20px; -fx-font-weight: bold;");
        nameCol.setCellFactory(column -> new javafx.scene.control.TableCell<SaleItem, String>() {
            {
                setFont(Font.font("System", FontWeight.BOLD, 20));
                setStyle("-fx-padding: 10px;");
            }

            @Override
            protected void updateItem(String item, boolean empty) {
                super.updateItem(item, empty);
                if (empty || item == null) {
                    setText(null);
                } else {
                    setText(item);
                }
            }
        });

        // Quantity - Make it more prominent
        TableColumn<SaleItem, String> qtyCol = new TableColumn<>("Quantity");
        qtyCol.setCellValueFactory(
                data -> new SimpleStringProperty("× " + String.valueOf(data.getValue().getQuantity())));
        qtyCol.setPrefWidth(120);
        qtyCol.setStyle("-fx-font-size: 22px; -fx-font-weight: bold;");
        qtyCol.setCellFactory(column -> new javafx.scene.control.TableCell<SaleItem, String>() {
            {
                setFont(Font.font("System", FontWeight.BOLD, 22));
                setTextFill(javafx.scene.paint.Color.web("#1976d2"));
                setAlignment(Pos.CENTER);
                setStyle("-fx-padding: 10px; -fx-background-color: #e3f2fd; -fx-background-radius: 5;");
            }

            @Override
            protected void updateItem(String item, boolean empty) {
                super.updateItem(item, empty);
                if (empty || item == null) {
                    setText(null);
                } else {
                    setText(item);
                }
            }
        });

        // Price (Unit)
        TableColumn<SaleItem, String> priceCol = new TableColumn<>("Unit Price");
        priceCol.setCellValueFactory(data -> {
            SaleItem item = data.getValue();
            // Use item.getPrice() to respect manual price overrides
            BigDecimal cashPrice = item.getPrice("CASH");
            BigDecimal listPrice = item.getPrice("CARD");
            NumberFormat fmt = NumberFormat.getCurrencyInstance();

            if (listPrice != null && listPrice.compareTo(cashPrice) != 0) {
                return new SimpleStringProperty(fmt.format(cashPrice) + " / " + fmt.format(listPrice));
            } else {
                return new SimpleStringProperty(fmt.format(cashPrice));
            }
        });
        priceCol.setPrefWidth(150);
        priceCol.setStyle("-fx-font-size: 18px;");
        priceCol.setCellFactory(column -> new javafx.scene.control.TableCell<SaleItem, String>() {
            {
                setFont(Font.font("System", 18));
                setAlignment(Pos.CENTER_RIGHT);
                setStyle("-fx-padding: 10px;");
            }

            @Override
            protected void updateItem(String item, boolean empty) {
                super.updateItem(item, empty);
                if (empty || item == null) {
                    setText(null);
                } else {
                    setText(item);
                }
            }
        });

        // Total Line Price
        TableColumn<SaleItem, String> totalCol = new TableColumn<>("Line Total");
        totalCol.setCellValueFactory(data -> data.getValue().totalProperty());
        totalCol.setPrefWidth(150);
        totalCol.setStyle("-fx-font-size: 20px; -fx-font-weight: bold;");
        totalCol.setCellFactory(column -> new javafx.scene.control.TableCell<SaleItem, String>() {
            {
                setFont(Font.font("System", FontWeight.BOLD, 20));
                setTextFill(javafx.scene.paint.Color.web("#2a5298"));
                setAlignment(Pos.CENTER_RIGHT);
                setStyle("-fx-padding: 10px;");
            }

            @Override
            protected void updateItem(String item, boolean empty) {
                super.updateItem(item, empty);
                if (empty || item == null) {
                    setText(null);
                } else {
                    setText(item);
                }
            }
        });

        cartTable.getColumns().addAll(nameCol, qtyCol, priceCol, totalCol);
        cartTable.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY);
    }

    private Label createTotalLabel(String text, double fontSize) {
        Label label = new Label(text);
        label.setFont(Font.font("System", FontWeight.BOLD, fontSize));
        label.setMaxWidth(Double.MAX_VALUE);
        label.setAlignment(Pos.CENTER_RIGHT);
        return label;
    }

    // Public methods to update state

    public void updateTotals(BigDecimal subtotal, BigDecimal discount, BigDecimal tax, BigDecimal total) {
        NumberFormat fmt = NumberFormat.getCurrencyInstance();
        subtotalLabel.setText("Subtotal: " + fmt.format(subtotal));

        if (discount.compareTo(BigDecimal.ZERO) > 0) {
            discountLabel.setText("Discount: -" + fmt.format(discount));
            discountLabel.setVisible(true);
            discountLabel.setManaged(true);
        } else {
            discountLabel.setVisible(false);
            discountLabel.setManaged(false);
        }

        taxLabel.setText("Tax: " + fmt.format(tax));
        totalLabel.setText("Total: " + fmt.format(total));

        // Update item count when totals are updated
        updateItemCount();
    }

    public void setWelcomeMessage(String message) {
        statusLabel.setText(message);
    }
}
