package com.oficina.functions.handler;

import com.oficina.functions.auth.CriarDesafio;
import com.oficina.functions.bootstrap.FunctionFactory;

/** Lazy holder makes one immutable dependency graph per Lambda execution environment. */
final class FunctionFactoryHolder {
    private static final CriarDesafio CHALLENGE = FunctionFactory.challengeFromEnvironment();
    private FunctionFactoryHolder() { }
    static CriarDesafio challenge() { return CHALLENGE; }
}
