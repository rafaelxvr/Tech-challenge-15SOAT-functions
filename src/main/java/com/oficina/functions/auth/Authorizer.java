package com.oficina.functions.auth;

import java.util.Objects;

public final class Authorizer {
    private final CustomerTokenVerifier customer;
    private final StaffTokenVerifier staff;
    private final RoutePolicy policy;

    public Authorizer(CustomerTokenVerifier customer, StaffTokenVerifier staff, RoutePolicy policy) {
        this.customer = Objects.requireNonNull(customer);
        this.staff = Objects.requireNonNull(staff);
        this.policy = Objects.requireNonNull(policy);
    }

    /** Invalid/absent credentials throw 401; valid credentials without a grant return false (403).
     * Public routes are configured without this authorizer. No authorization result is cached here.
     */
    public boolean autorizar(String bearer, String routeKey) {
        if (bearer == null || !bearer.matches("(?i)Bearer [A-Za-z0-9_-]+\\.[A-Za-z0-9_-]+\\.[A-Za-z0-9_-]+"))
            throw TokenVerification.invalid();
        String token = bearer.substring(7);
        VerifiedPrincipal principal;
        try {
            principal = customer.verificar(token);
        } catch (AutenticacaoException invalidCustomer) {
            principal = staff.verificar(token);
        }
        return policy.permite(principal, routeKey);
    }
}
