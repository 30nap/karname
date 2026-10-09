package ir.karname.common;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;

class OutboundUrlsTest {

    @ParameterizedTest
    @ValueSource(strings = {"https://api.anthropic.com", "http://localhost:11434/v1", "http://192.168.1.20:11434/v1",
            "http://ollama:11434/v1", "https://example.com/prices.json?symbol=usd", "http://[::1]:8080/v1"})
    void servicesOnTheInternetThisMachineOrTheLanAreAllowed(String url) {
        assertThat(OutboundUrls.isAllowed(url)).isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {"http://169.254.169.254/latest/meta-data/", "http://169.254.43518/", "http://2852039166/", "http://0xa9fea9fe/",
            "http://[fe80::1]/", "http://[::ffff:169.254.169.254]/", "http://metadata.google.internal/computeMetadata/v1/",
            "http://0.0.0.0:8080/", "ftp://example.com/", "file:///etc/passwd", "https://user:secret@example.com/", "not a url", ""})
    void metadataServicesAndOddAddressesAreRefused(String url) {
        assertThat(OutboundUrls.isAllowed(url)).isFalse();
    }
}
