package ir.karname.ai.capture;

import ir.karname.ai.AiAccess;
import ir.karname.ai.llm.LlmException;
import ir.karname.ai.llm.LlmListener;
import ir.karname.ai.llm.LlmMessage;
import ir.karname.ai.llm.LlmRequest;
import ir.karname.ai.llm.LlmResponse;
import ir.karname.ai.llm.LlmUsage;
import ir.karname.ai.llm.Part;
import ir.karname.ai.llm.StopReason;
import ir.karname.ai.llm.StructuredOutput;
import ir.karname.ai.llm.StructuredReply;
import ir.karname.ai.provider.AiProviderService.ResolvedRoute;
import ir.karname.ai.provider.AiTask;
import ir.karname.ai.usage.AiUsageService;
import ir.karname.ai.usage.AiUsageService.Outcome;
import ir.karname.common.web.ApiException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * One request for a JSON answer: the provider holds the model to the schema where it can,
 * otherwise the prompt asks for it and the reply is read leniently. Either way the answer is
 * checked against the schema, and a non-conforming one gets a single retry with the problems
 * spelled out. Each call is accounted for in the user's usage.
 */
@Component
public class StructuredCalls {

    private static final Logger log = LoggerFactory.getLogger(StructuredCalls.class);

    private final AiAccess access;
    private final AiUsageService usage;

    public StructuredCalls(AiAccess access, AiUsageService usage) {
        this.access = access;
        this.usage = usage;
    }

    /** The validated JSON answer, and the model that gave it. */
    public record Answer(JsonNode value, String model) {
    }

    /**
     * @param usageTask the name the operation is accounted under (e.g. SMS)
     * @throws ApiException when AI is not available to the user, the provider fails, or the reply stays unusable
     */
    public Answer call(long userId, AiTask task, String usageTask, String system, List<Part> question, StructuredOutput output,
            int maxTokens) {
        ResolvedRoute route = access.require(userId, task);
        List<LlmMessage> messages = new ArrayList<>(List.of(new LlmMessage.User(question)));
        LlmUsage total = LlmUsage.ZERO;
        int calls = 0;
        Outcome outcome = Outcome.ERROR;
        try {
            for (int attempt = 0; attempt < 2; attempt++) {
                calls++;
                LlmResponse response = route.client().send(new LlmRequest(route.model(), system, messages, List.of(), output, route.effort(),
                        maxTokens), LlmListener.NONE);
                total = total.plus(response.usage());
                if (response.stopReason() == StopReason.REFUSAL) {
                    outcome = Outcome.REFUSED;
                    throw new ApiException(HttpStatus.UNPROCESSABLE_CONTENT, "ai.refused");
                }
                Optional<JsonNode> parsed = StructuredReply.parse(response.text());
                String problem;
                if (parsed.isPresent()) {
                    JsonNode value = output.schema().coerce(parsed.get());
                    List<String> problems = output.schema().validate(value);
                    if (problems.isEmpty()) {
                        outcome = Outcome.OK;
                        return new Answer(value, response.model());
                    }
                    problem = "Your reply did not match the schema: " + String.join("; ", problems.subList(0, Math.min(problems.size(), 10)));
                } else {
                    problem = response.stopReason() == StopReason.MAX_TOKENS ? "Your reply was cut off before the JSON was complete."
                            : "Your reply was not a JSON object.";
                }
                log.info("Structured reply rejected ({}): {}", usageTask, problem);
                messages.add(response.toMessage());
                messages.add(new LlmMessage.User(List.of(new Part.Text(problem + " Reply again with only the corrected JSON object."))));
            }
            throw new ApiException(HttpStatus.BAD_GATEWAY, "ai.invalidOutput");
        } catch (LlmException e) {
            outcome = e.kind() == LlmException.Kind.CANCELLED ? Outcome.STOPPED : Outcome.ERROR;
            log.warn("AI {} failed: {} {}", usageTask, e.kind(), e.detail());
            throw AiAccess.error(e);
        } finally {
            try {
                usage.record(new AiUsageService.Operation(userId, usageTask, route.providerId(), route.providerName(), route.model(), calls,
                        total, outcome));
            } catch (RuntimeException e) {
                log.warn("Could not record AI usage", e);
            }
        }
    }
}
