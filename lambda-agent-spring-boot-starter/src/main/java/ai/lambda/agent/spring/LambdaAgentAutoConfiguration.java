package ai.lambda.agent.spring;
import ai.lambda.agent.core.*;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;
@AutoConfiguration @EnableConfigurationProperties(LambdaAgentProperties.class) @ConditionalOnBean(Agent.class)
public class LambdaAgentAutoConfiguration {
 @Bean @ConditionalOnMissingBean LambdaAgentController lambdaAgentController(Agent a,LambdaAgentProperties p){return new LambdaAgentController(a,p);}
 @RestController @RequestMapping("/agent")
 static final class LambdaAgentController {
  private final Agent agent; private final LambdaAgentProperties properties;
  LambdaAgentController(Agent a,LambdaAgentProperties p){agent=a;properties=p;}
  @PostMapping("/run") Response run(@RequestHeader(value="Authorization",required=false) String auth,@RequestBody Request request){
   if(properties.getBearerToken()!=null&&!properties.getBearerToken().isBlank()&&!("Bearer "+properties.getBearerToken()).equals(auth))throw new ResponseStatusException(HttpStatus.UNAUTHORIZED,"authentication required");
   if(request.sessionId()==null||request.input()==null)throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"sessionId and input are required");
   AgentResult r=agent.run(request.sessionId(),request.input()); return new Response(r.getRunId(),r.getFinalText(),r.getIterations());
  }
  record Request(String sessionId,String input){} record Response(String runId,String text,int iterations){}
 }
}
