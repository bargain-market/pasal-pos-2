package com.pos.service;

import com.pos.model.DriversLicenseData;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.HashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Service for parsing PDF417 barcodes from US driver's licenses.
 * 
 * Implements AAMVA (American Association of Motor Vehicle Administrators)
 * standard for driver's license barcodes. The PDF417 barcode contains
 * data elements identified by 3-character codes.
 * 
 * Common AAMVA Data Element IDs:
 * - DAA: Full Name (Last, First, Middle, Suffix)
 * - DCS: Last Name
 * - DAC/DCT: First Name
 * - DAD: Middle Name
 * - DBB: Date of Birth (MMDDYYYY or YYYYMMDD)
 * - DBA: Expiration Date
 * - DAQ: License Number
 * - DAJ: State
 * - DAG: Street Address
 * - DAI: City
 * - DAK: Zip Code
 * - DBC: Sex (1=Male, 2=Female)
 */
public class PDF417ParserService {

    private static final Logger logger = LoggerFactory.getLogger(PDF417ParserService.class);
    private static PDF417ParserService instance;

    // AAMVA Data Element IDs
    private static final String LAST_NAME = "DCS";
    private static final String FIRST_NAME = "DAC";
    private static final String FIRST_NAME_ALT = "DCT";
    private static final String MIDDLE_NAME = "DAD";
    private static final String FULL_NAME = "DAA";
    private static final String DOB = "DBB";
    private static final String EXPIRATION = "DBA";
    private static final String LICENSE_NUMBER = "DAQ";
    private static final String STATE = "DAJ";
    private static final String ADDRESS = "DAG";
    private static final String CITY = "DAI";
    private static final String ZIP = "DAK";
    private static final String SEX = "DBC";

    // Date formats used by different states (Ohio typically uses MMDDYYYY)
    private static final DateTimeFormatter[] DATE_FORMATS = {
            DateTimeFormatter.ofPattern("MMddyyyy"),
            DateTimeFormatter.ofPattern("yyyyMMdd"),
            DateTimeFormatter.ofPattern("MM/dd/yyyy"),
            DateTimeFormatter.ofPattern("MM-dd-yyyy"),
            DateTimeFormatter.ofPattern("yyyy/MM/dd"),
            DateTimeFormatter.ofPattern("yyyy-MM-dd"),
            DateTimeFormatter.ofPattern("ddMMyyyy"), // Some states use DDMMYYYY
            DateTimeFormatter.ofPattern("M/d/yyyy"), // Single digit month/day variant
            DateTimeFormatter.ofPattern("M-d-yyyy") // Single digit month/day variant
    };

    // Pattern to extract data elements (3-letter code followed by value until next
    // code or end)
    private static final Pattern DATA_ELEMENT_PATTERN = Pattern.compile(
            "(D[A-Z]{2})([^\\x1d\\x1e\\x0a\\x0d]*?)(?=D[A-Z]{2}|$|\\x1d|\\x1e|\\x0a|\\x0d)");

    // Alternative pattern for states like Ohio that may use different separators
    private static final Pattern DATA_ELEMENT_PATTERN_ALT = Pattern.compile(
            "(D[A-Z]{2})([^D]*?)(?=D[A-Z]{2}|$)");

    private PDF417ParserService() {
    }

    public static synchronized PDF417ParserService getInstance() {
        if (instance == null) {
            instance = new PDF417ParserService();
        }
        return instance;
    }

