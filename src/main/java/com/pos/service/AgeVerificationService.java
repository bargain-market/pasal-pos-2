package com.pos.service;

import com.pos.database.DatabaseManager;
import com.pos.model.AgeVerification;
import com.pos.model.AgeVerification.VerificationMethod;
import com.pos.model.DriversLicenseData;
import com.pos.model.Product;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.*;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.Period;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Service for managing age verification for age-restricted products.
 * 
 * Age restriction is determined by the department's ageVerification field.
 * Once a customer is verified for a certain age (e.g., 21), they are
 * automatically cleared for lower age requirements (e.g., 18) within
 * the same sale session.
 */
public class AgeVerificationService {

    private static final Logger logger = LoggerFactory.getLogger(AgeVerificationService.class);
    private static AgeVerificationService instance;

    private final DatabaseManager dbManager;
    private final ProductManagementService productManagementService;
    private final PDF417ParserService pdf417Parser;

    // Session-level tracking: highest verified age for current sale
    private int sessionVerifiedAge = 0;
    private List<AgeVerification> pendingVerifications = new ArrayList<>();

    private AgeVerificationService() {
        this.dbManager = DatabaseManager.getInstance();
        this.productManagementService = ProductManagementService.getInstance();
        this.pdf417Parser = PDF417ParserService.getInstance();
    }

    public static synchronized AgeVerificationService getInstance() {
        if (instance == null) {
            instance = new AgeVerificationService();
        }
        return instance;
    }

    /**
     * Get the required age for a product based on its department
     * 
     * @param product The product to check
     * @return Required age (e.g., 21, 18) or 0 if no age restriction
     */
    public int getRequiredAge(Product product) {
        if (product == null) {
            return 0;
        }

        String departmentId = product.getDepartmentId();
        if (departmentId == null || departmentId.isEmpty()) {
            return 0;
        }

        try {
            ProductManagementService.Department department = productManagementService.getDepartmentById(departmentId);

            if (department != null && department.ageVerification != null) {
                return department.ageVerification;
            }
        } catch (SQLException e) {
            logger.error("Error getting department for age verification", e);
        }

        return 0;
    }

    /**
     * Check if age verification is required for a product
     * 
     * @param product The product to check
     * @return true if age verification is needed
     */
    public boolean isVerificationRequired(Product product) {
        int requiredAge = getRequiredAge(product);
        if (requiredAge <= 0) {
            return false;
        }

        // Check if already verified for this age level in current session
        return !hasVerificationForAge(requiredAge);
    }

    /**
     * Check if current session has verification for the specified age
     * 
     * @param requiredAge The minimum age required
     * @return true if session already has verification for this age or higher
     */
    public boolean hasVerificationForAge(int requiredAge) {
        return sessionVerifiedAge >= requiredAge;
    }

    /**
     * Calculate age from date of birth
     * 
     * @param dob Date of birth
     * @return Age in years
     */
    public int calculateAge(LocalDate dob) {
        if (dob == null) {
            return -1;
        }
        return Period.between(dob, LocalDate.now()).getYears();
    }

    /**
     * Verify age using scanned driver's license data
     * 
     * @param barcodeData  Raw PDF417 barcode data
     * @param requiredAge  Minimum age required
     * @param departmentId Department ID for the product
     * @param verifiedBy   Cashier name/ID
     * @return AgeVerification result, or null if verification failed
     */
    public AgeVerification verifyWithScan(String barcodeData, int requiredAge,
            String departmentId, String verifiedBy) {
        DriversLicenseData license = pdf417Parser.parse(barcodeData);

        if (license == null) {
            logger.warn("Failed to parse driver's license barcode");
            return null;
        }

        // Check if license is expired
        if (license.isExpired()) {
            logger.warn("Driver's license is expired");
            return null;
        }

        int customerAge = license.calculateAge();
        if (customerAge < 0) {
            logger.warn("Could not determine customer age from license");
            return null;
        }

        // Create verification record
        AgeVerification verification = new AgeVerification();
        verification.setId(UUID.randomUUID().toString());
        verification.setDepartmentId(departmentId);
        verification.setRequiredAge(requiredAge);
        verification.setCustomerDob(license.getDateOfBirth());
        verification.setCustomerAge(customerAge);
        verification.setIdLastFour(license.getLastFourOfLicense());
        verification.setIdExpiration(license.getExpirationDate());
        verification.setVerificationMethod(VerificationMethod.SCAN);
        verification.setVerifiedBy(verifiedBy);
        verification.setVerifiedAt(LocalDateTime.now());

        // Check if age requirement is met
        if (customerAge >= requiredAge) {
            // Update session verified age
            if (customerAge > sessionVerifiedAge) {
                sessionVerifiedAge = customerAge;
            }

            // Add to pending verifications (will be linked to sale later)
            pendingVerifications.add(verification);

            logger.info("Age verification passed via scan: customer age {} >= required {}",
                    customerAge, requiredAge);
            return verification;
        } else {
            logger.info("Age verification failed via scan: customer age {} < required {}",
                    customerAge, requiredAge);
            return verification; // Return the verification so UI can show the failure
        }
    }

