package com.oficina.functions.adapter.aws;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import software.amazon.awssdk.services.sesv2.SesV2Client;
import software.amazon.awssdk.services.sesv2.model.SendEmailRequest;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class SesEnviarCodigoTest {
    @Test void sendsDirectOtpOnlyToTrustedLookupAddressWithFixedSenderAndTemplate() {
        SesV2Client ses = mock(SesV2Client.class);
        new SesEnviarCodigo(ses, "noreply@example.invalid").enviar("registered@example.invalid", "123456");
        ArgumentCaptor<SendEmailRequest> request = ArgumentCaptor.forClass(SendEmailRequest.class);
        verify(ses).sendEmail(request.capture());
        assertThat(request.getValue().fromEmailAddress()).isEqualTo("noreply@example.invalid");
        assertThat(request.getValue().destination().toAddresses()).containsExactly("registered@example.invalid");
        assertThat(request.getValue().content().simple().subject().data()).contains("código");
        assertThat(request.getValue().content().simple().body().text().data()).contains("123456", "5 minutos");
    }
    @Test void rejectsUntrustedAddressOrNonOtpBeforeCallingSes() {
        SesV2Client ses = mock(SesV2Client.class);
        SesEnviarCodigo sender = new SesEnviarCodigo(ses, "noreply@example.invalid");
        assertThatIllegalArgumentException().isThrownBy(() -> sender.enviar("override@example.invalid", "not-code"));
        verifyNoInteractions(ses);
    }
}
