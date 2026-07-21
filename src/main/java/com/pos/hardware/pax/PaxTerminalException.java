package com.pos.hardware.pax;

/**
 * Error communicating with a PAX terminal.
 */
public class PaxTerminalException extends Exception {

    public PaxTerminalException(String message) {
        super(message);
    }

    public PaxTerminalException(String message, Throwable cause) {
        super(message, cause);
    }
}
