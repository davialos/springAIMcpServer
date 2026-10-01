package com.springaimcpservercommon.core.display;

import com.springaimcpservercommon.core.guard.PiiRedactor;
import com.springaimcpservercommon.core.guard.PiiType;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@SuppressWarnings("unchecked")
class StructuredResponseRendererTest {

    private final StructuredResponseRenderer renderer = new StructuredResponseRenderer(PiiRedactor.defaults());
    private final DisplayTemplateParser parser = new DisplayTemplateParser();

    private static final String ANSWER = """
            I found the customer. Contact them at ann@example.com if anything is unclear.
            ```json
            {"customer": {"name": "Ann Lee", "email": "ann@example.com", "phone": "+44 20 7946 0958",
                          "cardNumber": "4111111111111111", "tier": "gold", "internalNote": "VIP"},
             "orders": [{"id": "PO-1", "total": 120.5, "shippedOn": "2026-09-28", "contact": "ann@example.com"},
                        {"id": "PO-2", "total": 80, "shippedOn": null, "contact": "ops"},
                        {"id": "PO-3", "total": 12, "shippedOn": "2026-09-30", "contact": "ops"}]}
            ```
            """;

    private static List<Map<String, Object>> blocks(StructuredResponse r) {
        return (List<Map<String, Object>>) r.tree().get("blocks");
    }

    @Test
    void aTemplateShowsOnlyWhatItNamesWithPersonalDataRemoved() {
        DisplayTemplate template = parser.parse("""
                {"version": 1, "blocks": [
                  {"type": "text", "title": "Answer"},
                  {"type": "fields", "title": "Customer", "source": "customer", "fields": [
                    {"path": "name", "label": "Name"},
                    {"path": "email", "label": "E-mail"},
                    {"path": "cardNumber", "label": "Card"},
                    {"path": "tier", "label": "Tier", "mask": "full"}]},
                  {"type": "table", "title": "Orders", "source": "orders", "maxRows": 2, "columns": [
                    {"path": "id", "label": "Order #"},
                    {"path": "total", "label": "Total", "format": "number"}]}]}
                """);

        StructuredResponse r = renderer.render(template, ANSWER, SensitiveFields.heuristic());
        String json = r.toJson();

        assertThat(json).doesNotContain("ann@example.com", "4111", "7946", "VIP", "internalNote", "gold", "phone");
        List<Map<String, Object>> blocks = blocks(r);
        assertThat(blocks).extracting(b -> b.get("type")).containsExactly("text", "fields", "table");
        assertThat(blocks.get(0).get("text")).isEqualTo(
                "I found the customer. Contact them at [redacted email] if anything is unclear.");
        List<Map<String, Object>> items = (List<Map<String, Object>>) blocks.get(1).get("items");
        assertThat(items).extracting(i -> i.get("value"))
                .containsExactly("Ann Lee", "[redacted email]", StructuredResponseRenderer.MASK,
                        StructuredResponseRenderer.MASK);
        Map<String, Object> table = blocks.get(2);
        assertThat((List<Object>) table.get("rows")).containsExactly(List.of("PO-1", new java.math.BigDecimal("120.5")),
                List.of("PO-2", 80L));
        assertThat(table.get("totalRows")).isEqualTo(3);
        assertThat(table.get("truncated")).isEqualTo(true);
        assertThat(r.redactions()).containsEntry(PiiType.EMAIL, 2);
        assertThat(r.masked()).isEqualTo(2);
    }

    @Test
    void partialMaskKeepsTheLastFourCharacters() {
        DisplayTemplate template = parser.parse("""
                {"version": 1, "blocks": [{"type": "fields", "source": "customer", "fields": [
                  {"path": "phone", "label": "Phone", "mask": "partial"}]}]}
                """);

        StructuredResponse r = renderer.render(template, ANSWER, SensitiveFields.heuristic());

        List<Map<String, Object>> items = (List<Map<String, Object>>) blocks(r).getFirst().get("items");
        assertThat(items.getFirst().get("value")).isEqualTo("••••0958");
    }

