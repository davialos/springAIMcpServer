package com.springaimcpservercommon.core.display;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DisplayTemplateParserTest {

    private final DisplayTemplateParser parser = new DisplayTemplateParser();

    @Test
    void parsesAllNodeTypes() {
        DisplayTemplate t = parser.parse("""
                {"version": 1, "blocks": [
                  {"type": "text", "title": "Summary"},
                  {"type": "fields", "title": "Customer", "source": "customer",
                   "fields": [{"path": "name", "label": "Name"}, {"path": "cardNumber", "mask": "partial"}]},
                  {"type": "section", "title": "History", "blocks": [
                    {"type": "table", "source": "orders", "maxRows": 5,
                     "columns": [{"path": "id", "label": "Order #"}, {"path": "total", "format": "number"}]}]}]}
                """);

        assertThat(t.blocks()).hasSize(3);
        assertThat(t.blocks().get(0)).isEqualTo(new DisplayNode.Text("Summary"));
        assertThat(t.blocks().get(1)).isInstanceOfSatisfying(DisplayNode.Fields.class, f -> {
            assertThat(f.source()).isEqualTo("customer");
            assertThat(f.fields()).containsExactly(
                    new FieldSpec("name", "Name", DisplayFormat.TEXT, DisplayMask.NONE),
                    new FieldSpec("cardNumber", "Card number", DisplayFormat.TEXT, DisplayMask.PARTIAL));
        });
        assertThat(t.blocks().get(2)).isInstanceOfSatisfying(DisplayNode.Section.class, s ->
                assertThat(s.children().getFirst()).isInstanceOfSatisfying(DisplayNode.Table.class, tb -> {
                    assertThat(tb.maxRows()).isEqualTo(5);
                    assertThat(tb.columns().get(1)).isEqualTo(
                            new FieldSpec("total", "Total", DisplayFormat.NUMBER, DisplayMask.NONE));
                }));
    }

    @Test
    void reportsEveryProblemWithItsLocation() {
        assertThatThrownBy(() -> parser.parse("""
                {"version": 2, "extra": true, "blocks": [
                  {"type": "chart"},
                  {"type": "table", "source": "a b", "maxRows": 0, "columns": []},
                  {"type": "fields", "colour": "red", "fields": [{"label": "No path", "mask": "blur"}]},
                  {"type": "section", "blocks": [{"type": "text"}]}]}
                """))
                .isInstanceOfSatisfying(DisplayTemplateException.class, e -> assertThat(e.errors()).contains(
                        "$.extra: unknown property",
                        "$.version: must be 1",
                        "$.blocks[0].type: must be one of text, fields, table, section",
                        "$.blocks[1].source: must be $ or a dot path of letters, digits, '_' and '-'",
                        "$.blocks[1].maxRows: must be an integer between 1 and 1000",
                        "$.blocks[1].columns: must be a non-empty array",
                        "$.blocks[2].colour: unknown property for type fields",
                        "$.blocks[2].fields[0].path: is required",
                        "$.blocks[2].fields[0].mask: unknown value 'blur'",
                        "$.blocks[3].title: is required"));
    }

    @Test
    void rejectsInvalidJsonAndDeepNesting() {
        assertThatThrownBy(() -> parser.parse("{not json")).isInstanceOf(DisplayTemplateException.class);
        String deep = "{\"type\":\"text\"}";
        for (int i = 0; i < DisplayTemplateParser.MAX_DEPTH + 1; i++) {
            deep = "{\"type\":\"section\",\"title\":\"s\",\"blocks\":[" + deep + "]}";
        }
        String json = "{\"version\":1,\"blocks\":[" + deep + "]}";
        assertThatThrownBy(() -> parser.parse(json)).isInstanceOfSatisfying(DisplayTemplateException.class,
                e -> assertThat(e.errors()).anyMatch(s -> s.contains("nested deeper than")));
    }

    @Test
    void humanizesKeys() {
        assertThat(DisplayTemplateParser.humanize("customer.firstName")).isEqualTo("First name");
        assertThat(DisplayTemplateParser.humanize("grand_total")).isEqualTo("Grand total");
        assertThat(DisplayTemplateParser.humanize("$")).isEqualTo("Value");
    }
}
