package com.lantanagroup.link.query.auth;

import com.lantanagroup.link.config.YamlPropertySourceFactory;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.PropertySource;

@Getter
@Setter
@Configuration
@ConfigurationProperties(prefix = "query.auth.epic")
@PropertySource(value = "classpath:application.yml", factory = YamlPropertySourceFactory.class)
public class EpicAuthConfig implements ICustomAuthConfig {
  private String key;
  private String tokenUrl;
  private String clientId;
  private String audience;
}
