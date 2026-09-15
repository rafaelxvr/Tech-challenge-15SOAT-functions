package com.oficina.functions.adapter.aws;

import com.oficina.functions.auth.EnviarCodigo;
import software.amazon.awssdk.services.sesv2.SesV2Client;
import software.amazon.awssdk.services.sesv2.model.*;
import java.util.Objects;

/** Sends one short OTP to the address supplied by the trusted customer snapshot. */
public final class SesEnviarCodigo implements EnviarCodigo {
    private static final String SUBJECT = "Seu código de acesso Oficina";
    private final SesV2Client ses;
    private final String sender;

    public SesEnviarCodigo(SesV2Client ses, String sender) {
        this.ses = Objects.requireNonNull(ses);
        if (sender == null || !sender.matches("[^\\s@]+@[^\\s@]+\\.[^\\s@]+")) throw new IllegalArgumentException("Verified sender required");
        this.sender = sender;
    }

    @Override public void enviar(String email, String codigo) {
        if (email == null || !email.matches("[^\\s@]+@[^\\s@]+\\.[^\\s@]+") || codigo == null || !codigo.matches("\\d{6}"))
            throw new IllegalArgumentException("Invalid trusted delivery address or code");
        ses.sendEmail(SendEmailRequest.builder().fromEmailAddress(sender)
                .destination(Destination.builder().toAddresses(email).build())
                .content(EmailContent.builder().simple(Message.builder()
                        .subject(Content.builder().data(SUBJECT).charset("UTF-8").build())
                        .body(Body.builder().text(Content.builder().data("Seu código é " + codigo + ". Ele expira em 5 minutos.")
                                .charset("UTF-8").build()).build()).build()).build()).build());
    }
}
