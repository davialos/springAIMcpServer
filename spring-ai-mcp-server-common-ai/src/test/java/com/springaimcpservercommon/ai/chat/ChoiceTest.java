package com.springaimcpservercommon.ai.chat;

import com.springaimcpservercommon.core.guard.PiiRedactor;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ChoiceTest {

    private final PiiRedactor redactor = PiiRedactor.defaults();

    private Choice single() {
        return Choice.fromToolArguments("choice-1", """
                {"question": "Which order?", "allowOther": true, "options": [
                  {"value": "PO-1", "label": "PO-1 (open)", "description": "Placed 2 Oct"},
                  {"value": "PO-2", "label": "PO-2 (shipped)"}]}""", redactor);
    }

    @Test
    void buildsAChoiceAndItsPayloadRoundTrips() {
        Choice c = single();

        assertThat(c.options()).extracting(Choice.Option::value).containsExactly("PO-1", "PO-2");
        assertThat(c.multiple()).isFalse();
        assertThat(Choice.fromPayloadJson(c.toPayloadJson())).isEqualTo(c);
        assertThat(c.toPayloadJson()).contains("\"componentId\":\"choice-1\"", "\"question\":\"Which order?\"");
    }

    @Test
    void personalDataInTheQuestionAndOptionsIsRedacted() {
        Choice c = Choice.fromToolArguments("choice-1", """
                {"question": "Email ann@acme.io or call?", "options": [{"label": "ann@acme.io"}, {"label": "Call"}]}""",
                redactor);

        assertThat(c.question()).isEqualTo("Email [redacted email] or call?");
        assertThat(c.options().getFirst().label()).isEqualTo("[redacted email]");
        assertThat(c.options().getFirst().value()).isEqualTo("[redacted email]");
    }

    @Test
    void rejectsInvalidArguments() {
        assertThatThrownBy(() -> Choice.fromToolArguments("c", "{\"question\":\"q\",\"options\":[{\"label\":\"a\"}]}",
                redactor)).hasMessageContaining("2 to 12");
        assertThatThrownBy(() -> Choice.fromToolArguments("c", "{\"options\":[{\"label\":\"a\"},{\"label\":\"b\"}]}",
                redactor)).hasMessageContaining("question is required");
        assertThatThrownBy(() -> Choice.fromToolArguments("c",
                "{\"question\":\"q\",\"options\":[{\"label\":\"a\"},{\"label\":\"a\"}]}", redactor))
                .hasMessageContaining("unique");
        assertThatThrownBy(() -> Choice.fromToolArguments("c", "not json", redactor))
                .hasMessageContaining("JSON object");
    }

    @Test
    void validatesAnswers() {
        Choice c = single();

        assertThat(c.validate(List.of("PO-2"), null, redactor))
                .isEqualTo(new Choice.Answer(List.of("PO-2"), List.of("PO-2 (shipped)"), null));
        assertThat(c.validate(List.of(), "the other one, mail bob@x.io", redactor).other())
                .isEqualTo("the other one, mail [redacted email]");
        assertThatThrownBy(() -> c.validate(List.of("PO-1", "PO-2"), null, redactor)).hasMessageContaining("only one");
        assertThatThrownBy(() -> c.validate(List.of("PO-9"), null, redactor)).hasMessageContaining("unknown option");
        assertThatThrownBy(() -> c.validate(List.of(), null, redactor)).hasMessageContaining("select an option");
    }

    @Test
    void multipleSelectionsComeBackInOptionOrder() {
        Choice c = Choice.fromToolArguments("c", """
                {"question": "Which?", "multiple": true, "options": [{"label": "A"}, {"label": "B"}, {"label": "C"}]}""",
                redactor);

        Choice.Answer a = c.validate(List.of("C", "A"), null, redactor);

        assertThat(a.values()).containsExactly("A", "C");
        assertThat(a.asMessage(c.question())).isEqualTo("My answer to \"Which?\": A, C");
        assertThatThrownBy(() -> c.validate(List.of(), "typed", redactor)).hasMessageContaining("free-text");
    }

    @Test
    void theToolShowsValidChoicesAndTellsTheModelAboutInvalidOnes() {
        List<Choice> shown = new CopyOnWriteArrayList<>();
        PresentChoicesTool tool = new PresentChoicesTool(redactor, shown::add);

        String ok = tool.call("{\"question\":\"Which?\",\"options\":[{\"label\":\"A\"},{\"label\":\"B\"}]}");
        String bad = tool.call("{\"question\":\"Which?\",\"options\":[]}");

        assertThat(ok).contains("\"status\":\"shown\"", "choice-1");
        assertThat(bad).contains("\"status\":\"error\"", "2 to 12");
        assertThat(shown).singleElement().extracting(Choice::componentId).isEqualTo("choice-1");
    }
}
