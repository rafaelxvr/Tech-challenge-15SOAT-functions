package com.oficina.functions.handler;

import com.oficina.functions.auth.VerificarDesafio;
import com.oficina.functions.bootstrap.FunctionFactory;

/** Only this Lambda bootstrap resolves the customer signing-key secret. */
final class VerificationFactoryHolder {
    private static final VerificarDesafio VERIFICATION = FunctionFactory.verificationFromEnvironment();
    private VerificationFactoryHolder() { }
    static VerificarDesafio verification() { return VERIFICATION; }
}
