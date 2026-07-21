package com.pos.model;

import javafx.beans.property.*;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import java.math.BigDecimal;
import java.util.List;

/**
 * VendorPayout model for tracking payments owed to vendors based on sales.
 * A payout represents an aggregate of all sales for a vendor within a specific period.
 */
public class VendorPayout {
    
    /**
     * Payout status enumeration
     */
    public enum PayoutStatus {
        PENDING("Pending"),      // Calculated but not yet paid
        APPROVED("Approved"),    // Approved for payment
        PAID("Paid"),           // Payment has been made
        CANCELLED("Cancelled"); // Payout was cancelled

        private final String displayName;

        PayoutStatus(String displayName) {
            this.displayName = displayName;
        }

        public String getDisplayName() {
            return displayName;
        }
    }

    private final StringProperty id;
    private final StringProperty vendorId;
    private final StringProperty vendorName;
    private final StringProperty periodStart;
    private final StringProperty periodEnd;
    private final ObjectProperty<BigDecimal> totalSales;
    private final ObjectProperty<BigDecimal> totalCost;
    private final ObjectProperty<BigDecimal> totalPayout;
    private final ObjectProperty<BigDecimal> commissionRate;
    private final IntegerProperty itemCount;
    private final IntegerProperty transactionCount;
    private final ObjectProperty<PayoutStatus> status;
    private final StringProperty paidAt;
    private final StringProperty paidBy;
    private final StringProperty paymentMethod;
    private final StringProperty paymentReference;
    private final ObjectProperty<BigDecimal> amountPaid;
    private final StringProperty chequeNumber;
    private final StringProperty notes;
    private final StringProperty createdAt;
    private final StringProperty updatedAt;
    private final BooleanProperty synced;
    private final StringProperty shiftId;
    
    // Line items for this payout
    private final ObservableList<VendorPayoutItem> items;

    public VendorPayout() {
        this(null, null, null);
    }

    public VendorPayout(String id, String vendorId, String vendorName) {
        this.id = new SimpleStringProperty(id);
        this.vendorId = new SimpleStringProperty(vendorId);
        this.vendorName = new SimpleStringProperty(vendorName);
        this.periodStart = new SimpleStringProperty();
        this.periodEnd = new SimpleStringProperty();
        this.totalSales = new SimpleObjectProperty<>(BigDecimal.ZERO);
        this.totalCost = new SimpleObjectProperty<>(BigDecimal.ZERO);
        this.totalPayout = new SimpleObjectProperty<>(BigDecimal.ZERO);
        this.commissionRate = new SimpleObjectProperty<>(BigDecimal.ZERO);
        this.itemCount = new SimpleIntegerProperty(0);
        this.transactionCount = new SimpleIntegerProperty(0);
        this.status = new SimpleObjectProperty<>(PayoutStatus.PENDING);
        this.paidAt = new SimpleStringProperty();
        this.paidBy = new SimpleStringProperty();
        this.paymentMethod = new SimpleStringProperty();
        this.paymentReference = new SimpleStringProperty();
        this.amountPaid = new SimpleObjectProperty<>(BigDecimal.ZERO);
        this.chequeNumber = new SimpleStringProperty();
        this.notes = new SimpleStringProperty();
        this.createdAt = new SimpleStringProperty();
        this.updatedAt = new SimpleStringProperty();
        this.synced = new SimpleBooleanProperty(false);
        this.shiftId = new SimpleStringProperty();
        this.items = FXCollections.observableArrayList();
    }

    // ID
    public String getId() { return id.get(); }
    public void setId(String id) { this.id.set(id); }
    public StringProperty idProperty() { return id; }

    // Vendor ID
    public String getVendorId() { return vendorId.get(); }
    public void setVendorId(String vendorId) { this.vendorId.set(vendorId); }
    public StringProperty vendorIdProperty() { return vendorId; }

    // Vendor Name
    public String getVendorName() { return vendorName.get(); }
    public void setVendorName(String vendorName) { this.vendorName.set(vendorName); }
    public StringProperty vendorNameProperty() { return vendorName; }

    // Period Start
    public String getPeriodStart() { return periodStart.get(); }
    public void setPeriodStart(String periodStart) { this.periodStart.set(periodStart); }
    public StringProperty periodStartProperty() { return periodStart; }

    // Period End
    public String getPeriodEnd() { return periodEnd.get(); }
    public void setPeriodEnd(String periodEnd) { this.periodEnd.set(periodEnd); }
    public StringProperty periodEndProperty() { return periodEnd; }

    // Total Sales
    public BigDecimal getTotalSales() { return totalSales.get(); }
    public void setTotalSales(BigDecimal totalSales) { this.totalSales.set(totalSales); }
    public ObjectProperty<BigDecimal> totalSalesProperty() { return totalSales; }

    // Total Cost
    public BigDecimal getTotalCost() { return totalCost.get(); }
    public void setTotalCost(BigDecimal totalCost) { this.totalCost.set(totalCost); }
    public ObjectProperty<BigDecimal> totalCostProperty() { return totalCost; }

