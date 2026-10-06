package ir.karname.common.config;

import org.springframework.boot.jackson.autoconfigure.JsonMapperBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import tools.jackson.core.JsonGenerator;
import tools.jackson.databind.SerializationContext;
import tools.jackson.databind.module.SimpleModule;
import tools.jackson.databind.ser.std.StdSerializer;

import java.math.BigDecimal;

@Configuration(proxyBeanMethods = false)
public class JacksonConfig {

    /**
     * Monetary values are serialized as plain strings ("2500000", "0.00012") so JavaScript
     * clients never round them through IEEE-754 doubles.
     */
    @Bean
    JsonMapperBuilderCustomizer decimalsAsStrings() {
        SimpleModule module = new SimpleModule("karname-decimals");
        module.addSerializer(BigDecimal.class, new PlainBigDecimalSerializer());
        return builder -> builder.addModule(module);
    }

    static final class PlainBigDecimalSerializer extends StdSerializer<BigDecimal> {

        PlainBigDecimalSerializer() {
            super(BigDecimal.class);
        }

        @Override
        public void serialize(BigDecimal value, JsonGenerator gen, SerializationContext ctxt) {
            BigDecimal normalized = value.signum() == 0 ? BigDecimal.ZERO : value.stripTrailingZeros();
            gen.writeString(normalized.toPlainString());
        }
    }
}
