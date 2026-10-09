package com.springaimcpservercommon.celfaker.importer;

import com.springaimcpservercommon.celfaker.contract.ApiSpec;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CurlParserTest {

    @Test
    void parsesAPostWithJsonBodyAndKeepsNoSecrets() {
        Imported i = CurlParser.parse("""
                curl -X POST 'https://api.example.com:8443/v1/orders?dryRun=true' \\
                  -H 'Content-Type: application/json' \\
                  -H "Authorization: Bearer abc.def.ghi" \\
                  -H 'X-Api-Key: s3cr3t' \\
                  --data-raw '{"customerId": 7, "items": [{"sku": "A-1", "qty": 2}], "note": "it'"'"'s fine"}'
                """);
        ApiSpec api = i.apis().getFirst();
        assertThat(i.baseUrl()).isEqualTo("https://api.example.com:8443");
        assertThat(api.method()).isEqualTo("POST");
        assertThat(api.path()).isEqualTo("/v1/orders?dryRun=true");
        assertThat(api.id()).isEqualTo("postV1Orders");
        assertThat(api.requestExample().path("customerId").asInt()).isEqualTo(7);
        assertThat(api.requestExample().path("note").asString()).isEqualTo("it's fine");
        assertThat(api.headers()).containsEntry("Authorization", "Bearer {{env.AUTHORIZATION}}")
                .containsEntry("X-Api-Key", "{{env.X_API_KEY}}").containsEntry("Content-Type", "application/json");
        assertThat(api.headers().values()).noneMatch(v -> v.contains("abc.def") || v.contains("s3cr3t"));
        assertThat(i.warnings()).hasSize(2);
    }

    @Test
    void methodDefaultsFollowTheBody() {
        assertThat(CurlParser.parse("curl http://localhost:8080/api/items/42").apis().getFirst().method()).isEqualTo("GET");
        assertThat(CurlParser.parse("curl localhost:8080/items -d '{\"a\":1}'").apis().getFirst().method()).isEqualTo("POST");
        assertThat(CurlParser.parse("curl -s -L --compressed -k -X DELETE http://h/items/1").apis().getFirst().method()).isEqualTo("DELETE");
    }

    @Test
    void getFlagTurnsDataIntoQuery() {
        ApiSpec api = CurlParser.parse("curl -G http://h/search -d q=cats -d page=2").apis().getFirst();
        assertThat(api.method()).isEqualTo("GET");
        assertThat(api.path()).isEqualTo("/search?q=cats&page=2");
    }

    @Test
    void basicAuthBecomesAPlaceholder() {
        Imported i = CurlParser.parse("curl -u admin:hunter2 http://h/x");
        assertThat(i.apis().getFirst().headers().get("Authorization")).isEqualTo("Basic {{env.BASIC_AUTH}}");
        assertThat(i.warnings().toString()).doesNotContain("hunter2");
    }

    @Test
    void rejectsGarbage() {
        assertThatThrownBy(() -> CurlParser.parse("curl -X POST")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> CurlParser.parse("curl 'http://h/unterminated")).isInstanceOf(IllegalArgumentException.class);
    }
}
