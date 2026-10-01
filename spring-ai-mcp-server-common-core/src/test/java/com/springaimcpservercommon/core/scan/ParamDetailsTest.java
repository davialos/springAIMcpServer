package com.springaimcpservercommon.core.scan;

import com.example.host.DetailsFixtures;
import com.springaimcpservercommon.core.catalog.OperationDescriptor;
import com.springaimcpservercommon.core.catalog.ParamDescriptor;
import com.springaimcpservercommon.core.catalog.ScanIssueCode;
import com.springaimcpservercommon.core.catalog.ScannedCatalog;
import org.junit.jupiter.api.Test;
import org.springframework.context.support.GenericApplicationContext;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ParamDetailsTest {

    private static ScannedCatalog scan() {
        try (GenericApplicationContext ctx = new GenericApplicationContext()) {
            ctx.registerBean("invoiceService", DetailsFixtures.InvoiceService.class);
            ctx.refresh();
            return new SpringBeanOperationScanner(ScanOptions.defaults(List.of("com.example.host")),
                    Clock.fixed(Instant.parse("2026-09-30T10:00:00Z"), ZoneOffset.UTC)).scan(ctx);
        }
    }

    @Test
    void detailsAndExamplesAreKeptAndReachTheModelInTheParameterDescription() {
        OperationDescriptor op = scan().operationsByToolName("find_invoices").getFirst();
        ParamDescriptor customer = op.params().getFirst();
        assertThat(customer.description()).isEqualTo("Customer number");
        assertThat(customer.details()).contains("not the invoice number");
        assertThat(customer.examples()).containsExactly("C-1001", "C-2087");
        assertThat(op.inputSchema().json()).contains("Customer number This is the number printed on the "
                + "customer's card, not the invoice number.").contains("Examples: C-1001; C-2087.");
    }

    @Test
    void aSensitiveParameterKeepsItsNotesButNeverItsExamples() {
        ParamDescriptor code = scan().operationsByToolName("find_invoices").getFirst().params().get(1);
        assertThat(code.sensitive()).isTrue();
        assertThat(code.details()).isEqualTo("Never repeat it.");
        assertThat(code.examples()).isEmpty();
        assertThat(scan().operationsByToolName("find_invoices").getFirst().inputSchema().json())
                .doesNotContain("s3cret-value");
    }

    @Test
    void moreThanFiveExamplesExcludeTheActionWithAnIssue() {
        ScannedCatalog catalog = scan();
        assertThat(catalog.operationsByToolName("too_many")).isEmpty();
        assertThat(catalog.issues()).anyMatch(i -> i.code() == ScanIssueCode.DESCRIPTION_TOO_LONG
                && i.message().contains("more than 5 examples"));
    }
}
