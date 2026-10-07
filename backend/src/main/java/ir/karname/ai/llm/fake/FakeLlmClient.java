package ir.karname.ai.llm.fake;

import ir.karname.ai.llm.LlmClient;
import ir.karname.ai.llm.LlmException;
import ir.karname.ai.llm.LlmListener;
import ir.karname.ai.llm.LlmRequest;
import ir.karname.ai.llm.LlmResponse;
import ir.karname.ai.llm.ProviderConfig;

import java.util.List;

/** A scripted, offline model for tests and demos ({@code karname.ai.fake-enabled}). */
public final class FakeLlmClient implements LlmClient {

    private final FakeLlm fake;
    private final ProviderConfig config;

    public FakeLlmClient(FakeLlm fake, ProviderConfig config) {
        this.fake = fake;
        this.config = config;
    }

    @Override
    public LlmResponse send(LlmRequest request, LlmListener listener) {
        LlmResponse response = fake.respond(request);
        String text = response.text();
        for (int i = 0; i < text.length(); i += 16) {
            if (listener.cancelled()) {
                throw new LlmException(LlmException.Kind.CANCELLED, null);
            }
            listener.onText(text.substring(i, Math.min(text.length(), i + 16)));
        }
        return response;
    }

    @Override
    public List<String> listModels() {
        return List.of(FakeLlm.MODEL);
    }

    @Override
    public boolean supportsStructuredOutput(String model) {
        return config.jsonSchema();
    }

    @Override
    public boolean supportsTools(String model) {
        return config.tools();
    }
}