    /**
     * Parse a PDF417 barcode string from a driver's license
     * 
     * @param barcodeData The raw barcode data string
     * @return DriversLicenseData object with parsed information, or null if parsing
     *         fails
     */
    public DriversLicenseData parse(String barcodeData) {
        if (barcodeData == null || barcodeData.isEmpty()) {
            logger.warn("Empty barcode data provided");
            return null;
        }

        logger.debug("Parsing PDF417 barcode data: {} characters", barcodeData.length());

        try {
            // Check if this looks like an AAMVA barcode
            if (!isAAMVABarcode(barcodeData)) {
                logger.warn("Barcode does not appear to be AAMVA format");
                return null;
            }

            // Extract all data elements
            Map<String, String> elements = extractDataElements(barcodeData);

            if (elements.isEmpty()) {
                logger.warn("No data elements found in barcode, trying alternative extraction");
                // Try alternative extraction for Ohio and similar licenses
                elements = extractDataElementsAlternative(barcodeData);
                if (elements.isEmpty()) {
                    logger.warn("Alternative extraction also failed");
                    return null;
                }
            }

            // Log what we found for debugging
            logger.debug("Extracted {} data elements from barcode", elements.size());
            if (elements.containsKey(DOB)) {
                logger.debug("Found DOB field: {}", elements.get(DOB));
            }

            // Build the license data object
            DriversLicenseData license = new DriversLicenseData();
            license.setRawData(barcodeData);

            // Parse name
            parseName(elements, license);

            // Parse dates
            license.setDateOfBirth(parseDate(elements.get(DOB)));
            license.setExpirationDate(parseDate(elements.get(EXPIRATION)));

            // Parse other fields
            license.setLicenseNumber(cleanValue(elements.get(LICENSE_NUMBER)));
            license.setState(cleanValue(elements.get(STATE)));
            license.setAddress(cleanValue(elements.get(ADDRESS)));
            license.setCity(cleanValue(elements.get(CITY)));
            license.setZipCode(cleanZipCode(elements.get(ZIP)));
            license.setGender(parseGender(elements.get(SEX)));

            // Validate essential fields
            if (license.getDateOfBirth() == null) {
                logger.warn("Could not parse date of birth from barcode");
                return null;
            }

            if (license.getLicenseNumber() == null || license.getLicenseNumber().isEmpty()) {
                logger.warn("Could not parse license number from barcode");
                return null;
            }

            logger.info("Successfully parsed driver's license: age={}, state={}",
                    license.calculateAge(), license.getState());

            return license;

        } catch (Exception e) {
            logger.error("Error parsing PDF417 barcode", e);
            return null;
        }
    }

    /**
     * Check if the barcode data appears to be AAMVA format
     */
    private boolean isAAMVABarcode(String data) {
        // AAMVA barcodes typically start with @ or contain ANSI header
        // They also contain specific data element codes
        return data.contains("@") ||
                data.contains("ANSI") ||
                data.contains("DAQ") ||
                data.contains("DBB") ||
                (data.contains("DCS") && data.contains("DAC"));
    }

    /**
     * Extract all data elements from the barcode
     */
    private Map<String, String> extractDataElements(String data) {
        Map<String, String> elements = new HashMap<>();

        // Remove header/compliance indicator if present
        String cleanData = data;
        int headerEnd = data.indexOf("DL");
        if (headerEnd > 0 && headerEnd < 50) {
            cleanData = data.substring(headerEnd);
        }

        // Also try looking for ANSI header
        int ansiIndex = data.indexOf("ANSI");
        if (ansiIndex >= 0) {
            // Skip past the ANSI header (typically about 20 chars)
            int dlIndex = data.indexOf("DL", ansiIndex);
            if (dlIndex > ansiIndex) {
                cleanData = data.substring(dlIndex);
            }
        }

        // Extract using pattern matching
        Matcher matcher = DATA_ELEMENT_PATTERN.matcher(cleanData);
        while (matcher.find()) {
            String code = matcher.group(1);
            String value = matcher.group(2);
            if (value != null && !value.isEmpty()) {
                elements.put(code, value.trim());
                logger.trace("Found element {}: {}", code,
                        code.equals(LICENSE_NUMBER) ? "****" : value.trim());
            }
        }

        // Also try simple split approach as fallback
        if (elements.isEmpty() || !elements.containsKey(DOB)) {
            extractElementsSimple(cleanData, elements);
        }

        return elements;
    }

    /**
     * Simple extraction method as fallback
     */
    private void extractElementsSimple(String data, Map<String, String> elements) {
        String[] codes = { LAST_NAME, FIRST_NAME, FIRST_NAME_ALT, MIDDLE_NAME, FULL_NAME,
                DOB, EXPIRATION, LICENSE_NUMBER, STATE, ADDRESS, CITY, ZIP, SEX };

        for (String code : codes) {
            int index = data.indexOf(code);
            if (index >= 0) {
                int valueStart = index + code.length();
                int valueEnd = data.length();

                // Find the next code or control character
                for (int i = valueStart; i < data.length() - 2; i++) {
                    char c = data.charAt(i);
                    if (c == '\n' || c == '\r' || c == 0x1d || c == 0x1e) {
                        valueEnd = i;
                        break;
                    }
                    // Check if next 3 chars look like a code
                    if (i + 3 <= data.length()) {
                        String potential = data.substring(i, i + 3);
                        if (potential.matches("D[A-Z]{2}")) {
                            valueEnd = i;
                            break;
                        }
                    }
                }

                String value = data.substring(valueStart, valueEnd).trim();
                if (!value.isEmpty() && !elements.containsKey(code)) {
                    elements.put(code, value);
                }
            }
        }
    }