    /**
     * Verify age using manual entry
     * 
     * @param customerDob  Customer's date of birth
     * @param idLastFour   Last 4 digits of ID number (optional)
     * @param idExpiration ID expiration date (optional)
     * @param requiredAge  Minimum age required
     * @param departmentId Department ID for the product
     * @param verifiedBy   Cashier name/ID
     * @return AgeVerification result
     */
    public AgeVerification verifyManually(LocalDate customerDob, String idLastFour,
            LocalDate idExpiration, int requiredAge,
            String departmentId, String verifiedBy) {
        if (customerDob == null) {
            logger.warn("Customer DOB is required for manual verification");
            return null;
        }

        int customerAge = calculateAge(customerDob);

        // Create verification record
        AgeVerification verification = new AgeVerification();
        verification.setId(UUID.randomUUID().toString());
        verification.setDepartmentId(departmentId);
        verification.setRequiredAge(requiredAge);
        verification.setCustomerDob(customerDob);
        verification.setCustomerAge(customerAge);
        verification.setIdLastFour(idLastFour);
        verification.setIdExpiration(idExpiration);
        verification.setVerificationMethod(VerificationMethod.MANUAL);
        verification.setVerifiedBy(verifiedBy);
        verification.setVerifiedAt(LocalDateTime.now());

        // Check if age requirement is met
        if (customerAge >= requiredAge) {
            // Update session verified age
            if (customerAge > sessionVerifiedAge) {
                sessionVerifiedAge = customerAge;
            }

            // Add to pending verifications
            pendingVerifications.add(verification);

            logger.info("Age verification passed via manual entry: customer age {} >= required {}",
                    customerAge, requiredAge);
        } else {
            logger.info("Age verification failed via manual entry: customer age {} < required {}",
                    customerAge, requiredAge);
        }

        return verification;
    }

    /**
     * Verify age using visual confirmation by cashier.
     * The cashier confirms they have visually checked the customer's ID.
     * No date of birth data is recorded - only that verification was performed.
     * 
     * @param requiredAge  Minimum age required
     * @param departmentId Department ID for the product
     * @param verifiedBy   Cashier name/ID
     * @return AgeVerification result (always passes - trust the cashier)
     */
    public AgeVerification verifyVisually(int requiredAge, String departmentId, String verifiedBy) {
        // Create verification record
        AgeVerification verification = new AgeVerification();
        verification.setId(UUID.randomUUID().toString());
        verification.setDepartmentId(departmentId);
        verification.setRequiredAge(requiredAge);
        verification.setCustomerDob(null);  // Not captured for visual verification
        verification.setCustomerAge(requiredAge);  // Assume meets requirement (cashier confirmed)
        verification.setIdLastFour(null);
        verification.setIdExpiration(null);
        verification.setVerificationMethod(VerificationMethod.VISUAL);
        verification.setVerifiedBy(verifiedBy);
        verification.setVerifiedAt(LocalDateTime.now());

        // Update session verified age - trust the cashier's visual confirmation
        if (requiredAge > sessionVerifiedAge) {
            sessionVerifiedAge = requiredAge;
        }

        // Add to pending verifications
        pendingVerifications.add(verification);

        logger.info("Age verification passed via visual confirmation: cashier {} confirmed customer meets age {} requirement",
                verifiedBy, requiredAge);

        return verification;
    }

