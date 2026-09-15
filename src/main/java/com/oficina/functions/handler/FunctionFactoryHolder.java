package com.oficina.functions.handler;

import com.oficina.functions.bootstrap.FunctionFactory;

/** Lazy holder makes one immutable dependency graph per Lambda execution environment. */
final class FunctionFactoryHolder {
    private static final FunctionFactory FACTORY = FunctionFactory.fromEnvironment();
    private FunctionFactoryHolder() { }
    static FunctionFactory factory() { return FACTORY; }
}
