package ir.karname.ai.llm;

import ir.karname.common.persian.PersianNumbers;
import ir.karname.common.persian.PersianText;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * The subset of JSON Schema used for tool inputs and structured outputs. What is sent to providers
 * stays within what strict tool use and structured outputs accept (every object closed with
 * {@code additionalProperties: false}, no numeric or length constraints); bounds are checked here
 * instead, because a model's arguments are validated before anything runs.
 */
public final class JsonSchema {

    public enum Type {
        OBJECT, ARRAY, STRING, INTEGER, NUMBER, BOOLEAN
    }

    private final Type type;
    private final String description;
    private final Map<String, JsonSchema> properties;
    private final Set<String> required;
    private final JsonSchema items;
    private final List<String> values;
    private final BigDecimal minimum;
    private final BigDecimal maximum;
    private final Integer maxLength;
    private final Integer maxItems;

    private JsonSchema(Type type, String description, Map<String, JsonSchema> properties, Set<String> required, JsonSchema items,
            List<String> values, BigDecimal minimum, BigDecimal maximum, Integer maxLength, Integer maxItems) {
        this.type = type;
        this.description = description;
        this.properties = properties;
        this.required = required;
        this.items = items;
        this.values = values;
        this.minimum = minimum;
        this.maximum = maximum;
        this.maxLength = maxLength;
        this.maxItems = maxItems;
    }

    private static JsonSchema of(Type type, String description) {
        return new JsonSchema(type, description, Map.of(), Set.of(), null, List.of(), null, null, null, null);
    }

    public static JsonSchema string(String description) {
        return of(Type.STRING, description);
    }

    public static JsonSchema integer(String description) {
        return of(Type.INTEGER, description);
    }

    public static JsonSchema number(String description) {
        return of(Type.NUMBER, description);
    }

    public static JsonSchema bool(String description) {
        return of(Type.BOOLEAN, description);
    }

    public static JsonSchema enumOf(String description, List<String> values) {
        return new JsonSchema(Type.STRING, description, Map.of(), Set.of(), null, List.copyOf(values), null, null, null, null);
    }

    public static JsonSchema array(String description, JsonSchema items) {
        return new JsonSchema(Type.ARRAY, description, Map.of(), Set.of(), items, List.of(), null, null, null, null);
    }

    public static ObjectBuilder object(String description) {
        return new ObjectBuilder(description);
    }

    /** An object with no properties, for tools that take no input. */
    public static JsonSchema empty() {
        return new ObjectBuilder(null).build();
    }

    public JsonSchema min(long value) {
        return new JsonSchema(type, description, properties, required, items, values, BigDecimal.valueOf(value), maximum, maxLength, maxItems);
    }

    public JsonSchema max(long value) {
        return new JsonSchema(type, description, properties, required, items, values, minimum, BigDecimal.valueOf(value), maxLength, maxItems);
    }

    public JsonSchema maxLength(int value) {
        return new JsonSchema(type, description, properties, required, items, values, minimum, maximum, value, maxItems);
    }

    public JsonSchema maxItems(int value) {
        return new JsonSchema(type, description, properties, required, items, values, minimum, maximum, maxLength, value);
    }

    public Type type() {
        return type;
    }

    public Map<String, JsonSchema> properties() {
        return properties;
    }

    public Set<String> required() {
        return required;
    }

    public JsonSchema items() {
        return items;
    }

    public List<String> values() {
        return values;
    }

    public static final class ObjectBuilder {

        private final String description;
        private final Map<String, JsonSchema> properties = new LinkedHashMap<>();
        private final Set<String> required = new LinkedHashSet<>();

        private ObjectBuilder(String description) {
            this.description = description;
        }

        public ObjectBuilder required(String name, JsonSchema schema) {
            properties.put(name, schema);
            required.add(name);
            return this;
        }

        public ObjectBuilder optional(String name, JsonSchema schema) {
            properties.put(name, schema);
            return this;
        }

        public JsonSchema build() {
            return new JsonSchema(Type.OBJECT, description, Collections.unmodifiableMap(new LinkedHashMap<>(properties)),
                    Collections.unmodifiableSet(new LinkedHashSet<>(required)), null, List.of(), null, null, null, null);
        }
    }

    /** The schema as sent to a provider, with keys in a fixed order so requests stay byte-identical. */
    public Map<String, Object> toMap() {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("type", type.name().toLowerCase());
        if (description != null) {
            map.put("description", description);
        }
        if (!values.isEmpty()) {
            map.put("enum", values);
        }
        if (type == Type.OBJECT) {
            Map<String, Object> props = new LinkedHashMap<>();
            properties.forEach((name, schema) -> props.put(name, schema.toMap()));
            map.put("properties", props);
            map.put("required", List.copyOf(required));
            map.put("additionalProperties", false);
        }
        if (type == Type.ARRAY) {
            map.put("items", items.toMap());
        }
        return map;
    }

