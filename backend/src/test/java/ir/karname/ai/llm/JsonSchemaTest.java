package ir.karname.ai.llm;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class JsonSchemaTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private static final JsonSchema SCHEMA = JsonSchema.object("A search")
            .required("query", JsonSchema.string("Words to find").maxLength(20))
            .optional("limit", JsonSchema.integer("How many").min(1).max(50))
            .optional("kind", JsonSchema.enumOf("Kind", List.of("INCOME", "EXPENSE")))
            .optional("ids", JsonSchema.array("Ids", JsonSchema.integer(null)).maxItems(3))
            .optional("exact", JsonSchema.bool(null))
            .build();

    @Test
    void rendersAClosedSchemaWithoutBoundsForProviders() {
        Map<String, Object> map = SCHEMA.toMap();
        assertThat(map).containsEntry("type", "object").containsEntry("additionalProperties", false).containsEntry("required", List.of("query"));
        @SuppressWarnings("unchecked")
        Map<String, Object> limit = (Map<String, Object>) ((Map<String, Object>) map.get("properties")).get("limit");
        assertThat(limit).containsOnlyKeys("type", "description");
        assertThat(JSON.writeValueAsString(map)).startsWith("{\"type\":\"object\",\"description\":\"A search\",\"properties\":{\"query\"");
    }

    @Test
    void repairsCommonMistakesOfWeakerModels() {
        JsonNode input = JSON.readTree("{\"query\":\"اسنپ\",\"limit\":\"۱۰\",\"kind\":\"expense\",\"ids\":7,\"exact\":\"true\"}");
        JsonNode fixed = SCHEMA.coerce(input);
        assertThat(fixed.get("limit").asInt()).isEqualTo(10);
        assertThat(fixed.get("kind").asString()).isEqualTo("EXPENSE");
        assertThat(fixed.get("exact").asBoolean()).isTrue();
        assertThat(SCHEMA.validate(fixed)).containsExactly("$.ids: expected an array");

        JsonNode nullOptional = SCHEMA.coerce(JSON.readTree("{\"query\":\"x\",\"limit\":null}"));
        assertThat(nullOptional.has("limit")).isFalse();
        assertThat(SCHEMA.validate(nullOptional)).isEmpty();
    }

    @Test
    void reportsEveryViolation() {
        JsonNode input = JSON.readTree("{\"limit\":0.5,\"kind\":\"OTHER\",\"ids\":[1,2,3,4],\"extra\":1,\"query\":\"a very long query text here\"}");
        assertThat(SCHEMA.validate(input)).containsExactlyInAnyOrder(
                "$.query: at most 20 characters",
                "$.limit: expected an integer",
                "$.kind: must be one of [INCOME, EXPENSE]",
                "$.ids: at most 3 items",
                "$.extra: unknown property");
        assertThat(SCHEMA.validate(JSON.readTree("{\"query\":\"x\",\"limit\":51}"))).containsExactly("$.limit: must be at most 50");
        assertThat(SCHEMA.validate(JSON.readTree("{}"))).containsExactly("$.query: required");
    }

    @Test
    void readsJsonFromChattyReplies() {
        assertThat(StructuredReply.parse("```json\n{\"a\": 1}\n```")).get().extracting(n -> n.get("a").asInt()).isEqualTo(1);
        assertThat(StructuredReply.parse("بفرمایید: {\"a\": 2} موفق باشید")).get().extracting(n -> n.get("a").asInt()).isEqualTo(2);
        assertThat(StructuredReply.parse("no json here")).isEmpty();
    }
}
