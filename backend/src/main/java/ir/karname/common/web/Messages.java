package ir.karname.common.web;

import org.springframework.context.MessageSource;
import org.springframework.context.NoSuchMessageException;
import org.springframework.stereotype.Component;

import java.util.Locale;

/** Resolves Persian user-facing messages; the UI is Persian-only. */
@Component
public class Messages {

    public static final Locale PERSIAN = Locale.forLanguageTag("fa-IR");

    private final MessageSource messageSource;

    public Messages(MessageSource messageSource) {
        this.messageSource = messageSource;
    }

    public String get(String code, Object... args) {
        try {
            return messageSource.getMessage(code, args, PERSIAN);
        } catch (NoSuchMessageException e) {
            return messageSource.getMessage("error.generic", null, "خطایی رخ داد.", PERSIAN);
        }
    }
}
