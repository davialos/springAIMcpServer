package com.springaimcpservercommon.core.schema;

import com.springaimcpservercommon.annotations.AiContext;
import com.springaimcpservercommon.annotations.AiEntityProperty;
import com.springaimcpservercommon.core.lint.SensitiveNames;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Type;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class JsonSchemaMapperTest {

    private final JsonSchemaMapper mapper = new JsonSchemaMapper(JsonSchemaMapper.Options.defaults());

    enum Status { OPEN, CLOSED }

    @AiContext(description = "A customer order")
    record Order(@AiEntityProperty(meaning = "Order number") long id,
                 Status status,
                 BigDecimal total,
                 Optional<String> note,
                 @AiEntityProperty(meaning = "Card", sensitive = true) String cardNumber,
                 List<Line> lines) {
    }

    record Line(String sku, int quantity) {
    }

    record Node(String name, List<Node> children) {
    }

    record Level3(String value) {
    }

    record Level2(Level3 inner) {
    }

    record Level1(Level2 inner) {
    }

    record Holder(String password, String displayName) {
    }

    static class Pojo {
        private String name;
        private transient String cache;

        public String getName() {
            return name;
        }

        public String getCache() {
            return cache;
        }

        public boolean isActive() {
            return true;
        }
    }

    record Wrapper<T>(T value) {
    }

    private SchemaResult schema(Type type) {
        return mapper.schemaFor(type, Map.of());
    }

    @Test
    void mapsScalars() {
        assertThat(schema(int.class).schema().json()).isEqualTo("{\"format\":\"int32\",\"type\":\"integer\"}");
        assertThat(schema(Long.class).schema().json()).isEqualTo("{\"format\":\"int64\",\"type\":\"integer\"}");
        assertThat(schema(boolean.class).schema().json()).isEqualTo("{\"type\":\"boolean\"}");
        assertThat(schema(String.class).schema().json()).isEqualTo("{\"type\":\"string\"}");
        assertThat(schema(UUID.class).schema().json()).contains("\"format\":\"uuid\"");
        assertThat(schema(Instant.class).schema().json()).contains("\"format\":\"date-time\"");
        assertThat(schema(LocalDate.class).schema().json()).contains("\"format\":\"date\"");
        assertThat(schema(BigDecimal.class).schema().json()).contains("\"format\":\"decimal\"").contains("\"type\":\"string\"");
    }

    @Test
    void mapsEnumsToEnumValues() {
        assertThat(schema(Status.class).schema().json()).isEqualTo("{\"enum\":[\"OPEN\",\"CLOSED\"],\"type\":\"string\"}");
    }

    @Test
    void mapsCollectionsWithMaxItemsAndSetsAsUnique() throws Exception {
        Type listOfLong = JsonSchemaMapperTest.class.getDeclaredMethod("listOfLong").getGenericReturnType();
        SchemaResult list = schema(listOfLong);
        assertThat(list.schema().json()).isEqualTo(
                "{\"items\":{\"format\":\"int64\",\"type\":\"integer\"},\"maxItems\":100,\"type\":\"array\"}");
        assertThat(list.maxNesting()).isEqualTo(1);
        Type setOfString = JsonSchemaMapperTest.class.getDeclaredMethod("setOfString").getGenericReturnType();
        assertThat(schema(setOfString).schema().json()).contains("\"uniqueItems\":true");
        assertThat(schema(String[].class).schema().json()).contains("\"type\":\"array\"");
    }

    @Test
    void mapsRecordsWithDescriptionsAndRemovesSensitiveMembers() {
        SchemaResult result = schema(Order.class);
        Map<String, Object> tree = result.schema().tree();
        assertThat(tree).containsEntry("description", "A customer order");
        @SuppressWarnings("unchecked")
        Map<String, Object> props = (Map<String, Object>) tree.get("properties");
        assertThat(props).containsKeys("id", "status", "total", "note", "lines").doesNotContainKey("cardNumber");
        assertThat(props.get("id").toString()).contains("Order number");
        assertThat(tree.get("required").toString()).contains("id").doesNotContain("note");
        assertThat(result.removedSensitiveMembers()).containsExactly("$.cardNumber");
        assertThat(result.maxNesting()).isEqualTo(3); // Order -> lines[] -> Line
        assertThat(result.complex()).isTrue();
    }

    @Test
    void recursiveTypesUseRefs() {
        SchemaResult result = schema(Node.class);
        String json = result.schema().json();
        assertThat(json).contains("\"$ref\":\"#/$defs/" + Node.class.getName() + "\"");
        assertThat(result.schema().tree()).containsKey("$defs");
    }

    @Test
    void depthLimitStopsExpansion() {
        JsonSchemaMapper shallow = new JsonSchemaMapper(new JsonSchemaMapper.Options(2, 10, SensitiveNames.defaults()));
        SchemaResult result = shallow.schemaFor(Level1.class, Map.of());
        assertThat(result.depthLimitReached()).isTrue();
        assertThat(result.schema().json()).contains("nested too deeply");
    }

    @Test
    void unconfirmedSensitiveNamesAreRemovedUnlessConfirmed() {
        SchemaResult result = schema(Holder.class);
        assertThat(result.unconfirmedSensitiveNames()).containsExactly("$.password");
        assertThat(result.schema().json()).doesNotContain("\"password\"").contains("displayName");

        JsonSchemaMapper confirming = new JsonSchemaMapper(new JsonSchemaMapper.Options(5, 100,
                new SensitiveNames(SensitiveNames.DEFAULT_TERMS, Set.of(Holder.class.getName() + "#password"))));
        assertThat(confirming.schemaFor(Holder.class, Map.of()).schema().json()).contains("\"password\"");
    }

    @Test
    void mapsPojoPropertiesAndSkipsTransientFields() {
        String json = schema(Pojo.class).schema().json();
        assertThat(json).contains("\"name\"").contains("\"active\"").doesNotContain("cache");
    }

    @Test
    void mapsAndPolymorphicTypesAreFlagged() throws Exception {
        Type map = JsonSchemaMapperTest.class.getDeclaredMethod("mapOfString").getGenericReturnType();
        SchemaResult result = schema(map);
        assertThat(result.containsMap()).isTrue();
        assertThat(result.schema().json()).contains("additionalProperties");
        assertThat(schema(Runnable.class).polymorphic()).isTrue();
    }

    @Test
    void resolvesGenericTypeArguments() throws Exception {
        Type wrapper = JsonSchemaMapperTest.class.getDeclaredMethod("wrappedDate").getGenericReturnType();
        assertThat(schema(wrapper).schema().json()).contains("\"format\":\"date\"");
    }

    @Test
    void inputSchemaListsParametersRequiredAndDescriptions() {
        SchemaResult result = mapper.inputSchema(List.of(
                new ParameterSpec("customerId", Long.class, "Id of the customer", true),
                new ParameterSpec("status", Status.class, null, false)), Map.of());
        String json = result.schema().json();
        assertThat(json).startsWith("{\"additionalProperties\":false,\"properties\":{\"customerId\":{\"description\":\"Id of the customer\"");
        assertThat(json).contains("\"required\":[\"customerId\"]");
        assertThat(result.complex()).isFalse();
    }

    @Test
    void schemaTextIsCanonicalAndStable() {
        assertThat(schema(Order.class).schema()).isEqualTo(schema(Order.class).schema());
        assertThat(schema(Order.class).schema().hash()).startsWith("sha256:");
    }

    @SuppressWarnings("unused")
    private static List<Long> listOfLong() {
        return List.of();
    }

    @SuppressWarnings("unused")
    private static Set<String> setOfString() {
        return Set.of();
    }

    @SuppressWarnings("unused")
    private static Map<String, String> mapOfString() {
        return Map.of();
    }

    @SuppressWarnings("unused")
    private static Wrapper<LocalDate> wrappedDate() {
        return new Wrapper<>(LocalDate.now());
    }
}
