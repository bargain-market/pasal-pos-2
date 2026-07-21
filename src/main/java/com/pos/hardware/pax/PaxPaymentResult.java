package com.pos.hardware.pax;

import com.pos.api.dto.SaleSubmission;

/**
 * Result of a PAX terminal card authorization attempt.
 */
public class PaxPaymentResult {

    public final boolean approved;
    public final String message;
    public final String authCode;
    public final String transactionRef;
    public final String cardLastFour;
    public final String cardType;
    public final String cardNetwork;
    public final String entryMode;
    public final String hostReference;
    public final String invoiceRef;
    public final String resultCode;

    public PaxPaymentResult(
            boolean approved,
            String message,
            String authCode,
            String transactionRef,
            String cardLastFour,
            String cardType,
            String cardNetwork,
            String entryMode,
            String hostReference,
            String invoiceRef,
            String resultCode) {
        this.approved = approved;
        this.message = message;
        this.authCode = authCode;
        this.transactionRef = transactionRef;
        this.cardLastFour = cardLastFour;
        this.cardType = cardType;
        this.cardNetwork = cardNetwork;
        this.entryMode = entryMode;
        this.hostReference = hostReference;
        this.invoiceRef = invoiceRef;
        this.resultCode = resultCode;
    }

    public static PaxPaymentResult approved(
            String message,
            String authCode,
            String transactionRef,
            String cardLastFour,
            String cardType,
            String cardNetwork,
            String entryMode,
            String hostReference,
            String invoiceRef) {
        return new PaxPaymentResult(
                true,
                message,
                authCode,
                transactionRef,
                cardLastFour,
                cardType,
                cardNetwork,
                entryMode,
                hostReference,
                invoiceRef,
                "OK");
    }

    public static PaxPaymentResult declined(String message, String resultCode) {
        return new PaxPaymentResult(
                false,
                message,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                resultCode);
    }

    public SaleSubmission.PaymentDetails toPaymentDetails() {
        if (!approved) {
            return null;
        }
        SaleSubmission.PaymentDetails details = new SaleSubmission.PaymentDetails();
        details.cardType = cardType;
        details.cardLastFour = cardLastFour;
        details.cardNetwork = cardNetwork;
        details.transactionRef = transactionRef != null ? transactionRef : hostReference;
        details.authCode = authCode;
        details.entryMode = entryMode;
        details.paymentProcessor = "PAX";
        details.approvalStatus = "APPROVED";
        return details;
    }
}
