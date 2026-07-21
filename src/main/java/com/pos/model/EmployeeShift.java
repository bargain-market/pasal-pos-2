package com.pos.model;

import javafx.beans.property.*;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.Duration;
import java.time.format.DateTimeFormatter;

/**
 * Employee shift model for tracking clock-in/clock-out times
 */
public class EmployeeShift {
    private final StringProperty id;
    private final StringProperty employeeId;
    private final StringProperty employeeName;
    private final StringProperty storeId;
    private final ObjectProperty<LocalDateTime> clockInAt;
    private final ObjectProperty<LocalDateTime> clockOutAt;
    private final StringProperty status; // ACTIVE, COMPLETED
    private final ObjectProperty<BigDecimal> totalHours;
    private final StringProperty notes;
    private final BooleanProperty synced;
    private final ObjectProperty<LocalDateTime> createdAt;

    public static final String STATUS_ACTIVE = "ACTIVE";
    public static final String STATUS_COMPLETED = "COMPLETED";

    private static final DateTimeFormatter DISPLAY_FORMATTER = DateTimeFormatter.ofPattern("MMM d, yyyy h:mm a");
    private static final DateTimeFormatter TIME_FORMATTER = DateTimeFormatter.ofPattern("h:mm a");

    public EmployeeShift() {
        this(null, null, null, null, null, null, STATUS_ACTIVE, null, null, false, null);
    }

    public EmployeeShift(String id, String employeeId, String employeeName, String storeId,
                         LocalDateTime clockInAt, LocalDateTime clockOutAt, String status,
                         BigDecimal totalHours, String notes, boolean synced, LocalDateTime createdAt) {
        this.id = new SimpleStringProperty(id);
        this.employeeId = new SimpleStringProperty(employeeId);
        this.employeeName = new SimpleStringProperty(employeeName);
        this.storeId = new SimpleStringProperty(storeId);
        this.clockInAt = new SimpleObjectProperty<>(clockInAt);
        this.clockOutAt = new SimpleObjectProperty<>(clockOutAt);
        this.status = new SimpleStringProperty(status != null ? status : STATUS_ACTIVE);
        this.totalHours = new SimpleObjectProperty<>(totalHours);
        this.notes = new SimpleStringProperty(notes);
        this.synced = new SimpleBooleanProperty(synced);
        this.createdAt = new SimpleObjectProperty<>(createdAt);
    }

    // Getters
    public String getId() { return id.get(); }
    public String getEmployeeId() { return employeeId.get(); }
    public String getEmployeeName() { return employeeName.get(); }
    public String getStoreId() { return storeId.get(); }
    public LocalDateTime getClockInAt() { return clockInAt.get(); }
    public LocalDateTime getClockOutAt() { return clockOutAt.get(); }
    public String getStatus() { return status.get(); }
    public BigDecimal getTotalHours() { return totalHours.get(); }
    public String getNotes() { return notes.get(); }
    public boolean isSynced() { return synced.get(); }
    public LocalDateTime getCreatedAt() { return createdAt.get(); }

    // Property getters for JavaFX binding
    public StringProperty idProperty() { return id; }
    public StringProperty employeeIdProperty() { return employeeId; }
    public StringProperty employeeNameProperty() { return employeeName; }
    public StringProperty storeIdProperty() { return storeId; }
    public ObjectProperty<LocalDateTime> clockInAtProperty() { return clockInAt; }
    public ObjectProperty<LocalDateTime> clockOutAtProperty() { return clockOutAt; }
    public StringProperty statusProperty() { return status; }
    public ObjectProperty<BigDecimal> totalHoursProperty() { return totalHours; }
    public StringProperty notesProperty() { return notes; }
    public BooleanProperty syncedProperty() { return synced; }
    public ObjectProperty<LocalDateTime> createdAtProperty() { return createdAt; }

    // Setters
    public void setId(String id) { this.id.set(id); }
    public void setEmployeeId(String employeeId) { this.employeeId.set(employeeId); }
    public void setEmployeeName(String employeeName) { this.employeeName.set(employeeName); }
    public void setStoreId(String storeId) { this.storeId.set(storeId); }
    public void setClockInAt(LocalDateTime clockInAt) { this.clockInAt.set(clockInAt); }
    public void setClockOutAt(LocalDateTime clockOutAt) { this.clockOutAt.set(clockOutAt); }
    public void setStatus(String status) { this.status.set(status); }
    public void setTotalHours(BigDecimal totalHours) { this.totalHours.set(totalHours); }
    public void setNotes(String notes) { this.notes.set(notes); }
    public void setSynced(boolean synced) { this.synced.set(synced); }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt.set(createdAt); }

    /**
     * Check if this shift is currently active (not clocked out)
     */
    public boolean isActive() {
        return STATUS_ACTIVE.equals(getStatus());
    }

    /**
     * Calculate the duration of this shift
     * @return Duration of the shift, or duration since clock-in if still active
     */
    public Duration getDuration() {
        LocalDateTime start = getClockInAt();
        LocalDateTime end = getClockOutAt();
        
        if (start == null) {
            return Duration.ZERO;
        }
        
        if (end == null) {
            // Still active, calculate from now
            end = LocalDateTime.now();
        }
        
        return Duration.between(start, end);
    }

    /**
     * Get formatted duration string (e.g., "8h 30m")
     */
    public String getFormattedDuration() {
        Duration duration = getDuration();
        long hours = duration.toHours();
        long minutes = duration.toMinutesPart();
        
        if (hours > 0) {
            return String.format("%dh %dm", hours, minutes);
        } else {
            return String.format("%dm", minutes);
        }
    }

    /**
     * Get formatted clock-in time
     */
    public String getFormattedClockIn() {
        LocalDateTime clockIn = getClockInAt();
        return clockIn != null ? clockIn.format(DISPLAY_FORMATTER) : "-";
    }

    /**
     * Get formatted clock-out time
     */
    public String getFormattedClockOut() {
        LocalDateTime clockOut = getClockOutAt();
        if (clockOut != null) {
            return clockOut.format(DISPLAY_FORMATTER);
        }
        return isActive() ? "WORKING..." : "-";
    }

    /**
     * Get formatted time only for clock-in
     */
    public String getClockInTime() {
        LocalDateTime clockIn = getClockInAt();
        return clockIn != null ? clockIn.format(TIME_FORMATTER) : "-";
    }

    /**
     * Get formatted time only for clock-out
     */
    public String getClockOutTime() {
        LocalDateTime clockOut = getClockOutAt();
        return clockOut != null ? clockOut.format(TIME_FORMATTER) : "-";
    }

    /**
     * Calculate total hours as BigDecimal (for storage)
     */
    public BigDecimal calculateTotalHours() {
        Duration duration = getDuration();
        double hours = duration.toMinutes() / 60.0;
        return BigDecimal.valueOf(hours).setScale(2, java.math.RoundingMode.HALF_UP);
    }

    @Override
    public String toString() {
        return "EmployeeShift{" +
                "id='" + getId() + '\'' +
                ", employeeName='" + getEmployeeName() + '\'' +
                ", clockInAt=" + getClockInAt() +
                ", clockOutAt=" + getClockOutAt() +
                ", status='" + getStatus() + '\'' +
                ", duration=" + getFormattedDuration() +
                '}';
    }
}
