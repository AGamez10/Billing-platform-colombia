package co.billingplatform.platform;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.net.http.HttpResponse;

import static org.junit.jupiter.api.Assertions.*;

class OrganizationProfileApiTest extends ApiTest {

    private static final String PROFILE = """
            {"legalName":"Hogar de Paso Prueba S.A.S.","tradeName":"Hogar","email":"Facturas@Hogar.example",
             "phone":"601 555 0101","addressLine":"Calle 10 # 5-20","cityCode":"76001",
             "fiscal":{"personType":"LEGAL","vatRegime":"RESPONSIBLE","responsibilities":["O-23","O-13"]}}
            """;

    @Test
    @DisplayName("perfil del emisor: incompleto al registrarse; se completa con municipio DANE válido")
    void issuerProfile() throws Exception {
        String token = registerOrganization();
        HttpResponse<String> initial = send("GET", "/api/v1/organization", token, null);
        assertEquals(200, initial.statusCode(), initial.body());
        assertTrue(initial.body().contains("\"readyToIssue\":false"), initial.body());

        HttpResponse<String> unknownCity = send("PUT", "/api/v1/organization", token, PROFILE.replace("76001", "99999"));
        assertEquals(422, unknownCity.statusCode());
        assertEquals("UNKNOWN_MUNICIPALITY", field(unknownCity.body(), "code"));

        HttpResponse<String> mixed = send("PUT", "/api/v1/organization", token, PROFILE.replace("\"O-13\"", "\"R-99-PN\""));
        assertEquals("INVALID_FISCAL_PROFILE", field(mixed.body(), "code"));

        HttpResponse<String> saved = send("PUT", "/api/v1/organization", token, PROFILE);
        assertEquals(200, saved.statusCode(), saved.body());
        assertTrue(saved.body().contains("\"readyToIssue\":true"));
        assertTrue(saved.body().contains("\"cityName\":\"Santiago de Cali\""), saved.body());
        assertTrue(saved.body().contains("\"responsibilities\":[\"O-13\",\"O-23\"]"), saved.body());
        assertEquals("48", field(saved.body(), "vatRegimeCode"));
        assertEquals("facturas@hogar.example", field(send("GET", "/api/v1/organization", token, null).body(), "email"));
    }

    @Test
    @DisplayName("cliente con perfil tributario y municipio; sin perfil sigue siendo válido; municipio inexistente no")
    void customerFiscalProfile() throws Exception {
        String token = registerOrganization();
        HttpResponse<String> created = send("POST", "/api/v1/customers", token, "{" + randomNitJson()
                + ",\"legalName\":\"Cliente S.A.S.\",\"address\":\"Cra 7 # 1-1\",\"cityCode\":\"05001\","
                + "\"fiscal\":{\"personType\":\"LEGAL\",\"vatRegime\":\"NOT_RESPONSIBLE\",\"responsibilities\":[\"R-99-PN\"]}}");
        assertEquals(201, created.statusCode(), created.body());
        assertTrue(created.body().contains("\"responsibilities\":[\"R-99-PN\"]"), created.body());
        assertEquals("1", field(created.body(), "personTypeCode"));

        assertEquals(201, send("POST", "/api/v1/customers", token, "{" + randomNitJson()
                + ",\"legalName\":\"Sin perfil\"}").statusCode());

        HttpResponse<String> badCity = send("POST", "/api/v1/customers", token, "{" + randomNitJson()
                + ",\"legalName\":\"X\",\"cityCode\":\"00000\"}");
        assertEquals("UNKNOWN_MUNICIPALITY", field(badCity.body(), "code"));
    }

    @Test
    @DisplayName("municipios: catálogo público con caché")
    void municipalities() throws Exception {
        HttpResponse<String> res = send("GET", "/api/v1/public/municipalities", null, null);
        assertEquals(200, res.statusCode());
        assertTrue(res.body().contains("\"code\":\"11001\""));
        assertTrue(res.headers().firstValue("Cache-Control").orElse("").contains("max-age=86400"));
    }
}
