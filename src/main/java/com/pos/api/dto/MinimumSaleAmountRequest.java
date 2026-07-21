package com.pos.api.dto;

/**
 * Request body for POST /pos/settings/minimum-sale-amount
 */
public class MinimumSaleAmountRequest {
    /** Null or zero clears the minimum; positive value enforces a floor on sale totals */
    public Double minimumSaleAmount;
}
