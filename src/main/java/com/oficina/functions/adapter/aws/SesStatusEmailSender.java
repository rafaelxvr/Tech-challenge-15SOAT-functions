package com.oficina.functions.adapter.aws;

import com.oficina.functions.notification.Destinatario;
import com.oficina.functions.notification.StatusEmailSender;
import com.oficina.functions.notification.StatusOrdemServicoRegistrado;
import software.amazon.awssdk.services.sesv2.SesV2Client;
import software.amazon.awssdk.services.sesv2.model.*;
import java.time.format.DateTimeFormatter;
import java.util.Objects;

/** Fixed historical-status template; it contains no link, token, OTP or mutable contact data. */
public final class SesStatusEmailSender implements StatusEmailSender {
    private final SesV2Client ses; private final String sender;
    public SesStatusEmailSender(SesV2Client ses, String sender) { this.ses = Objects.requireNonNull(ses); if (sender == null || sender.isBlank()) throw new IllegalArgumentException("Sender required"); this.sender = sender; }
    @Override public String enviar(Destinatario recipient, StatusOrdemServicoRegistrado event) {
        String body = "A ordem de serviço " + event.numero() + " registrou o status " + event.statusNovo()
                + " em " + DateTimeFormatter.ISO_INSTANT.format(event.ocorridoEm()) + ". Acompanhe pelo canal autenticado da oficina.";
        SendEmailResponse response = ses.sendEmail(SendEmailRequest.builder().fromEmailAddress(sender)
                .destination(Destination.builder().toAddresses(recipient.email()).build()).content(EmailContent.builder().simple(Message.builder()
                        .subject(Content.builder().data("Atualização da ordem de serviço").charset("UTF-8").build())
                        .body(Body.builder().text(Content.builder().data(body).charset("UTF-8").build()).build()).build()).build()).build());
        return response.messageId();
    }
}
