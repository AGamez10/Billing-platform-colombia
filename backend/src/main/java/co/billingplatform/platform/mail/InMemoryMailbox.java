package co.billingplatform.platform.mail;

import co.billingplatform.shared.application.Mailer;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;

/** Buzón en memoria para pruebas automáticas ({@code billing.mail.mode=memory}). */
public final class InMemoryMailbox {

    private final List<Mailer.Email> delivered = new CopyOnWriteArrayList<>();

    void deliver(Mailer.Email email) {
        delivered.add(email);
    }

    /** Último correo enviado a esa dirección. */
    public Optional<Mailer.Email> lastTo(String address) {
        for (int i = delivered.size() - 1; i >= 0; i--) {
            if (delivered.get(i).to().equalsIgnoreCase(address)) {
                return Optional.of(delivered.get(i));
            }
        }
        return Optional.empty();
    }

    public long countTo(String address) {
        return delivered.stream().filter(e -> e.to().equalsIgnoreCase(address)).count();
    }
}
