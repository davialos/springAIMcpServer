package com.springaimcpservercommon.autoconfigure;

import com.springaimcpservercommon.ai.tool.ToolResultEnvelope;
import com.springaimcpservercommon.query.ast.QueryParam;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class BackingToolCallbackTest {

    @Test
    void aQueryResultBecomesRowsWithPagingInfo() {
        ToolResultEnvelope e = BackingToolCallback.shapeQueryResult("find_orders",
                "{\"hasMore\":true,\"nextCursor\":\"abc\",\"rowCount\":2,\"rows\":[{\"id\":1},{\"id\":2}]}");

        assertThat(e.count()).isEqualTo(2);
        assertThat(e.hasMore()).isTrue();
        assertThat(e.nextCursor()).isEqualTo("abc");
        assertThat(e.status().name()).isEqualTo("OK");
    }

    @Test
    void anEmptyQueryResultIsEmptyNotAnError() {
        ToolResultEnvelope e = BackingToolCallback.shapeQueryResult("find_orders",
                "{\"hasMore\":false,\"rowCount\":0,\"rows\":[]}");
        assertThat(e.status().name()).isEqualTo("EMPTY");
    }

    @Test
    void anOperationResultIsWrappedAsRows() {
        assertThat(BackingToolCallback.shapeOperationResult("op_tool", "[1,2,3]").count()).isEqualTo(3);
        assertThat(BackingToolCallback.shapeOperationResult("op_tool", "{\"a\":1}").count()).isEqualTo(1);
        assertThat(BackingToolCallback.shapeOperationResult("op_tool", "null").status().name()).isEqualTo("EMPTY");
    }

    @Test
    void theQuerySchemaListsEveryParameterAndTheRequiredOnes() {
        String schema = BackingToolCallback.querySchema(null);
        assertThat(schema).isEqualTo("{\"additionalProperties\":false,\"properties\":{},\"type\":\"object\"}");
        assertThat(new QueryParam("status", "{\"type\":\"string\"}", true, null).required()).isTrue();
    }
}
