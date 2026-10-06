package ir.karname.pricefeed;

import java.util.List;
import java.util.Map;

/** Everything a provider needs to read a source, with header values decrypted. */
public record SourceConfig(PriceSourceKind kind, String url, Map<String, String> headers, PriceUnit unit, List<PriceMapping> mappings) {
}
