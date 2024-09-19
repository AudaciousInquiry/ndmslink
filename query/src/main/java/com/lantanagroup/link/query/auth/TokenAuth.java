package com.lantanagroup.link.query.auth;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;

public class TokenAuth implements ICustomAuth {
  private static final Logger logger = LoggerFactory.getLogger(TokenAuth.class);

  @Autowired
  private TokenAuthConfig config;

  @Override
  public void setConfig(ICustomAuthConfig authConfig) throws Exception {
    config = (TokenAuthConfig) authConfig;
  }

  @Override
  public String getAuthHeader() {
    return this.config.getToken();
  }

  @Override
  public String getApiKeyHeader() throws Exception {
    return null;
  }
}
