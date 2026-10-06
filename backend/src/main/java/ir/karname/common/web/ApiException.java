package ir.karname.common.web;

import org.springframework.http.HttpStatus;

/**
 * Business error with a message key resolved (in Persian) from {@code messages.properties}.
 * The key is also returned to clients as {@code code} for programmatic handling.
 */
public class ApiException extends RuntimeException {

    private final HttpStatus status;
    private final String code;
    private final transient Object[] args;

    public ApiException(HttpStatus status, String code, Object... args) {
        super(code);
        this.status = status;
        this.code = code;
        this.args = args;
    }

    public HttpStatus status() {
        return status;
    }

    public String code() {
        return code;
    }

    public Object[] args() {
        return args;
    }

    public static ApiException notFound(String code, Object... args) {
        return new ApiException(HttpStatus.NOT_FOUND, code, args);
    }

    public static ApiException badRequest(String code, Object... args) {
        return new ApiException(HttpStatus.BAD_REQUEST, code, args);
    }

    public static ApiException conflict(String code, Object... args) {
        return new ApiException(HttpStatus.CONFLICT, code, args);
    }

    public static ApiException forbidden(String code, Object... args) {
        return new ApiException(HttpStatus.FORBIDDEN, code, args);
    }

    public static ApiException unauthorized(String code, Object... args) {
        return new ApiException(HttpStatus.UNAUTHORIZED, code, args);
    }

    public static ApiException tooManyRequests(String code, Object... args) {
        return new ApiException(HttpStatus.TOO_MANY_REQUESTS, code, args);
    }

    public static ApiException unavailable(String code, Object... args) {
        return new ApiException(HttpStatus.SERVICE_UNAVAILABLE, code, args);
    }
}