    @Test
    void withoutATemplateTheAnswerIsLaidOutAutomatically() {
        StructuredResponse r = renderer.render(null, ANSWER, SensitiveFields.heuristic());
        String json = r.toJson();

        assertThat(json).doesNotContain("ann@example.com", "4111111111111111", "7946 0958");
        List<Map<String, Object>> blocks = blocks(r);
        assertThat(blocks).extracting(b -> b.get("type")).containsExactly("text", "fields", "table");
        assertThat(blocks.get(1).get("title")).isEqualTo("Customer");
        List<Map<String, Object>> items = (List<Map<String, Object>>) blocks.get(1).get("items");
        assertThat(items).extracting(i -> i.get("label"))
                .containsExactly("Name", "Email", "Phone", "Card number", "Tier", "Internal note");
        assertThat(items).extracting(i -> i.get("value"))
                .containsExactly("Ann Lee", "[redacted email]", "[redacted phone]", StructuredResponseRenderer.MASK,
                        "gold", "VIP");
        Map<String, Object> table = blocks.get(2);
        assertThat((List<Map<String, Object>>) table.get("columns")).extracting(c -> c.get("format"))
                .containsExactly("text", "number", "date", "text");
        assertThat(((List<List<Object>>) table.get("rows")).getFirst())
                .containsExactly("PO-1", new java.math.BigDecimal("120.5"), "2026-09-28", "[redacted email]");
    }

    @Test
    void sensitiveCatalogAttributesAreAlwaysMasked() {
        DisplayTemplate template = parser.parse("""
                {"version": 1, "blocks": [{"type": "fields", "source": "customer", "fields": [
                  {"path": "tier", "label": "Tier"}, {"path": "internalNote", "label": "Note"}]}]}
                """);
        SensitiveFields sensitive = SensitiveFields.of(com.springaimcpservercommon.core.guard.GuardFixturesAccess
                .catalogWithSensitive("internalNote"));

        StructuredResponse r = renderer.render(template, ANSWER, sensitive);

        List<Map<String, Object>> items = (List<Map<String, Object>>) blocks(r).getFirst().get("items");
        assertThat(items).extracting(i -> i.get("value")).containsExactly("gold", StructuredResponseRenderer.MASK);
    }

    @Test
    void plainTextBecomesOneRedactedTextBlock() {
        StructuredResponse r = renderer.render(null, "Call +1 415 555 2671 about order 7.", SensitiveFields.heuristic());

        assertThat(blocks(r)).singleElement().satisfies(b -> {
            assertThat(b.get("type")).isEqualTo("text");
            assertThat(b.get("text")).isEqualTo("Call [redacted phone] about order 7.");
        });
        assertThat(r.tree().get("redactions")).isEqualTo(Map.of("PHONE", 1));
    }

    @Test
    void aJsonAnswerIsRedactedInsideItsStructure() {
        var redacted = renderer.redactJson(
                "{\"name\":\"Ann\",\"email\":\"ann@example.com\",\"card\":4111111111111111,\"pin\":\"1234\",\"n\":7}",
                SensitiveFields.heuristic());

        assertThat(AnswerContent.parseJson(redacted.text())).isEqualTo(Map.of("name", "Ann",
                "email", "[redacted email]", "card", "[redacted credit card]", "pin", StructuredResponseRenderer.MASK,
                "n", 7L));
        assertThat(redacted.counts()).containsEntry(PiiType.EMAIL, 1).containsEntry(PiiType.CREDIT_CARD, 1);
        assertThat(redacted.masked()).isEqualTo(1);
    }

    @Test
    void aCleanJsonAnswerIsReturnedUnchanged() {
        String json = "{ \"b\": 1, \"a\": \"open\" }";

        assertThat(renderer.redactJson(json, SensitiveFields.heuristic()).text()).isSameAs(json);
    }

    @Test
    void aTopLevelArrayBecomesATable() {
        StructuredResponse r = renderer.render(null, "[{\"sku\":\"A\",\"qty\":2},{\"sku\":\"B\",\"qty\":1}]",
                SensitiveFields.heuristic());

        assertThat(blocks(r)).singleElement().satisfies(b -> {
            assertThat(b.get("type")).isEqualTo("table");
            assertThat(b.get("rows")).isEqualTo(List.of(List.of("A", 2L), List.of("B", 1L)));
        });
    }
}
