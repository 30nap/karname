package ir.karname.pricefeed;

/** A source could not be read; the message is Persian and shown to the admin as is. */
public class PriceFetchException extends RuntimeException {

    public PriceFetchException(String message) {
        super(message);
    }

    public PriceFetchException(String message, Throwable cause) {
        super(message, cause);
    }
}
