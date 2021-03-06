/*
 * Copyright (c) MuleSoft, Inc.  All rights reserved.  http://www.mulesoft.com
 * The software in this package is published under the terms of the CPAL v1.0
 * license, a copy of which has been included with this distribution in the
 * LICENSE.txt file.
 */
package org.mule.services.http.impl.service.server;

import org.mule.runtime.api.connection.ConnectionException;
import org.mule.service.http.api.server.HttpServer;
import org.mule.service.http.api.server.HttpServerConfiguration;
import org.mule.service.http.api.server.HttpServerFactory;
import org.mule.service.http.api.server.ServerNotFoundException;

/**
 * Adapts a {@link ContextHttpServerFactory} to a {@link HttpServerFactory}.
 *
 * @since 4.0
 */
public class ContextHttpServerFactoryAdapter implements HttpServerFactory {

  private final String context;
  private final ContextHttpServerFactory delegate;

  public ContextHttpServerFactoryAdapter(String context, ContextHttpServerFactory delegate) {
    this.context = context;
    this.delegate = delegate;
  }

  @Override
  public HttpServer create(HttpServerConfiguration configuration) throws ConnectionException {
    return delegate.create(configuration, context);
  }

  @Override
  public HttpServer lookup(String name) throws ServerNotFoundException {
    return delegate.lookup(new ServerIdentifier(context, name));
  }
}
