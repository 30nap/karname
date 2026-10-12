package ir.karname.pricefeed;

/**
 * A source could not be read; the message is Persian and shown to the admin as is. {@code status}
 * is the HTTP status the service answered with, or 0 when there was no answer.
 */
public class PriceFetchException extends RuntimeException {

    private final int status;

    public PriceFetchException(String message) {
        this(message, 0, null);
    }

    public PriceFetchException(String message, Throwable cause) {
        this(message, 0, cause);
    }

    public PriceFetchException(String message, int status) {
        this(message, status, null);
    }

    private PriceFetchException(String message, int status, Throwable cause) {
        super(message, cause);
        this.status = status;
    }

    public int status() {
        return status;
    }
}
