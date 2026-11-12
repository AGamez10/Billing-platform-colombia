package co.billingplatform.invoicing.adapter.persistence;

import co.billingplatform.invoicing.application.DocumentSharingService;
import co.billingplatform.shared.domain.TenantId;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/** Enlaces de documento (V17). La búsqueda por token pasa por una función acotada sin tenant. */
@Repository
class JdbcShareLinks implements DocumentSharingService.ShareLinks {

    private final JdbcClient jdbc;

    JdbcShareLinks(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void insert(DocumentSharingService.ShareLink l) {
        jdbc.sql("""
                        INSERT INTO invoice_share_links (tenant_id, id, invoice_id, token_hash, channel, sent_to, created_by,
                                                         created_at, expires_at)
                        VALUES (:t, :id, :invoice, :hash, :channel, :to, :by, :at, :expires)
                        """)
                .param("t", l.tenantId().value())
                .param("id", l.id())
                .param("invoice", l.invoiceId())
                .param("hash", l.tokenHash())
                .param("channel", l.channel().name())
                .param("to", l.sentTo())
                .param("by", l.createdBy())
                .param("at", Timestamp.from(l.createdAt()))
                .param("expires", Timestamp.from(l.expiresAt()))
                .update();
    }

    @Override
    public int countSince(TenantId tenantId, UUID invoiceId, Instant since) {
        return jdbc.sql("SELECT count(*) FROM invoice_share_links WHERE tenant_id = :t AND invoice_id = :i AND created_at >= :since")
                .param("t", tenantId.value())
                .param("i", invoiceId)
                .param("since", Timestamp.from(since))
                .query(Integer.class)
                .single();
    }

    @Override
    public Optional<DocumentSharingService.Target> find(byte[] tokenHash) {
        return jdbc.sql("SELECT tenant_id, invoice_id FROM invoice_share_find(:hash)")
                .param("hash", tokenHash)
                .query((rs, n) -> new DocumentSharingService.Target(new TenantId(rs.getObject("tenant_id", UUID.class)),
                        rs.getObject("invoice_id", UUID.class)))
                .optional();
    }
}
