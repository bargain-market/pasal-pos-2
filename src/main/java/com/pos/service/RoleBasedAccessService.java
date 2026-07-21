package com.pos.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.Map;

/**
 * Service for role-based access control (RBAC)
 * Provides centralized permission checking based on user roles
 */
public class RoleBasedAccessService {
    private static final Logger logger = LoggerFactory.getLogger(RoleBasedAccessService.class);
    private static RoleBasedAccessService instance;

    private final UserAuthService userAuthService;
    private final PermissionManagementService permissionService;

    // Cache for current user's effective permissions (cleared on logout)
    private Map<String, Boolean> cachedEffectivePermissions = null;
    private String cachedUserId = null;
    private String cachedUserType = null;

    // Role constants
    public static final String ROLE_ADMIN = "Admin";
    public static final String ROLE_MANAGER = "Manager";
    public static final String ROLE_CASHIER = "Cashier";

    // Permission constants
    public static final String PERMISSION_MANAGE_EMPLOYEES = "manage_employees";
    public static final String PERMISSION_MANAGE_SETTINGS = "manage_settings";
    public static final String PERMISSION_MANAGE_HARDWARE = "manage_hardware";
    public static final String PERMISSION_VOID_TRANSACTION = "void_transaction";
    public static final String PERMISSION_PROCESS_REFUND = "process_refund";
    public static final String PERMISSION_APPROVE_DISCOUNT = "approve_discount";
    public static final String PERMISSION_ADJUST_STOCK = "adjust_stock";
    public static final String PERMISSION_MANAGE_PRODUCTS = "manage_products";
    public static final String PERMISSION_CASH_OPERATIONS = "cash_operations";
    public static final String PERMISSION_NO_SALE = "no_sale";
    public static final String PERMISSION_VIEW_REPORTS = "view_reports";
    public static final String PERMISSION_MANAGE_INVENTORY = "manage_inventory";
    public static final String PERMISSION_EXPENSES = "expenses";
    public static final String PERMISSION_VIEW_SHIFT_REPORT = "view_shift_report";
    public static final String PERMISSION_END_DAY = "end_day";

    private RoleBasedAccessService() {
        this.userAuthService = UserAuthService.getInstance();
        this.permissionService = PermissionManagementService.getInstance();
    }

    public static synchronized RoleBasedAccessService getInstance() {
        if (instance == null) {
            instance = new RoleBasedAccessService();
        }
        return instance;
    }

    /**
     * Get current user's role
     * 
     * @return Role string (Admin, Manager, Cashier) or null if not logged in
     */
    public String getCurrentUserRole() {
        return userAuthService.getCurrentUserRole();
    }

    /**
     * Check if current user is Admin
     */
    public boolean isAdmin() {
        String role = getCurrentUserRole();
        return ROLE_ADMIN.equalsIgnoreCase(role);
    }

    /**
     * Check if current user is Manager or Admin
     */
    public boolean isManager() {
        String role = getCurrentUserRole();
        return ROLE_MANAGER.equalsIgnoreCase(role) || ROLE_ADMIN.equalsIgnoreCase(role);
    }

    /**
     * Check if current user is Cashier
     */
    public boolean isCashier() {
        String role = getCurrentUserRole();
        return ROLE_CASHIER.equalsIgnoreCase(role);
    }

    /**
     * Check if current user has a specific permission from their current role.
     */
    public boolean hasPermission(String permission) {
        if (!userAuthService.isLoggedIn()) {
            return false;
        }

        String userId = null;
        String userType = null;

        try {
            // Get current user info
            if (userAuthService.isPosUser()) {
                userId = userAuthService.getCurrentPosUserId();
                userType = PermissionManagementService.USER_TYPE_POS_USER;
            } else {
                userId = userAuthService.getCurrentUserId();
                userType = PermissionManagementService.USER_TYPE_REGULAR_USER;
            }

            if (userId == null) {
                return false;
            }

            // Check if we have cached permissions for this user
            if (cachedEffectivePermissions != null && userId.equals(cachedUserId) && userType.equals(cachedUserType)) {
                return cachedEffectivePermissions.getOrDefault(permission, false);
            }

            Map<String, Boolean> effectivePermissions = permissionService.getEffectivePermissions(userId, userType);

            // Log permission loading for debugging
            if (logger.isDebugEnabled()) {
                logger.debug("Loaded {} permissions for user {} ({}): {}",
                        effectivePermissions.size(), userId, userType,
                        effectivePermissions.entrySet().stream()
                                .filter(Map.Entry::getValue)
                                .map(Map.Entry::getKey)
                                .toList());
            }

            cachedEffectivePermissions = effectivePermissions;
            cachedUserId = userId;
            cachedUserType = userType;

            boolean hasPerm = effectivePermissions.getOrDefault(permission, false);
            logger.debug("Permission check: {} = {} for user {}", permission, hasPerm, userId);
            return hasPerm;

        } catch (Exception e) {
            logger.error("Error checking permission: {} for user {} ({}): {}",
                    permission, userId != null ? userId : "unknown",
                    userType != null ? userType : "unknown", e.getMessage(), e);
            logger.warn("Falling back to role-based permission check");
            return hasRoleBasedPermission(permission);
        }
    }