    // Total Payout
    public BigDecimal getTotalPayout() { return totalPayout.get(); }
    public void setTotalPayout(BigDecimal totalPayout) { this.totalPayout.set(totalPayout); }
    public ObjectProperty<BigDecimal> totalPayoutProperty() { return totalPayout; }

    // Commission Rate
    public BigDecimal getCommissionRate() { return commissionRate.get(); }
    public void setCommissionRate(BigDecimal rate) { this.commissionRate.set(rate); }
    public ObjectProperty<BigDecimal> commissionRateProperty() { return commissionRate; }

    // Item Count
    public int getItemCount() { return itemCount.get(); }
    public void setItemCount(int count) { this.itemCount.set(count); }
    public IntegerProperty itemCountProperty() { return itemCount; }

    // Transaction Count
    public int getTransactionCount() { return transactionCount.get(); }
    public void setTransactionCount(int count) { this.transactionCount.set(count); }
    public IntegerProperty transactionCountProperty() { return transactionCount; }

    // Status
    public PayoutStatus getStatus() { return status.get(); }
    public void setStatus(PayoutStatus status) { this.status.set(status); }
    public ObjectProperty<PayoutStatus> statusProperty() { return status; }

    // Paid At
    public String getPaidAt() { return paidAt.get(); }
    public void setPaidAt(String paidAt) { this.paidAt.set(paidAt); }
    public StringProperty paidAtProperty() { return paidAt; }

    // Paid By
    public String getPaidBy() { return paidBy.get(); }
    public void setPaidBy(String paidBy) { this.paidBy.set(paidBy); }
    public StringProperty paidByProperty() { return paidBy; }

    // Payment Method
    public String getPaymentMethod() { return paymentMethod.get(); }
    public void setPaymentMethod(String method) { this.paymentMethod.set(method); }
    public StringProperty paymentMethodProperty() { return paymentMethod; }

    // Payment Reference
    public String getPaymentReference() { return paymentReference.get(); }
    public void setPaymentReference(String reference) { this.paymentReference.set(reference); }
    public StringProperty paymentReferenceProperty() { return paymentReference; }

    // Amount Paid (actual cash/cheque amount given to vendor)
    public BigDecimal getAmountPaid() { return amountPaid.get(); }
    public void setAmountPaid(BigDecimal amount) { this.amountPaid.set(amount); }
    public ObjectProperty<BigDecimal> amountPaidProperty() { return amountPaid; }

    // Cheque Number (required if payment method is CHEQUE)
    public String getChequeNumber() { return chequeNumber.get(); }
    public void setChequeNumber(String number) { this.chequeNumber.set(number); }
    public StringProperty chequeNumberProperty() { return chequeNumber; }

    // Notes
    public String getNotes() { return notes.get(); }
    public void setNotes(String notes) { this.notes.set(notes); }
    public StringProperty notesProperty() { return notes; }

    // Created At
    public String getCreatedAt() { return createdAt.get(); }
    public void setCreatedAt(String createdAt) { this.createdAt.set(createdAt); }
    public StringProperty createdAtProperty() { return createdAt; }

    // Updated At
    public String getUpdatedAt() { return updatedAt.get(); }
    public void setUpdatedAt(String updatedAt) { this.updatedAt.set(updatedAt); }
    public StringProperty updatedAtProperty() { return updatedAt; }

    // Synced
    public boolean isSynced() { return synced.get(); }
    public void setSynced(boolean synced) { this.synced.set(synced); }
    public BooleanProperty syncedProperty() { return synced; }

    // Shift ID (the shift during which this payout was made)
    public String getShiftId() { return shiftId.get(); }
    public void setShiftId(String shiftId) { this.shiftId.set(shiftId); }
    public StringProperty shiftIdProperty() { return shiftId; }

    // Items
    public ObservableList<VendorPayoutItem> getItems() { return items; }
    public void setItems(List<VendorPayoutItem> items) {
        this.items.clear();
        if (items != null) {
            this.items.addAll(items);
        }
    }
    public void addItem(VendorPayoutItem item) {
        this.items.add(item);
    }

    /**
     * Calculate totals from items
     */
    public void calculateTotals() {
        BigDecimal sales = BigDecimal.ZERO;
        BigDecimal cost = BigDecimal.ZERO;
        BigDecimal payout = BigDecimal.ZERO;
        int count = 0;

        for (VendorPayoutItem item : items) {
            sales = sales.add(item.getSaleAmount() != null ? item.getSaleAmount() : BigDecimal.ZERO);
            cost = cost.add(item.getCostAmount() != null ? item.getCostAmount() : BigDecimal.ZERO);
            payout = payout.add(item.getPayoutAmount() != null ? item.getPayoutAmount() : BigDecimal.ZERO);
            count += item.getQuantity();
        }

        setTotalSales(sales);
        setTotalCost(cost);
        setTotalPayout(payout);
        setItemCount(count);
    }

    @Override
    public String toString() {
        return "VendorPayout{" +
                "id='" + getId() + '\'' +
                ", vendorName='" + getVendorName() + '\'' +
                ", periodStart='" + getPeriodStart() + '\'' +
                ", periodEnd='" + getPeriodEnd() + '\'' +
                ", totalPayout=" + getTotalPayout() +
                ", status=" + getStatus() +
                '}';
    }
}