    /**
     * Alternative extraction method for licenses from states like Ohio
     * that may use different formatting
     */
    private Map<String, String> extractDataElementsAlternative(String data) {
        Map<String, String> elements = new HashMap<>();

        logger.debug("Trying alternative extraction for barcode data");

        // Try the alternative pattern
        Matcher matcher = DATA_ELEMENT_PATTERN_ALT.matcher(data);
        while (matcher.find()) {
            String code = matcher.group(1);
            String value = matcher.group(2);
            if (value != null && !value.isEmpty()) {
                // Clean the value more aggressively
                String cleanedValue = value.replaceAll("[\\x00-\\x1F\\x7F]", "").trim();
                if (!cleanedValue.isEmpty()) {
                    elements.put(code, cleanedValue);
                    logger.trace("Alt found element {}: {}", code,
                            code.equals(LICENSE_NUMBER) ? "****" : cleanedValue);
                }
            }
        }

        // If we still don't have DOB, try to find it with a more specific pattern
        if (!elements.containsKey(DOB)) {
            // Look for DBB followed by 8 digits
            Pattern dobPattern = Pattern.compile("DBB(\\d{8})");
            Matcher dobMatcher = dobPattern.matcher(data);
            if (dobMatcher.find()) {
                elements.put(DOB, dobMatcher.group(1));
                logger.debug("Found DOB with specific pattern: {}", dobMatcher.group(1));
            }
        }

        // Similarly for license number
        if (!elements.containsKey(LICENSE_NUMBER)) {
            // Look for DAQ followed by alphanumeric characters
            Pattern lnPattern = Pattern.compile("DAQ([A-Z0-9]+)");
            Matcher lnMatcher = lnPattern.matcher(data);
            if (lnMatcher.find()) {
                elements.put(LICENSE_NUMBER, lnMatcher.group(1));
                logger.debug("Found license number with specific pattern");
            }
        }

        return elements;
    }

    /**
     * Parse name from elements
     */
    private void parseName(Map<String, String> elements, DriversLicenseData license) {
        // Try individual name fields first
        String firstName = elements.get(FIRST_NAME);
        if (firstName == null) {
            firstName = elements.get(FIRST_NAME_ALT);
        }

        String lastName = elements.get(LAST_NAME);
        String middleName = elements.get(MIDDLE_NAME);

        // If individual fields found, use them
        if (firstName != null || lastName != null) {
            license.setFirstName(cleanValue(firstName));
            license.setLastName(cleanValue(lastName));
            license.setMiddleName(cleanValue(middleName));
            return;
        }

        // Try full name field (format: LAST,FIRST,MIDDLE or LAST,FIRST MIDDLE)
        String fullName = elements.get(FULL_NAME);
        if (fullName != null && !fullName.isEmpty()) {
            String[] parts = fullName.split(",");
            if (parts.length >= 1) {
                license.setLastName(cleanValue(parts[0]));
            }
            if (parts.length >= 2) {
                // Second part might be "FIRST MIDDLE" or just "FIRST"
                String[] firstMiddle = parts[1].trim().split("\\s+", 2);
                license.setFirstName(cleanValue(firstMiddle[0]));
                if (firstMiddle.length > 1) {
                    license.setMiddleName(cleanValue(firstMiddle[1]));
                }
            }
            if (parts.length >= 3) {
                license.setMiddleName(cleanValue(parts[2]));
            }
        }
    }

