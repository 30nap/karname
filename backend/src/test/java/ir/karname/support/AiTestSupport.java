package ir.karname.support;

import ir.karname.ai.chat.ChatSink;
import ir.karname.ai.llm.fake.FakeLlm;
import ir.karname.ai.provider.AiPreset;
import ir.karname.ai.provider.AiProviderService;
import ir.karname.ai.provider.AiProviderService.ProviderRequest;
import ir.karname.ai.provider.AiProviderService.ProviderView;
import ir.karname.ai.provider.AiProviderService.RouteRequest;
import ir.karname.ai.provider.AiTask;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.stream.Collectors;

/** AI tests run against the offline scripted model. */
public abstract class AiTestSupport extends FinanceTestSupport {

    @Autowired
    protected FakeLlm fake;

    @Autowired
    protected AiProviderService providers;

    @BeforeEach
    void resetFake() {
        fake.reset();
    }

    /** A provider on the offline model serving the given tasks. */
    protected long fakeProvider(boolean tools, AiTask... tasks) {
        ProviderView provider = providers.create(new ProviderRequest("مدل آزمایشی", AiPreset.FAKE, null, null, null, null, null, null, null,
                tools, true, null, null, true));
        providers.saveRoutes(Arrays.stream(tasks).map(t -> new RouteRequest(t, provider.id(), null, null)).toList());
        return provider.id();
    }

    /** Collects what a chat turn reports. */
    public static final class RecordingSink implements ChatSink {

        public record Event(String name, Object data) {
        }

        public final List<Event> events = new CopyOnWriteArrayList<>();
        public volatile boolean closed;

        @Override
        public void send(String event, Object data) {
            events.add(new Event(event, data));
        }

        @Override
        public void close() {
            closed = true;
        }

        public List<String> names() {
            return events.stream().map(Event::name).toList();
        }

        public String text() {
            return events.stream().filter(e -> e.name().equals("text")).map(e -> (String) ((Map<?, ?>) e.data()).get("delta"))
                    .collect(Collectors.joining());
        }

        @SuppressWarnings("unchecked")
        public Map<String, Object> last(String name) {
            return (Map<String, Object>) events.stream().filter(e -> e.name().equals(name)).reduce((a, b) -> b).orElseThrow().data();
        }
    }
}
