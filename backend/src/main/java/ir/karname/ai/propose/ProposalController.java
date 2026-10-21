package ir.karname.ai.propose;

import ir.karname.common.security.KarnamePrincipal;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** The user's decision on a card the assistant proposed. */
@RestController
@RequestMapping("/api/v1/ai/proposals")
public class ProposalController {

    private final ProposalService proposals;

    public ProposalController(ProposalService proposals) {
        this.proposals = proposals;
    }

    /** Records it as shown; a second call returns the first result without recording again. */
    @PostMapping("/{ref}/apply")
    public ProposalView apply(@AuthenticationPrincipal KarnamePrincipal user, @PathVariable String ref) {
        return proposals.apply(user.id(), ref);
    }

    @PostMapping("/{ref}/dismiss")
    public ProposalView dismiss(@AuthenticationPrincipal KarnamePrincipal user, @PathVariable String ref) {
        return proposals.dismiss(user.id(), ref);
    }
}
