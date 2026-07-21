package com.pos.ui.keyboard;

/**
 * Defines the type of input expected for a text field.
 * Used to determine whether to show a full keyboard or numeric keypad.
 * 
 * For touch-only POS systems:
 * - TEXT: Shows full QWERTY keyboard (for names, search, notes)
 * - NUMERIC: Shows numeric keypad only (for amounts, PIN, quantities)
 * - DECIMAL: Shows numeric keypad with decimal point (for prices, cash amounts)
 * - PIN: Shows numeric keypad with enter button (for PIN entry)
 */
public enum InputType {
    /**
     * Full keyboard for text input (search, names, notes, etc.)
     */
    TEXT,
    
    /**
     * Numeric keypad for integers only (quantities, ID numbers)
     */
    NUMERIC,
    
    /**
     * Numeric keypad with decimal point (cash amounts, prices)
     */
    DECIMAL,
    
    /**
     * Numeric keypad with PIN indicator and enter button (PIN entry)
     */
    PIN;
    
    /**
     * Check if this input type requires numeric-only input
     */
    public boolean isNumeric() {
        return this == NUMERIC || this == DECIMAL || this == PIN;
    }
    
    /**
     * Check if decimal point should be allowed
     */
    public boolean allowsDecimal() {
        return this == DECIMAL;
    }
    
    /**
     * Check if this is a PIN entry field
     */
    public boolean isPinEntry() {
        return this == PIN;
    }
}
