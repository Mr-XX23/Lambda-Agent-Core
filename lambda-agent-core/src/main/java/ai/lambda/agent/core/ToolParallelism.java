package ai.lambda.agent.core;

/** How the tools a model asks for in one reply are run (see {@link AgentConfig#withToolParallelism}). */
public enum ToolParallelism {

    /**
     * Tools that declare {@link AgentTool#isParallelSafe()} run at the same time; every other tool
     * waits for the ones before it and runs alone. The default.
     */
    AUTO,

    /** Every tool runs at the same time as the others, whatever it declares. */
    ALWAYS,

    /** Tools run one after another. */
    NEVER
}
