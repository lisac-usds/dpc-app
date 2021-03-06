package gov.cms.dpc.api.auth.jwt;

import com.github.nitram509.jmacaroons.Macaroon;
import com.github.nitram509.jmacaroons.MacaroonVersion;
import com.github.nitram509.jmacaroons.MacaroonsBuilder;
import gov.cms.dpc.api.entities.PublicKeyEntity;
import gov.cms.dpc.api.jdbi.PublicKeyDAO;
import gov.cms.dpc.testing.APIAuthHelpers;
import gov.cms.dpc.testing.BufferedLoggerHandler;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwsHeader;
import org.bouncycastle.asn1.x509.SubjectPublicKeyInfo;
import org.eclipse.jetty.http.HttpStatus;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.Mockito;

import javax.ws.rs.WebApplicationException;
import java.io.IOException;
import java.security.Key;
import java.security.KeyPair;
import java.security.NoSuchAlgorithmException;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;

@ExtendWith(BufferedLoggerHandler.class)
class JwtKeyResolverTests {

    private static JwtKeyResolver resolver;
    private static KeyPair keyPair;

    private final static UUID badKeyID = UUID.randomUUID();
    private final static UUID correctKeyID = UUID.randomUUID();
    private final static UUID notRealKeyID = UUID.randomUUID();
    private final static UUID organization1 = UUID.randomUUID();
    private final static UUID organization2 = UUID.randomUUID();

    private static final String org1Macaroon = makeMacaroon(organization1);
    private static final String org2Macaroon = makeMacaroon(organization2);



    @BeforeAll
    static void setup() throws IOException, NoSuchAlgorithmException {
        keyPair = APIAuthHelpers.generateKeyPair();
        PublicKeyDAO dao = mock(PublicKeyDAO.class);
        // Bad entity with malformed key
        final PublicKeyEntity badEntity = mock(PublicKeyEntity.class);
        final SubjectPublicKeyInfo badInfo = mock(SubjectPublicKeyInfo.class);
        Mockito.when(badInfo.getEncoded()).thenReturn("This is not a public key".getBytes());
        Mockito.when(badEntity.getPublicKey()).thenReturn(badInfo);

        // Good entity with real key
        final PublicKeyEntity goodEntity = mock(PublicKeyEntity.class);
        final SubjectPublicKeyInfo goodInfo = mock(SubjectPublicKeyInfo.class);
        Mockito.when(goodInfo.getEncoded()).thenReturn(keyPair.getPublic().getEncoded());
        Mockito.when(goodEntity.getPublicKey()).thenReturn(goodInfo);

        Mockito.when(dao.fetchPublicKey(organization1, badKeyID)).thenReturn(Optional.of(badEntity));
        Mockito.when(dao.fetchPublicKey(organization1, correctKeyID)).thenReturn(Optional.of(goodEntity));
        Mockito.when(dao.fetchPublicKey(organization1, notRealKeyID)).thenReturn(Optional.empty());
        Mockito.when(dao.fetchPublicKey(eq(organization2), Mockito.any())).thenReturn(Optional.empty());
        resolver = new JwtKeyResolver(dao);
    }

    @Test
    void testSigningKeyResolver() {
        final JwsHeader headerMock = mock(JwsHeader.class);
        final Claims mockClaims = mock(Claims.class);
        Mockito.when(mockClaims.getIssuer()).thenReturn(org1Macaroon);
        Mockito.when(headerMock.getKeyId()).thenReturn(correctKeyID.toString());
        final Key key = resolver.resolveSigningKey(headerMock, mockClaims);

        assertEquals(keyPair.getPublic(), key, "Keys should match");
    }

    @Test
    void testMissingKIDField() {
        final JwsHeader headerMock = mock(JwsHeader.class);
        final Claims mockClaims = mock(Claims.class);
        Mockito.when(mockClaims.getIssuer()).thenReturn(org1Macaroon);
        Mockito.when(headerMock.getKeyId()).thenReturn(null);

        final WebApplicationException exception = assertThrows(WebApplicationException.class, () -> resolver.resolveSigningKey(headerMock, mockClaims));

        assertAll(() -> assertEquals(HttpStatus.UNAUTHORIZED_401, exception.getResponse().getStatus(), "Should be unauthorized"),
                () -> assertEquals("JWT must have KID field", exception.getMessage(), "Should have KID message"));


    }

