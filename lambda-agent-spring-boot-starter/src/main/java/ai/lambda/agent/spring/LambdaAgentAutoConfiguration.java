package ai.lambda.agent.spring;

import ai.lambda.agent.core.Agent;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;

/**
 * Exposes the application's {@link Agent} bean at {@code POST /agent/run}. Settings are under
 * {@code lambda.agent} (see {@link LambdaAgentProperties}). To serve the agent with your own
 * controller instead, exclude this auto-configuration.
 */
@AutoConfiguration
@EnableConfigurationProperties(LambdaAgentProperties.class)
@ConditionalOnBean(Agent.class)
public class LambdaAgentAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    LambdaAgentController lambdaAgentController(Agent agent, LambdaAgentProperties properties) {
        return new LambdaAgentController(agent, properties);
    }
}