    /**
     * Parse date from various formats
     */
    private LocalDate parseDate(String dateStr) {
        if (dateStr == null || dateStr.isEmpty()) {
            return null;
        }

        logger.debug("Attempting to parse date from: '{}'", dateStr);

        // First, try cleaning the date string - keep only digits, slashes, hyphens
        String cleanDate = dateStr.replaceAll("[^0-9/\\-]", "");
        logger.debug("Cleaned date string: '{}'", cleanDate);

        // Try each format
        for (DateTimeFormatter formatter : DATE_FORMATS) {
            try {
                LocalDate result = LocalDate.parse(cleanDate, formatter);
                logger.debug("Successfully parsed date as: {}", result);
                return result;
            } catch (DateTimeParseException e) {
                // Try next format
            }
        }

        // Try to parse 8-digit dates manually (common for Ohio: MMDDYYYY)
        if (cleanDate.length() >= 8) {
            String digits = cleanDate.replaceAll("[^0-9]", "");
            if (digits.length() >= 8) {
                // Take first 8 digits
                digits = digits.substring(0, 8);
                logger.debug("Trying manual 8-digit parse on: '{}'", digits);

                // Try MMDDYYYY first (most common US format, including Ohio)
                try {
                    int month = Integer.parseInt(digits.substring(0, 2));
                    int day = Integer.parseInt(digits.substring(2, 4));
                    int year = Integer.parseInt(digits.substring(4, 8));
                    if (month >= 1 && month <= 12 && day >= 1 && day <= 31 && year >= 1900 && year <= 2100) {
                        LocalDate result = LocalDate.of(year, month, day);
                        logger.debug("Parsed as MMDDYYYY: {}", result);
                        return result;
                    }
                } catch (Exception e) {
                    logger.trace("MMDDYYYY parse failed: {}", e.getMessage());
                }

                // Try YYYYMMDD
                try {
                    int year = Integer.parseInt(digits.substring(0, 4));
                    int month = Integer.parseInt(digits.substring(4, 6));
                    int day = Integer.parseInt(digits.substring(6, 8));
                    if (year >= 1900 && year <= 2100 && month >= 1 && month <= 12 && day >= 1 && day <= 31) {
                        LocalDate result = LocalDate.of(year, month, day);
                        logger.debug("Parsed as YYYYMMDD: {}", result);
                        return result;
                    }
                } catch (Exception e) {
                    logger.trace("YYYYMMDD parse failed: {}", e.getMessage());
                }

                // Try DDMMYYYY (less common but some states use it)
                try {
                    int day = Integer.parseInt(digits.substring(0, 2));
                    int month = Integer.parseInt(digits.substring(2, 4));
                    int year = Integer.parseInt(digits.substring(4, 8));
                    if (day >= 1 && day <= 31 && month >= 1 && month <= 12 && year >= 1900 && year <= 2100) {
                        LocalDate result = LocalDate.of(year, month, day);
                        logger.debug("Parsed as DDMMYYYY: {}", result);
                        return result;
                    }
                } catch (Exception e) {
                    logger.trace("DDMMYYYY parse failed: {}", e.getMessage());
                }
            }
        }

        logger.warn("Could not parse date: '{}'", dateStr);
        return null;
    }

    /**
     * Parse gender code
     */
    private String parseGender(String genderCode) {
        if (genderCode == null || genderCode.isEmpty()) {
            return null;
        }

        String code = genderCode.trim();
        if (code.equals("1") || code.equalsIgnoreCase("M")) {
            return "M";
        } else if (code.equals("2") || code.equalsIgnoreCase("F")) {
            return "F";
        }
        return code;
    }

    /**
     * Clean a value by removing control characters and trimming
     */
    private String cleanValue(String value) {
        if (value == null) {
            return null;
        }
        // Remove control characters and trim
        String cleaned = value.replaceAll("[\\x00-\\x1F\\x7F]", "").trim();
        return cleaned.isEmpty() ? null : cleaned;
    }

    /**
     * Clean and format zip code
     */
    private String cleanZipCode(String zip) {
        if (zip == null) {
            return null;
        }
        // Extract just the digits, take first 5 or 9 for ZIP+4
        String digits = zip.replaceAll("[^0-9]", "");
        if (digits.length() >= 9) {
            return digits.substring(0, 5) + "-" + digits.substring(5, 9);
        } else if (digits.length() >= 5) {
            return digits.substring(0, 5);
        }
        return digits.isEmpty() ? null : digits;
    }

    /**
     * Check if a barcode string looks like it could be a driver's license
     * This is a quick check before attempting full parsing
     * 
     * @param data The barcode data to check
     * @return true if it might be a driver's license barcode
     */
    public boolean mightBeDriversLicense(String data) {
        if (data == null || data.length() < 50) {
            return false;
        }

        // Check for common AAMVA indicators
        return data.contains("@") ||
                data.contains("ANSI") ||
                data.contains("DAQ") ||
                data.contains("DBB") ||
                data.contains("DCS") ||
                data.contains("AAMVA");
    }
}