    @Test
    void testMissingSigningKey() {
        final JwsHeader headerMock = mock(JwsHeader.class);
        final Claims mockClaims = mock(Claims.class);
        Mockito.when(mockClaims.getIssuer()).thenReturn(org1Macaroon);
        Mockito.when(headerMock.getKeyId()).thenReturn(notRealKeyID.toString());

        final WebApplicationException exception = assertThrows(WebApplicationException.class, () -> resolver.resolveSigningKey(headerMock, mockClaims));

        assertAll(() -> assertEquals(HttpStatus.UNAUTHORIZED_401, exception.getResponse().getStatus(), "Should be unauthorized"),
                () -> assertTrue(exception.getMessage().contains("Cannot find public key with id:"), "Should have KID message"));
    }

    @Test
    void testFailingKeyParsing() {
        final JwsHeader headerMock = mock(JwsHeader.class);
        final Claims mockClaims = mock(Claims.class);
        Mockito.when(mockClaims.getIssuer()).thenReturn(org1Macaroon);
        Mockito.when(headerMock.getKeyId()).thenReturn(badKeyID.toString());

        final WebApplicationException exception = assertThrows(WebApplicationException.class, () -> resolver.resolveSigningKey(headerMock, mockClaims));

        assertAll(() -> assertEquals(HttpStatus.INTERNAL_SERVER_ERROR_500, exception.getResponse().getStatus(), "Should be unauthorized"),
                () -> assertEquals("Internal server error", exception.getMessage(), "Should have KID message"));
    }

    @Test
    void testNonUUIDKeyID() {
        final JwsHeader headerMock = mock(JwsHeader.class);
        final Claims mockClaims = mock(Claims.class);
        Mockito.when(mockClaims.getIssuer()).thenReturn(org1Macaroon);
        Mockito.when(headerMock.getKeyId()).thenReturn("This is not a real key id");

        final WebApplicationException exception = assertThrows(WebApplicationException.class, () -> resolver.resolveSigningKey(headerMock, mockClaims));

        assertAll(() -> assertEquals(HttpStatus.UNAUTHORIZED_401, exception.getResponse().getStatus(), "Should be unauthorized"),
                () -> assertEquals("Invalid Public Key ID", exception.getMessage(), "Should have non-UUID message"));
    }

    @Test
    void testNoMacaroon() {
        final JwsHeader headerMock = mock(JwsHeader.class);
        final Claims mockClaims = mock(Claims.class);
        Mockito.when(mockClaims.getIssuer()).thenReturn(null);
        Mockito.when(headerMock.getKeyId()).thenReturn("This is not a real key id");

        final WebApplicationException exception = assertThrows(WebApplicationException.class, () -> resolver.resolveSigningKey(headerMock, mockClaims));

        assertAll(() -> assertEquals(HttpStatus.UNAUTHORIZED_401, exception.getResponse().getStatus(), "Should be unauthorized"),
                () -> assertEquals("JWT must have client_id", exception.getMessage(), "Should have non-UUID message"));
    }

    @Test
    void testMacaroonNoCaveat() {
        final JwsHeader headerMock = mock(JwsHeader.class);
        final Claims mockClaims = mock(Claims.class);
        Mockito.when(mockClaims.getIssuer()).thenReturn(makeMacaroon(null));
        Mockito.when(headerMock.getKeyId()).thenReturn("This is not a real key id");

        final WebApplicationException exception = assertThrows(WebApplicationException.class, () -> resolver.resolveSigningKey(headerMock, mockClaims));

        assertAll(() -> assertEquals(HttpStatus.UNAUTHORIZED_401, exception.getResponse().getStatus(), "Should be unauthorized"),
                () -> assertEquals("JWT client token must have organization_id", exception.getMessage(), "Should have non-UUID message"));
    }

    @Test
    void testMacaroonWrongOrg() {
        final JwsHeader headerMock = mock(JwsHeader.class);
        final Claims mockClaims = mock(Claims.class);
        Mockito.when(mockClaims.getIssuer()).thenReturn(org2Macaroon);
        Mockito.when(headerMock.getKeyId()).thenReturn("This is not a real key id");

        final WebApplicationException exception = assertThrows(WebApplicationException.class, () -> resolver.resolveSigningKey(headerMock, mockClaims));

        assertAll(() -> assertEquals(HttpStatus.UNAUTHORIZED_401, exception.getResponse().getStatus(), "Should be unauthorized"),
                () -> assertEquals("Invalid Public Key ID", exception.getMessage(), "Should have non-UUID message"));
    }

    private static String makeMacaroon(UUID orgID) {
        // Manually create a fake Macaroon with just the org id
        final Macaroon m = MacaroonsBuilder.create("test.local", "fake key", "make id");
        if (orgID != null) {
            return MacaroonsBuilder.modify(m)
                    .add_first_party_caveat(String.format("organization_id = %s", orgID.toString()))
                    .getMacaroon()
                    .serialize(MacaroonVersion.SerializationVersion.V1_BINARY);
        }

        return m.serialize(MacaroonVersion.SerializationVersion.V1_BINARY);

    }
}
