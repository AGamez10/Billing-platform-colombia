package co.billingplatform.invoicing.adapter.web;

import co.billingplatform.electronicinvoicing.domain.ElectronicDocument.NumberingAuthorization;
import co.billingplatform.electronicinvoicing.domain.ProviderEnvironment;
import co.billingplatform.invoicing.domain.DocumentNumber;
import co.billingplatform.invoicing.domain.Invoice;
import co.billingplatform.shared.application.Municipalities;
import co.billingplatform.shared.domain.FiscalAddress;
import co.billingplatform.shared.domain.FiscalProfile;
import co.billingplatform.shared.domain.Party;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/**
 * Datos de la representación gráfica de un documento validado: lo que se ve, imprime o
 * descarga. Sin historial, errores ni identificadores internos (también se entrega por enlace
 * público al comprador).
 */
public record DocumentSheet(
        String kind,
        String number,
        LocalDate issueDate,
        Instant validatedAt,
        String environment,
        boolean legallyValid,
        boolean simulated,
        PartyView issuer,
        PartyView customer,
        boolean finalConsumer,
        Authorization authorization,
        List<InvoiceView.Line> lines,
        List<InvoiceView.TaxSubtotal> taxSubtotals,
        InvoiceView.Totals totals,
        String documentKey,
        String qrPayload) {

    public record PartyView(String identificationType, String identificationNumber, Integer checkDigit, String legalName,
                            String email, String address, String cityCode, String cityName, String departmentName,
                            String personTypeCode, String vatRegime, List<String> responsibilities) {

        static PartyView of(Party p, Municipalities municipalities) {
            FiscalAddress a = p.address();
            var m = a == null ? java.util.Optional.<Municipalities.Municipality>empty() : municipalities.find(a.cityCode());
            FiscalProfile f = p.fiscal();
            return new PartyView(p.identification().type().name(), p.identification().number(), p.identification().checkDigit(),
                    p.legalName(), p.email(), a == null ? null : a.line(), a == null ? null : a.cityCode(),
                    m.map(Municipalities.Municipality::name).orElse(null),
                    m.map(Municipalities.Municipality::departmentName).orElse(null),
                    f == null ? null : f.personType().code(), f == null ? null : f.vatRegime().name(),
                    f == null ? List.of() : f.responsibilityCodes());
        }
    }

    public record Authorization(String resolutionNumber, String prefix, long rangeFrom, long rangeTo, LocalDate validFrom,
                                LocalDate validTo) {}

    static DocumentSheet of(Invoice invoice, Party issuer, Municipalities municipalities) {
        InvoiceView view = InvoiceView.of(invoice);
        var evidence = invoice.evidence().orElseThrow();
        NumberingAuthorization auth = invoice.authorization().orElseThrow();
        return new DocumentSheet(
                view.kind(), view.number(), view.issueDate(), evidence.validatedAt(), evidence.environment().name(),
                view.legallyValid(), evidence.environment() == ProviderEnvironment.SIMULATION,
                PartyView.of(issuer, municipalities),
                PartyView.of(invoice.customer(), municipalities),
                invoice.customer().isFinalConsumer(),
                new Authorization(auth.resolutionNumber(), invoice.number().map(DocumentNumber::prefix).orElse(null),
                        auth.rangeFrom(), auth.rangeTo(), auth.validFrom(), auth.validTo()),
                view.lines(), view.taxSubtotals(), view.totals(), evidence.documentKey(), evidence.qrPayload());
    }
}
