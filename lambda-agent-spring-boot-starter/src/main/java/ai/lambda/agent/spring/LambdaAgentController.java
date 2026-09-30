package ai.lambda.agent.spring;

import ai.lambda.agent.core.Agent;
import ai.lambda.agent.core.AgentResult;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/** The {@code POST /agent/run} endpoint. Registered by {@link LambdaAgentAutoConfiguration}. */
@RestController
@RequestMapping("/agent")
final class LambdaAgentController {
    private final Agent agent;
    private final LambdaAgentProperties properties;

    LambdaAgentController(Agent agent, LambdaAgentProperties properties) {
        this.agent = agent;
        this.properties = properties;
    }

    @PostMapping("/run")
    Response run(@RequestHeader(value = "Authorization", required = false) String authorization,
                 @RequestBody Request request) {
        if (!authorized(authorization)) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "authentication required");
        }
        if (request.sessionId() == null || request.input() == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "sessionId and input are required");
        }
        if (bytes(request.sessionId()) + bytes(request.input()) > properties.getMaxRequestBytes()) {
            throw new ResponseStatusException(HttpStatus.PAYLOAD_TOO_LARGE, "request too large");
        }
        AgentResult result = agent.run(request.sessionId(), request.input());
        if (bytes(result.getFinalText()) > properties.getMaxResponseBytes()) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "response exceeds configured limit");
        }
        return new Response(result.getRunId(), result.getFinalText(), result.getIterations());
    }

    private boolean authorized(String authorization) {
        String token = properties.getBearerToken();
        if (token == null || token.isBlank()) return true;
        // Compared in constant time, so response timing does not reveal the token.
        return authorization != null && MessageDigest.isEqual(
                authorization.getBytes(StandardCharsets.UTF_8),
                ("Bearer " + token).getBytes(StandardCharsets.UTF_8));
    }

    private static int bytes(String text) {
        return text == null ? 0 : text.getBytes(StandardCharsets.UTF_8).length;
    }

    record Request(String sessionId, String input) {
    }

    record Response(String runId, String text, int iterations) {
    }
}
