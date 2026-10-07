package com.springaimcpservercommon.ruleengine;

import com.springaimcpservercommon.ruleengine.store.RuleStore;
import com.springaimcpservercommon.ruleengine.support.InMemoryRuleStore;

class InMemoryLoanScenariosTest extends LoanScenarios {

    @Override
    protected RuleStore store() {
        return new InMemoryRuleStore();
    }
}
