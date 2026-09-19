package ai.lambda.agent.core;

import java.util.List;

public final class AgentEvaluator {
    public List<EvaluationResult> evaluate(Agent agent, List<EvaluationCase> cases) {
        return cases.stream().map(test -> {
            AgentResult result = agent.run(test.sessionId(), test.input());
            int toolCalls = (int) result.getSession().getMessages().stream()
                    .flatMap(message -> message.getToolCalls().stream()).count();
            boolean textMatches = test.expectedText() == null
                    || test.expectedText().equals(result.getFinalText());
            boolean toolsMatch = toolCalls == test.expectedToolCalls();
            return new EvaluationResult(test.name(), textMatches && toolsMatch,
                    result.getFinalText(), toolCalls,
                    textMatches && toolsMatch ? null : "Expected text/tools did not match");
        }).toList();
    }
}
