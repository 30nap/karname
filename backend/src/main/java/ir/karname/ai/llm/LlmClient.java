package ir.karname.ai.llm;

import java.util.List;

/** A language model provider, behind which any adapter (Anthropic, OpenAI-compatible…) sits. */
public interface LlmClient {

    /**
     * Sends a request and blocks until the reply is complete, passing text to {@code listener} as
     * it is generated.
     *
     * @throws LlmException when the provider cannot be reached or rejects the request
     */
    LlmResponse send(LlmRequest request, LlmListener listener);

    /** Model ids the provider offers, for choosing one in the settings. */
    List<String> listModels();

    /** Whether {@code model} can constrain its reply to a JSON schema; otherwise the prompt has to ask for JSON. */
    boolean supportsStructuredOutput(String model);

    /** Whether {@code model} can call tools; otherwise the data it needs goes into the prompt. */
    boolean supportsTools(String model);
}
