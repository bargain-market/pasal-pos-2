package com.pos.util;

import org.mindrot.jbcrypt.BCrypt;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Utility class for secure password/PIN hashing using BCrypt
 */
public class PasswordHasher {
    private static final Logger logger = LoggerFactory.getLogger(PasswordHasher.class);
    
    // BCrypt work factor (cost) - higher is more secure but slower
    // Using 10 as a balance between security and performance
    private static final int BCRYPT_ROUNDS = 10;
    
    /**
     * Hash a password or PIN securely
     * @param password The plain text password or PIN to hash
     * @return The hashed password/PIN
     */
    public static String hashPassword(String password) {
        if (password == null || password.isEmpty()) {
            throw new IllegalArgumentException("Password cannot be null or empty");
        }
        
        try {
            return BCrypt.hashpw(password, BCrypt.gensalt(BCRYPT_ROUNDS));
        } catch (Exception e) {
            logger.error("Error hashing password", e);
            throw new RuntimeException("Failed to hash password", e);
        }
    }
    
    /**
     * Verify a password or PIN against a hash
     * @param password The plain text password or PIN to verify
     * @param hash The stored hash to verify against
     * @return true if the password matches the hash, false otherwise
     */
    public static boolean verifyPassword(String password, String hash) {
        if (password == null || password.isEmpty()) {
            return false;
        }
        
        if (hash == null || hash.isEmpty()) {
            return false;
        }
        
        try {
            return BCrypt.checkpw(password, hash);
        } catch (Exception e) {
            logger.error("Error verifying password", e);
            return false;
        }
    }
    
    /**
     * Check if a string is already a BCrypt hash
     * BCrypt hashes start with $2a$, $2b$, or $2y$ followed by cost and salt
     * @param hash The string to check
     * @return true if it appears to be a BCrypt hash
     */
    public static boolean isHashed(String hash) {
        if (hash == null || hash.isEmpty()) {
            return false;
        }
        
        return hash.startsWith("$2a$") || hash.startsWith("$2b$") || hash.startsWith("$2y$");
    }
}