    /**
     * Repairs what weaker models commonly get wrong without changing meaning: numbers sent as text
     * (including Persian digits and thousands separators), booleans as text, a single object where
     * an array was expected. The result is then {@link #validate validated}.
     */
    public JsonNode coerce(JsonNode node) {
        if (node == null || node.isNull() || node.isMissingNode()) {
            return node;
        }
        JsonNodeFactory nodes = JsonNodeFactory.instance;
        return switch (type) {
            case OBJECT -> {
                if (!node.isObject()) {
                    yield node;
                }
                ObjectNode copy = nodes.objectNode();
                node.properties().forEach(e -> {
                    JsonSchema property = properties.get(e.getKey());
                    // an explicit null for an optional property means "not given"
                    if (property != null && e.getValue().isNull() && !required.contains(e.getKey())) {
                        return;
                    }
                    copy.set(e.getKey(), property == null ? e.getValue() : property.coerce(e.getValue()));
                });
                yield copy;
            }
            case ARRAY -> {
                ArrayNode array = nodes.arrayNode();
                if (node.isArray()) {
                    node.forEach(item -> array.add(items.coerce(item)));
                } else if (node.isObject() && items.type == Type.OBJECT) {
                    array.add(items.coerce(node));
                } else {
                    yield node;
                }
                yield array;
            }
            case INTEGER, NUMBER -> {
                if (node.isString()) {
                    Optional<BigDecimal> parsed = PersianNumbers.parseDecimal(node.asString());
                    if (parsed.isPresent()) {
                        yield nodes.numberNode(parsed.get());
                    }
                }
                yield node;
            }
            case BOOLEAN -> {
                if (node.isString() && (node.asString().equalsIgnoreCase("true") || node.asString().equalsIgnoreCase("false"))) {
                    yield nodes.booleanNode(Boolean.parseBoolean(node.asString().toLowerCase()));
                }
                yield node;
            }
            case STRING -> {
                if (!values.isEmpty() && node.isString()) {
                    String upper = PersianText.normalizeDigits(node.asString().trim());
                    for (String v : values) {
                        if (v.equalsIgnoreCase(upper)) {
                            yield nodes.stringNode(v);
                        }
                    }
                }
                yield node.isNumber() ? nodes.stringNode(node.asString()) : node;
            }
        };
    }

    /** Problems with {@code node}, as {@code path: message}; empty when it conforms. */
    public List<String> validate(JsonNode node) {
        List<String> errors = new ArrayList<>();
        validate(node, "$", errors);
        return errors;
    }

    private void validate(JsonNode node, String path, List<String> errors) {
        if (node == null || node.isMissingNode() || node.isNull()) {
            errors.add(path + ": value is required");
            return;
        }
        switch (type) {
            case OBJECT -> {
                if (!node.isObject()) {
                    errors.add(path + ": expected an object");
                    return;
                }
                for (String name : required) {
                    if (!node.has(name) || node.get(name).isNull()) {
                        errors.add(path + "." + name + ": required");
                    }
                }
                for (Map.Entry<String, JsonNode> e : node.properties()) {
                    JsonSchema property = properties.get(e.getKey());
                    if (property == null) {
                        errors.add(path + "." + e.getKey() + ": unknown property");
                    } else if (!e.getValue().isNull() || required.contains(e.getKey())) {
                        property.validate(e.getValue(), path + "." + e.getKey(), errors);
                    }
                }
            }
            case ARRAY -> {
                if (!node.isArray()) {
                    errors.add(path + ": expected an array");
                    return;
                }
                if (maxItems != null && node.size() > maxItems) {
                    errors.add(path + ": at most " + maxItems + " items");
                }
                for (int i = 0; i < node.size(); i++) {
                    items.validate(node.get(i), path + "[" + i + "]", errors);
                }
            }
            case STRING -> {
                if (!node.isString()) {
                    errors.add(path + ": expected a string");
                    return;
                }
                if (!values.isEmpty() && !values.contains(node.asString())) {
                    errors.add(path + ": must be one of " + values);
                }
                if (maxLength != null && node.asString().length() > maxLength) {
                    errors.add(path + ": at most " + maxLength + " characters");
                }
            }
            case INTEGER, NUMBER -> {
                if (!node.isNumber() || (type == Type.INTEGER && !isIntegral(node))) {
                    errors.add(path + ": expected " + (type == Type.INTEGER ? "an integer" : "a number"));
                    return;
                }
                BigDecimal value = node.decimalValue();
                if (minimum != null && value.compareTo(minimum) < 0) {
                    errors.add(path + ": must be at least " + minimum.toPlainString());
                }
                if (maximum != null && value.compareTo(maximum) > 0) {
                    errors.add(path + ": must be at most " + maximum.toPlainString());
                }
            }
            case BOOLEAN -> {
                if (!node.isBoolean()) {
                    errors.add(path + ": expected true or false");
                }
            }
        }
    }

    private static boolean isIntegral(JsonNode node) {
        if (node.isIntegralNumber()) {
            return true;
        }
        BigDecimal value = node.decimalValue();
        return value.signum() == 0 || value.stripTrailingZeros().scale() <= 0;
    }
}
