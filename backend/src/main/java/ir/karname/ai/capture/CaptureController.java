package ir.karname.ai.capture;

import ir.karname.ai.capture.CaptureService.CommitRequest;
import ir.karname.ai.capture.CaptureService.CommitResult;
import ir.karname.ai.capture.CaptureService.QuickAddResult;
import ir.karname.ai.capture.CaptureService.SmsResult;
import ir.karname.common.security.KarnamePrincipal;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/ai")
public class CaptureController {

    private final CaptureService capture;

    public CaptureController(CaptureService capture) {
        this.capture = capture;
    }

    public record TextRequest(String text) {
    }

    /** Drafts from a note such as «دیروز ناهار ۱۸۰ و اسنپ ۹۵ تومن»; nothing is recorded. */
    @PostMapping("/quick-add")
    public QuickAddResult quickAdd(@AuthenticationPrincipal KarnamePrincipal user, @RequestBody TextRequest request) {
        return capture.quickAdd(user.id(), request == null ? null : request.text());
    }

    /** Drafts from pasted bank SMS (separated by blank lines); nothing is recorded. */
    @PostMapping("/sms")
    public SmsResult sms(@AuthenticationPrincipal KarnamePrincipal user, @RequestBody TextRequest request) {
        return capture.sms(user.id(), request == null ? null : request.text());
    }

    /** Records confirmed drafts; drafts already recorded are skipped. */
    @PostMapping("/drafts/commit")
    public CommitResult commit(@AuthenticationPrincipal KarnamePrincipal user, @RequestBody CommitRequest request) {
        return capture.commit(user.id(), request);
    }
}
