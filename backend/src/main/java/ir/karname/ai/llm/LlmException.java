package ir.karname.ai.llm;

/**
 * A provider failure, with a Persian message fit for users and the provider's own wording in
 * {@link #detail()} (shown only to administrators, e.g. when testing a connection).
 */
public class LlmException extends RuntimeException {

    public enum Kind {
        /** The key is wrong or not allowed (also when the provider blocks the region). */
        AUTH,
        RATE_LIMIT,
        /** The provider is temporarily overloaded or failing. */
        UNAVAILABLE,
        /** The provider rejected the request itself (wrong model name, unsupported option…). */
        BAD_REQUEST,
        NETWORK,
        TIMEOUT,
        CANCELLED,
        /** The reply could not be understood. */
        INVALID_RESPONSE
    }

    private final Kind kind;
    private final String detail;

    public LlmException(Kind kind, String detail, Throwable cause) {
        super(kind.name() + (detail == null ? "" : ": " + detail), cause);
        this.kind = kind;
        this.detail = detail;
    }

    public LlmException(Kind kind, String detail) {
        this(kind, detail, null);
    }

    public Kind kind() {
        return kind;
    }

    public String detail() {
        return detail;
    }

    /** What to tell the user, in Persian. */
    public String userMessage() {
        return switch (kind) {
            case AUTH -> "سرویس هوش مصنوعی درخواست را نپذیرفت؛ کلید API یا دسترسی آن را بررسی کنید.";
            case RATE_LIMIT -> "سقف درخواست سرویس هوش مصنوعی پر شده است؛ کمی بعد دوباره امتحان کنید.";
            case UNAVAILABLE -> "سرویس هوش مصنوعی موقتاً در دسترس نیست؛ کمی بعد دوباره امتحان کنید.";
            case BAD_REQUEST -> "سرویس هوش مصنوعی درخواست را نامعتبر دانست؛ نام مدل و تنظیمات آن را بررسی کنید.";
            case NETWORK -> "اتصال به سرویس هوش مصنوعی برقرار نشد.";
            case TIMEOUT -> "سرویس هوش مصنوعی در زمان مقرر پاسخ نداد.";
            case CANCELLED -> "درخواست متوقف شد.";
            case INVALID_RESPONSE -> "پاسخ سرویس هوش مصنوعی قابل استفاده نبود.";
        };
    }
}
