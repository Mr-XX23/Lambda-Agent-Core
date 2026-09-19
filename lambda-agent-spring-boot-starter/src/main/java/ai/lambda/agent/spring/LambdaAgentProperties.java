package ai.lambda.agent.spring;
import org.springframework.boot.context.properties.ConfigurationProperties;
@ConfigurationProperties(prefix="lambda.agent")
public class LambdaAgentProperties {
 private String bearerToken; private int maxRequestBytes=65536; private int maxResponseBytes=131072;
 public String getBearerToken(){return bearerToken;} public void setBearerToken(String v){bearerToken=v;}
 public int getMaxRequestBytes(){return maxRequestBytes;} public void setMaxRequestBytes(int v){maxRequestBytes=v;}
 public int getMaxResponseBytes(){return maxResponseBytes;} public void setMaxResponseBytes(int v){maxResponseBytes=v;}
}
