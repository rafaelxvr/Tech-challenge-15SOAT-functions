package com.oficina.functions.handler;

import com.oficina.functions.auth.Authorizer;
import com.oficina.functions.bootstrap.FunctionFactory;

/** This Lambda's cold start deliberately has only public verification material and route policy. */
final class AuthorizerFactoryHolder {
    private static final Authorizer AUTHORIZER = FunctionFactory.authorizerFromEnvironment();
    private AuthorizerFactoryHolder() { }
    static Authorizer authorizer() { return AUTHORIZER; }
}