    /**
     * Link all pending verifications to a completed sale
     * 
     * @param saleId The sale ID to link to
     */
    public void linkVerificationsToSale(String saleId) {
        if (pendingVerifications.isEmpty()) {
            return;
        }

        logger.info("Linking {} age verifications to sale {}", pendingVerifications.size(), saleId);

        for (AgeVerification verification : pendingVerifications) {
            verification.setSaleId(saleId);
            try {
                saveVerification(verification);
            } catch (SQLException e) {
                logger.error("Error saving age verification for sale {}", saleId, e);
            }
        }

        // Clear pending verifications after linking
        pendingVerifications.clear();
    }

    /**
     * Save a verification record to the database
     */
    private void saveVerification(AgeVerification verification) throws SQLException {
        try (Connection conn = dbManager.getConnection()) {

            String sql = """
                    INSERT INTO age_verifications
                    (id, sale_id, department_id, required_age, customer_dob, customer_age,
                     id_last_four, id_expiration, verification_method, verified_by, verified_at)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """;

            try (PreparedStatement stmt = conn.prepareStatement(sql)) {
                stmt.setString(1, verification.getId());
                stmt.setString(2, verification.getSaleId());
                stmt.setString(3, verification.getDepartmentId());
                stmt.setInt(4, verification.getRequiredAge());
                stmt.setDate(5, Date.valueOf(verification.getCustomerDob()));
                stmt.setInt(6, verification.getCustomerAge());
                stmt.setString(7, verification.getIdLastFour());
                stmt.setDate(8,
                        verification.getIdExpiration() != null ? Date.valueOf(verification.getIdExpiration()) : null);
                stmt.setString(9, verification.getVerificationMethod().name());
                stmt.setString(10, verification.getVerifiedBy());
                stmt.setTimestamp(11, Timestamp.valueOf(verification.getVerifiedAt()));

                stmt.executeUpdate();
                conn.commit();

                logger.debug("Saved age verification: {}", verification.getId());
            }
        }
    }

    /**
     * Get all verifications for a sale
     */
    public List<AgeVerification> getVerificationsForSale(String saleId) throws SQLException {
        List<AgeVerification> verifications = new ArrayList<>();
        try (Connection conn = dbManager.getConnection()) {

            String sql = "SELECT * FROM age_verifications WHERE sale_id = ?";

            try (PreparedStatement stmt = conn.prepareStatement(sql)) {
                stmt.setString(1, saleId);
                ResultSet rs = stmt.executeQuery();

                while (rs.next()) {
                    verifications.add(mapResultSetToVerification(rs));
                }
            }

            return verifications;
        }
    }

    /**
     * Map ResultSet to AgeVerification
     */
    private AgeVerification mapResultSetToVerification(ResultSet rs) throws SQLException {
        AgeVerification v = new AgeVerification();
        v.setId(rs.getString("id"));
        v.setSaleId(rs.getString("sale_id"));
        v.setDepartmentId(rs.getString("department_id"));
        v.setRequiredAge(rs.getInt("required_age"));

        Date dob = rs.getDate("customer_dob");
        v.setCustomerDob(dob != null ? dob.toLocalDate() : null);

        v.setCustomerAge(rs.getInt("customer_age"));
        v.setIdLastFour(rs.getString("id_last_four"));

        Date exp = rs.getDate("id_expiration");
        v.setIdExpiration(exp != null ? exp.toLocalDate() : null);

        String method = rs.getString("verification_method");
        v.setVerificationMethod(VerificationMethod.valueOf(method));

        v.setVerifiedBy(rs.getString("verified_by"));

        Timestamp verifiedAt = rs.getTimestamp("verified_at");
        v.setVerifiedAt(verifiedAt != null ? verifiedAt.toLocalDateTime() : null);

        return v;
    }

    /**
     * Clear session state (call when starting a new sale)
     */
    public void clearSession() {
        sessionVerifiedAge = 0;
        pendingVerifications.clear();
        logger.debug("Age verification session cleared");
    }

    /**
     * Get the current session's verified age
     */
    public int getSessionVerifiedAge() {
        return sessionVerifiedAge;
    }

    /**
     * Get count of pending verifications
     */
    public int getPendingVerificationCount() {
        return pendingVerifications.size();
    }

    /**
     * Parse a driver's license barcode (convenience method)
     */
    public DriversLicenseData parseDriversLicense(String barcodeData) {
        return pdf417Parser.parse(barcodeData);
    }

    /**
     * Check if barcode data might be a driver's license
     */
    public boolean mightBeDriversLicense(String barcodeData) {
        return pdf417Parser.mightBeDriversLicense(barcodeData);
    }
}
