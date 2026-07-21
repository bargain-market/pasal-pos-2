package com.pos.model;

import java.time.LocalDate;
import java.time.Period;

/**
 * Data Transfer Object for parsed driver's license information.
 * Used when scanning PDF417 barcodes from US driver's licenses.
 * 
 * Based on AAMVA (American Association of Motor Vehicle Administrators)
 * standard.
 */
public class DriversLicenseData {

    private String firstName;
    private String lastName;
    private String middleName;
    private LocalDate dateOfBirth;
    private LocalDate expirationDate;
    private String licenseNumber;
    private String state;
    private String address;
    private String city;
    private String zipCode;
    private String gender;

    // Raw barcode data for debugging
    private String rawData;

    /**
     * Default constructor
     */
    public DriversLicenseData() {
    }

    /**
     * Constructor with essential fields
     */
    public DriversLicenseData(String firstName, String lastName, LocalDate dateOfBirth,
            LocalDate expirationDate, String licenseNumber) {
        this.firstName = firstName;
        this.lastName = lastName;
        this.dateOfBirth = dateOfBirth;
        this.expirationDate = expirationDate;
        this.licenseNumber = licenseNumber;
    }

    // Getters and Setters

    public String getFirstName() {
        return firstName;
    }

    public void setFirstName(String firstName) {
        this.firstName = firstName;
    }

    public String getLastName() {
        return lastName;
    }

    public void setLastName(String lastName) {
        this.lastName = lastName;
    }

    public String getMiddleName() {
        return middleName;
    }

    public void setMiddleName(String middleName) {
        this.middleName = middleName;
    }

    public LocalDate getDateOfBirth() {
        return dateOfBirth;
    }

    public void setDateOfBirth(LocalDate dateOfBirth) {
        this.dateOfBirth = dateOfBirth;
    }

    public LocalDate getExpirationDate() {
        return expirationDate;
    }

    public void setExpirationDate(LocalDate expirationDate) {
        this.expirationDate = expirationDate;
    }

    public String getLicenseNumber() {
        return licenseNumber;
    }

    public void setLicenseNumber(String licenseNumber) {
        this.licenseNumber = licenseNumber;
    }

    public String getState() {
        return state;
    }

    public void setState(String state) {
        this.state = state;
    }

    public String getAddress() {
        return address;
    }

    public void setAddress(String address) {
        this.address = address;
    }

    public String getCity() {
        return city;
    }

    public void setCity(String city) {
        this.city = city;
    }

    public String getZipCode() {
        return zipCode;
    }

    public void setZipCode(String zipCode) {
        this.zipCode = zipCode;
    }

    public String getGender() {
        return gender;
    }

    public void setGender(String gender) {
        this.gender = gender;
    }

    public String getRawData() {
        return rawData;
    }

    public void setRawData(String rawData) {
        this.rawData = rawData;
    }

    /**
     * Calculate age based on date of birth
     * 
     * @return Age in years, or -1 if DOB is not set
     */
    public int calculateAge() {
        if (dateOfBirth == null) {
            return -1;
        }
        return Period.between(dateOfBirth, LocalDate.now()).getYears();
    }

    /**
     * Get last 4 characters of license number for compliance storage
     * 
     * @return Last 4 characters or full number if less than 4 chars
     */
    public String getLastFourOfLicense() {
        if (licenseNumber == null || licenseNumber.isEmpty()) {
            return null;
        }
        // Remove any non-alphanumeric characters
        String cleanNumber = licenseNumber.replaceAll("[^A-Za-z0-9]", "");
        if (cleanNumber.length() <= 4) {
            return cleanNumber;
        }
        return cleanNumber.substring(cleanNumber.length() - 4);
    }

    /**
     * Check if the license is expired
     * 
     * @return true if expired, false if valid or no expiration date
     */
    public boolean isExpired() {
        if (expirationDate == null) {
            return false;
        }
        return expirationDate.isBefore(LocalDate.now());
    }

    /**
     * Check if the person is at least the specified age
     * 
     * @param minimumAge The minimum age required
     * @return true if person is at least minimumAge years old
     */
    public boolean isAtLeastAge(int minimumAge) {
        int age = calculateAge();
        return age >= minimumAge;
    }

    /**
     * Get full name
     * 
     * @return Full name in "First Last" format
     */
    public String getFullName() {
        StringBuilder name = new StringBuilder();
        if (firstName != null && !firstName.isEmpty()) {
            name.append(firstName);
        }
        if (middleName != null && !middleName.isEmpty()) {
            if (name.length() > 0)
                name.append(" ");
            name.append(middleName);
        }
        if (lastName != null && !lastName.isEmpty()) {
            if (name.length() > 0)
                name.append(" ");
            name.append(lastName);
        }
        return name.toString();
    }

    /**
     * Validate that essential fields are present
     * 
     * @return true if DOB and license number are present
     */
    public boolean isValid() {
        return dateOfBirth != null && licenseNumber != null && !licenseNumber.isEmpty();
    }

    @Override
    public String toString() {
        return "DriversLicenseData{" +
                "name='" + getFullName() + '\'' +
                ", dateOfBirth=" + dateOfBirth +
                ", age=" + calculateAge() +
                ", expirationDate=" + expirationDate +
                ", licenseNumber='" + (licenseNumber != null ? "****" + getLastFourOfLicense() : "null") + '\'' +
                ", state='" + state + '\'' +
                ", isExpired=" + isExpired() +
                '}';
    }
}
