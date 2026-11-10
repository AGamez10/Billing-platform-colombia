package co.billingplatform.platform.mail;

import co.billingplatform.shared.application.Mailer;
import jakarta.mail.MessagingException;
import jakarta.mail.internet.MimeMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Correo transaccional. {@code billing.mail.mode}:
 * <ul>
 *   <li>{@code smtp}: producción y docker compose (Amazon SES, Mailpit en desarrollo).</li>
 *   <li>{@code memory}: pruebas automáticas; se leen los correos desde {@link InMemoryMailbox}.</li>
 *   <li>{@code log} (por omisión): no envía; registra solo destinatario y asunto, nunca el enlace.</li>
 * </ul>
 */
@Configuration
class MailConfiguration {

    private static final Logger log = LoggerFactory.getLogger(MailConfiguration.class);

    @Bean
    @ConditionalOnProperty(name = "billing.mail.mode", havingValue = "smtp")
    Mailer smtpMailer(JavaMailSender sender, @Value("${billing.mail.from}") String from) {
        ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
        return afterCommit(email -> executor.submit(() -> {
            try {
                MimeMessage message = sender.createMimeMessage();
                MimeMessageHelper helper = new MimeMessageHelper(message, true, StandardCharsets.UTF_8.name());
                helper.setFrom(from);
                helper.setTo(email.to());
                helper.setSubject(email.subject());
                if (email.html() != null) {
                    helper.setText(email.text(), email.html());
                } else {
                    helper.setText(email.text());
                }
                sender.send(message);
            } catch (MessagingException | RuntimeException e) {
                log.warn("Email '{}' could not be sent: {}", email.subject(), e.getClass().getSimpleName());
            }
        }));
    }

    @Bean
    @ConditionalOnProperty(name = "billing.mail.mode", havingValue = "memory")
    InMemoryMailbox inMemoryMailbox() {
        return new InMemoryMailbox();
    }

    @Bean
    @ConditionalOnProperty(name = "billing.mail.mode", havingValue = "memory")
    Mailer memoryMailer(InMemoryMailbox mailbox) {
        return afterCommit(mailbox::deliver);
    }

    @Bean
    @ConditionalOnProperty(name = "billing.mail.mode", havingValue = "log", matchIfMissing = true)
    Mailer loggingMailer() {
        log.warn("billing.mail.mode=log: emails are NOT sent (set billing.mail.mode=smtp in production)");
        return afterCommit(email -> log.info("Email not sent (mode=log): {}", email));
    }

    /** Envía solo si la transacción en curso se confirma; sin transacción, de inmediato. */
    private static Mailer afterCommit(Mailer delegate) {
        return email -> {
            if (TransactionSynchronizationManager.isSynchronizationActive()) {
                TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                    @Override
                    public void afterCommit() {
                        delegate.send(email);
                    }
                });
            } else {
                delegate.send(email);
            }
        };
    }
}
