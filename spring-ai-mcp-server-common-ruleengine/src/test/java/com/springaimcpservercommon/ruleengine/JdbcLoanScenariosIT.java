package com.springaimcpservercommon.ruleengine;

import com.springaimcpservercommon.ruleengine.store.JdbcRuleStore;
import com.springaimcpservercommon.ruleengine.store.RuleStore;
import com.springaimcpservercommon.ruleengine.support.RuleEngineTestDatabase;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;

/** The loan scenarios on real PostgreSQL: V11 schema + {@code sample-data.sql} read through {@link JdbcRuleStore}. */
class JdbcLoanScenariosIT extends LoanScenarios {

    private static RuleEngineTestDatabase db;

    @BeforeAll
    static void setUp() {
        db = RuleEngineTestDatabase.create(true);
    }

    @AfterAll
    static void tearDown() {
        db.close();
    }

    @Override
    protected RuleStore store() {
        return new JdbcRuleStore(db.dataSource(), db.schema());
    }
}
