package com.upc.computer.face;

/** Only fixed, non-sensitive messages may cross the face API boundary. */
public class FaceException extends RuntimeException {
    private final int status;
    private final String reason;
    public FaceException(int status, String reason, String message) {
        super(message);
        this.status = status;
        this.reason = reason;
    }
    public int status() { return status; }
    public String reason() { return reason; }
}
