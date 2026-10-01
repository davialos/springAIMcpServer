package com.example.host;

import com.springaimcpservercommon.annotations.AiContext;
import com.springaimcpservercommon.annotations.AiExposedAction;
import com.springaimcpservercommon.annotations.AiParam;

/** Actions whose parameters carry plain-English guidance for the AI. */
public class DetailsFixtures {

    /** A service with documented parameters. */
    @AiContext(description = "Invoices")
    public static class InvoiceService {

        @AiExposedAction(intent = "Finds the invoices of one customer")
        public String findInvoices(
                @AiParam(description = "Customer number",
                        details = "This is the number printed on the customer's card, not the invoice number. "
                                + "It always starts with C- followed by digits.",
                        examples = {"C-1001", "C-2087"}) String customerNo,
                @AiParam(description = "Access code", sensitive = true, details = "Never repeat it.",
                        examples = {"s3cret-value"}) String code) {
            return "";
        }

        @AiExposedAction(intent = "Too many examples")
        public String tooMany(@AiParam(description = "x", examples = {"1", "2", "3", "4", "5", "6"}) String x) {
            return "";
        }
    }
}
