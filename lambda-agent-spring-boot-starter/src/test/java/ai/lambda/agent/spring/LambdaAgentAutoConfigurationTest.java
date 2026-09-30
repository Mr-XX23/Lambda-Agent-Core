package ai.lambda.agent.spring;

import ai.lambda.agent.core.Agent;
import ai.lambda.agent.core.AgentConfig;
import ai.lambda.agent.core.InMemorySessionStore;
import ai.lambda.ai.core.ChatResponse;
import ai.lambda.ai.core.Message;
import ai.lambda.ai.core.ModelClient;
import ai.lambda.ai.core.Role;
import ai.lambda.ai.core.ToolSchema;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class LambdaAgentAutoConfigurationTest {

    /** The application's side: it provides the Agent bean. This one answers with the user's text reversed. */
    @Configuration
    static class ApplicationWithAgent {
        @Bean
        Agent agent() {
            ModelClient model = new ModelClient() {
                public ChatResponse chat(List<Message> messages, List<ToolSchema> tools) {
                    String input = messages.getLast().getContent();
                    return new ChatResponse(new Message(Role.ASSISTANT, new StringBuilder(input).reverse().toString(), null), List.of());
                }

                public ChatResponse streamChat(List<Message> messages, List<ToolSchema> tools, Consumer<String> onDelta) {
                    return chat(messages, tools);
                }
            };
            return new Agent(new AgentConfig("system", model), new InMemorySessionStore());
        }
    }

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(LambdaAgentAutoConfiguration.class));

    private static MockMvc mvc(org.springframework.context.ApplicationContext context) {
        return MockMvcBuilders.standaloneSetup(context.getBean("lambdaAgentController")).build();
    }

    private static MockHttpServletRequestBuilder run(String json) {
        return post("/agent/run").contentType(MediaType.APPLICATION_JSON).content(json);
    }

    @Test
    void doesNothingWithoutAnAgentBean() {
        runner.run(context -> {
            assertFalse(context.containsBean("lambdaAgentController"));
            assertTrue(context.getBeansOfType(LambdaAgentProperties.class).isEmpty());
        });
    }

    @Test
    void exposesTheAgentOverHttp() {
        runner.withUserConfiguration(ApplicationWithAgent.class).run(context ->
                mvc(context).perform(run("{\"sessionId\":\"s1\",\"input\":\"hello\"}"))
                        .andExpect(status().isOk())
                        .andExpect(jsonPath("$.text").value("olleh"))
                        .andExpect(jsonPath("$.iterations").value(1))
                        .andExpect(jsonPath("$.runId").isNotEmpty()));
    }

    @Test
    void requiresTheConfiguredBearerToken() {
        runner.withUserConfiguration(ApplicationWithAgent.class)
                .withPropertyValues("lambda.agent.bearer-token=s3cret")
                .run(context -> {
                    MockMvc mvc = mvc(context);
                    String body = "{\"sessionId\":\"s1\",\"input\":\"hello\"}";
                    mvc.perform(run(body)).andExpect(status().isUnauthorized());
                    mvc.perform(run(body).header("Authorization", "Bearer wrong")).andExpect(status().isUnauthorized());
                    mvc.perform(run(body).header("Authorization", "s3cret")).andExpect(status().isUnauthorized());
                    mvc.perform(run(body).header("Authorization", "Bearer s3cret")).andExpect(status().isOk());
                });
    }

    @Test
    void rejectsIncompleteRequests() {
        runner.withUserConfiguration(ApplicationWithAgent.class).run(context -> {
            mvc(context).perform(run("{\"sessionId\":\"s1\"}")).andExpect(status().isBadRequest());
            mvc(context).perform(run("{\"input\":\"hello\"}")).andExpect(status().isBadRequest());
            mvc(context).perform(run("not json")).andExpect(status().isBadRequest());
        });
    }

    @Test
    void enforcesTheConfiguredSizeLimits() {
        runner.withUserConfiguration(ApplicationWithAgent.class)
                .withPropertyValues("lambda.agent.max-request-bytes=50", "lambda.agent.max-response-bytes=20")
                .run(context -> {
                    MockMvc mvc = mvc(context);
                    mvc.perform(run("{\"sessionId\":\"s1\",\"input\":\"" + "x".repeat(60) + "\"}"))
                            .andExpect(status().isPayloadTooLarge());
                    mvc.perform(run("{\"sessionId\":\"s1\",\"input\":\"" + "x".repeat(30) + "\"}"))
                            .andExpect(status().isInternalServerError()); // the 30-character answer exceeds 20 bytes
                    mvc.perform(run("{\"sessionId\":\"s1\",\"input\":\"short\"}")).andExpect(status().isOk());
                });
    }

    @Test
    void bindsPropertiesWithDefaults() {
        runner.withUserConfiguration(ApplicationWithAgent.class).run(context -> {
            assertEquals(1, context.getBeansOfType(LambdaAgentController.class).size(), "exactly one controller");
            LambdaAgentProperties properties = context.getBean(LambdaAgentProperties.class);
            assertNull(properties.getBearerToken());
            assertEquals(65536, properties.getMaxRequestBytes());
            assertEquals(131072, properties.getMaxResponseBytes());
        });
    }

    @Test
    void registersNoSecondControllerWhenOneExists() {
        runner.withUserConfiguration(ApplicationWithAgent.class)
                .withBean("myController", LambdaAgentController.class,
                        () -> new LambdaAgentController(null, new LambdaAgentProperties()))
                .run(context -> {
                    assertFalse(context.containsBean("lambdaAgentController"), "the default backs off");
                    assertEquals(1, context.getBeansOfType(LambdaAgentController.class).size());
                });
    }
}
