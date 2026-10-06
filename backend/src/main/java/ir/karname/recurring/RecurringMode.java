package ir.karname.recurring;

public enum RecurringMode {
    /** Posted automatically on its date (and caught up after downtime). */
    AUTO,
    /** Only reminded; the user posts or skips each occurrence. */
    REMIND
}