    /**
     * Check permission based on role only (fallback method).
     */
    private boolean hasRoleBasedPermission(String permission) {
        String role = getCurrentUserRole();
        if (role == null) {
            return false;
        }
        return permissionService.getRoleBasedPermissions(role).getOrDefault(permission, false);
    }

    /**
     * Get all effective permissions for current user.
     */
    public Map<String, Boolean> getEffectivePermissions() {
        if (!userAuthService.isLoggedIn()) {
            return new HashMap<>();
        }

        try {
            String userId = null;
            String userType = null;

            if (userAuthService.isPosUser()) {
                userId = userAuthService.getCurrentPosUserId();
                userType = PermissionManagementService.USER_TYPE_POS_USER;
            } else {
                userId = userAuthService.getCurrentUserId();
                userType = PermissionManagementService.USER_TYPE_REGULAR_USER;
            }

            if (userId == null) {
                return new HashMap<>();
            }

            if (cachedEffectivePermissions != null && userId.equals(cachedUserId) && userType.equals(cachedUserType)) {
                return new HashMap<>(cachedEffectivePermissions);
            }

            Map<String, Boolean> effectivePermissions = permissionService.getEffectivePermissions(userId, userType);

            cachedEffectivePermissions = effectivePermissions;
            cachedUserId = userId;
            cachedUserType = userType;

            return new HashMap<>(effectivePermissions);

        } catch (Exception e) {
            logger.error("Error getting effective permissions", e);
            return new HashMap<>();
        }
    }

    public boolean hasCustomPermission(String permission) {
        return false;
    }

    /**
     * Clear permission cache (call when user logs out or permissions are updated)
     */
    public void clearPermissionCache() {
        cachedEffectivePermissions = null;
        cachedUserId = null;
        cachedUserType = null;
    }

    /**
     * Check if current user can access a specific feature
     * 
     * @param feature Feature name (e.g., "settings", "reports", "inventory")
     * @return true if user can access, false otherwise
     */
    public boolean canAccess(String feature) {
        if (!userAuthService.isLoggedIn()) {
            return false;
        }

        String role = getCurrentUserRole();
        if (role == null) {
            return false;
        }

        // Admin can access everything
        if (ROLE_ADMIN.equalsIgnoreCase(role)) {
            return true;
        }

        // Feature-based access control
        switch (feature.toLowerCase()) {
            case "settings":
            case "hardware_settings":
            case "employee_management":
                // Admin only
                return false;

            case "reports":
                return hasPermission(PERMISSION_VIEW_REPORTS);

            case "inventory":
                return hasPermission(PERMISSION_MANAGE_INVENTORY);

            case "product_management":
            case "department_management":
                return hasPermission(PERMISSION_MANAGE_PRODUCTS);

            case "sales":
            case "sale_history":
            case "shift":
                // All roles
                return true;

            case "shift_report":
                // Manager and Admin (or custom permission)
                return hasPermission(PERMISSION_VIEW_SHIFT_REPORT);

            default:
                // Default: allow access (backward compatibility)
                logger.debug("Unknown feature: {}, allowing access", feature);
                return true;
        }
    }

    /**
     * Require a specific permission, throw exception if not granted
     * 
     * @param permission Permission constant
     * @throws SecurityException if user doesn't have permission
     */
    public void requirePermission(String permission) throws SecurityException {
        if (!hasPermission(permission)) {
            String role = getCurrentUserRole();
            throw new SecurityException(
                    String.format("Permission denied: %s (Current role: %s)", permission,
                            role != null ? role : "Unknown"));
        }
    }

    /**
     * Require Manager or Admin role, throw exception if not granted
     * 
     * @throws SecurityException if user is not Manager or Admin
     */
    public void requireManager() throws SecurityException {
        if (!isManager()) {
            String role = getCurrentUserRole();
            throw new SecurityException(
                    String.format("Manager or Admin role required (Current role: %s)",
                            role != null ? role : "Unknown"));
        }
    }

    /**
     * Require Admin role, throw exception if not granted
     * 
     * @throws SecurityException if user is not Admin
     */
    public void requireAdmin() throws SecurityException {
        if (!isAdmin()) {
            String role = getCurrentUserRole();
            throw new SecurityException(
                    String.format("Admin role required (Current role: %s)", role != null ? role : "Unknown"));
        }
    }
}
