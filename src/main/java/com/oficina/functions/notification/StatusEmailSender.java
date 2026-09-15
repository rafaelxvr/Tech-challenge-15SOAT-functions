package com.oficina.functions.notification;

public interface StatusEmailSender {
    /** @return provider acceptance ID, never an inbox-delivery assertion. */
    String enviar(Destinatario destinatario, StatusOrdemServicoRegistrado evento);
}
