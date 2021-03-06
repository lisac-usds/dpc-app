package gov.cms.dpc.api.resources.v1;

import ca.uhn.fhir.parser.IParser;
import ca.uhn.fhir.rest.api.MethodOutcome;
import ca.uhn.fhir.rest.client.api.IGenericClient;
import ca.uhn.fhir.rest.gclient.IOperationUntypedWithInput;
import ca.uhn.fhir.rest.gclient.IReadExecutable;
import ca.uhn.fhir.rest.gclient.IUpdateTyped;
import ca.uhn.fhir.rest.server.exceptions.AuthenticationException;
import ca.uhn.fhir.rest.server.exceptions.InvalidRequestException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.nitram509.jmacaroons.Macaroon;
import com.github.nitram509.jmacaroons.MacaroonVersion;
import com.github.nitram509.jmacaroons.MacaroonsBuilder;
import gov.cms.dpc.api.APITestHelpers;
import gov.cms.dpc.api.AbstractSecureApplicationTest;
import gov.cms.dpc.api.entities.PublicKeyEntity;
import gov.cms.dpc.api.entities.TokenEntity;
import gov.cms.dpc.api.models.CollectionResponse;
import gov.cms.dpc.fhir.helpers.FHIRHelpers;
import gov.cms.dpc.macaroons.MacaroonBakery;
import gov.cms.dpc.testing.APIAuthHelpers;
import gov.cms.dpc.testing.OrganizationHelpers;
import gov.cms.dpc.testing.factories.OrganizationFactory;
import org.apache.commons.lang3.tuple.Pair;
import org.apache.http.HttpHeaders;
import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClients;
import org.assertj.core.util.Lists;
import org.hl7.fhir.dstu3.model.Bundle;
import org.hl7.fhir.dstu3.model.Endpoint;
import org.hl7.fhir.dstu3.model.Organization;
import org.hl7.fhir.dstu3.model.Parameters;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.URISyntaxException;
import java.security.NoSuchAlgorithmException;
import java.security.PrivateKey;
import java.util.UUID;

import static gov.cms.dpc.api.APITestHelpers.ORGANIZATION_ID;
import static gov.cms.dpc.testing.APIAuthHelpers.TASK_URL;
import static org.junit.jupiter.api.Assertions.*;

class OrganizationResourceTest extends AbstractSecureApplicationTest {

    private final ObjectMapper mapper;

    OrganizationResourceTest() {
        this.mapper = new ObjectMapper();
    }

    @Test
    void testOrganizationRegistration() throws IOException {
        // Generate a golden macaroon
        final String goldenMacaroon = APIAuthHelpers.createGoldenMacaroon();
        final IGenericClient client = APIAuthHelpers.buildAdminClient(ctx, getBaseURL(), goldenMacaroon, false);


        final String newOrgID = UUID.randomUUID().toString();
        final Organization organization = OrganizationHelpers.createOrganization(ctx, client, newOrgID, true);
        assertNotNull(organization);

        // Try again, should fail because it's a duplicate
        // Error handling is really bad right now, but it should get improved in DPC-540
        assertThrows(InvalidRequestException.class, () -> OrganizationHelpers.createOrganization(ctx, client, newOrgID, true));

        // Now, try to create one again, but using an actual org token
        assertThrows(AuthenticationException.class, () -> OrganizationHelpers.createOrganization(ctx, APIAuthHelpers.buildAuthenticatedClient(ctx, getBaseURL(), ORGANIZATION_TOKEN, PUBLIC_KEY_ID, PRIVATE_KEY), UUID.randomUUID().toString(), true));
    }

    @Test
    void testOrganizationFetch() {

        final IGenericClient client = APIAuthHelpers.buildAuthenticatedClient(ctx, getBaseURL(), ORGANIZATION_TOKEN, PUBLIC_KEY_ID, PRIVATE_KEY);

        final Organization organization = client
                .read()
                .resource(Organization.class)
                .withId(ORGANIZATION_ID)
                .encodedJson()
                .execute();

        assertNotNull(organization, "Should have organization");

        // Try to get all public endpoints
        final Bundle endPointBundle = client
                .search()
                .forResource(Endpoint.class)
                .encodedJson()
                .returnBundle(Bundle.class)
                .execute();
        assertEquals(1, endPointBundle.getTotal(), "Should have one endpoint");

        // Try to fetch it
        final Endpoint endpoint = (Endpoint) endPointBundle.getEntryFirstRep().getResource();
        final Endpoint fetchedEndpoint = client
                .read()
                .resource(Endpoint.class)
                .withId(endpoint.getId())
                .encodedJson()
                .execute();

        assertTrue(endpoint.equalsDeep(fetchedEndpoint), "Should have matching records");
    }


    @Test
    void testMissingEndpoint() throws IOException {
        // Generate a golden macaroon
        final String goldenMacaroon = APIAuthHelpers.createGoldenMacaroon();
        final IGenericClient client = APIAuthHelpers.buildAdminClient(ctx, getBaseURL(), goldenMacaroon, false);
        final Organization organization = OrganizationFactory.generateFakeOrganization();

        final Bundle bundle = new Bundle();
        bundle.addEntry().setResource(organization);

        final Parameters parameters = new Parameters();
        parameters.addParameter().setName("resource").setResource(bundle);

        final IOperationUntypedWithInput<Organization> operation = client
                .operation()
                .onType(Organization.class)
                .named("submit")
                .withParameters(parameters)
                .returnResourceType(Organization.class)
                .encodedJson();

        assertThrows(InvalidRequestException.class, operation::execute, "Should be unprocessable");

    }

    @Test
    void testMissingOrganization() throws IOException {
        // Generate a golden macaroon
        final String goldenMacaroon = APIAuthHelpers.createGoldenMacaroon();
        final IGenericClient client = APIAuthHelpers.buildAdminClient(ctx, getBaseURL(), goldenMacaroon, false);
        final Endpoint endpoint = OrganizationFactory.createFakeEndpoint();

        final Bundle bundle = new Bundle();
        bundle.addEntry().setResource(endpoint);

        final Parameters parameters = new Parameters();
        parameters.addParameter().setName("resource").setResource(bundle);

        final IOperationUntypedWithInput<Organization> operation = client
                .operation()
                .onType(Organization.class)
                .named("submit")
                .withParameters(parameters)
                .returnResourceType(Organization.class)
                .encodedJson();

        assertThrows(InvalidRequestException.class, operation::execute, "Should be unprocessable");
    }

    @Test
    void testUpdateOrganization() throws IOException, URISyntaxException, NoSuchAlgorithmException {
        final String orgID = UUID.randomUUID().toString();
        final IParser parser = ctx.newJsonParser();
        final IGenericClient attrClient = APITestHelpers.buildAttributionClient(ctx);
        final String macaroon = FHIRHelpers.registerOrganization(attrClient, parser, orgID, getAdminURL());
        final Pair<UUID, PrivateKey> uuidPrivateKeyPair = APIAuthHelpers.generateAndUploadKey("org-update-key", orgID, GOLDEN_MACAROON, getBaseURL());
        final IGenericClient client = APIAuthHelpers.buildAuthenticatedClient(ctx, getBaseURL(), macaroon, uuidPrivateKeyPair.getLeft(), uuidPrivateKeyPair.getRight());

        Organization organization = client
                .read()
                .resource(Organization.class)
                .withId(orgID)
                .encodedJson()
                .execute();

        assertNotNull(organization);

        organization.setName("New Org Name");
        organization.setContact(Lists.emptyList());

        MethodOutcome outcome = client
                .update()
                .resource(organization)
                .execute();

        Organization result = (Organization) outcome.getResource();
        assertEquals(orgID, result.getIdentifierFirstRep().getValue());
        assertEquals(organization.getName(), result.getName(), "Name should be updated");
        assertTrue(organization.getContact().isEmpty(), "Contact list should be updated");
        assertEquals(1, result.getEndpoint().size(), "Endpoint list should be unchanged");

        // Try to update when authenticated as different organization
        final String org2ID = UUID.randomUUID().toString();
        final String org2Macaroon = FHIRHelpers.registerOrganization(attrClient, parser, org2ID, getAdminURL());
        final Pair<UUID, PrivateKey> org2UUIDPrivateKeyPair = APIAuthHelpers.generateAndUploadKey("org2-update-key", org2ID, GOLDEN_MACAROON, getBaseURL());
        final IGenericClient org2Client = APIAuthHelpers.buildAuthenticatedClient(ctx, getBaseURL(), org2Macaroon, org2UUIDPrivateKeyPair.getLeft(), org2UUIDPrivateKeyPair.getRight());

        IUpdateTyped update = org2Client.update().resource(organization);
        assertThrows(AuthenticationException.class, update::execute);
    }

    @Test
    void testOrganizationDeletion() throws IOException, URISyntaxException, NoSuchAlgorithmException {
//        // Generate a golden macaroon
        final UUID orgDeletionID = UUID.randomUUID();
        final IGenericClient attrClient = APITestHelpers.buildAttributionClient(ctx);
        FHIRHelpers.registerOrganization(attrClient, ctx.newJsonParser(), orgDeletionID.toString(), TASK_URL);

        // Register Public key
        APIAuthHelpers.generateAndUploadKey("org-deletion-key", orgDeletionID.toString(), GOLDEN_MACAROON, "http://localhost:3002/v1/");
        final IGenericClient client = APIAuthHelpers.buildAdminClient(ctx, getBaseURL(), GOLDEN_MACAROON, false);

        // Delegate the Macaroon
        final Macaroon macaroon = MacaroonBakery.deserializeMacaroon(GOLDEN_MACAROON).get(0);
        final String delegatedMacaroon = MacaroonsBuilder.modify(macaroon)
                .add_first_party_caveat(String.format("organization_id = %s", orgDeletionID.toString()))
                .getMacaroon()
                .serialize(MacaroonVersion.SerializationVersion.V2_JSON);

        // Verify the delegated token works
        final CollectionResponse<TokenEntity> tokens = fetchTokens(delegatedMacaroon);
        assertEquals(1, tokens.getEntities().size(), "Should have access token");

        final CollectionResponse<PublicKeyEntity> keys = fetchKeys(delegatedMacaroon);
        assertEquals(1, keys.getEntities().size(), "Should have public key");


        // Now, delete it
        client
                .delete()
                .resourceById("Organization", orgDeletionID.toString())
                .encodedJson()
                .execute();

        // Ensure it actually is gone
        final IReadExecutable<Organization> readRequest = client
                .read()
                .resource(Organization.class)
                .withId(orgDeletionID.toString())
                .encodedJson();

        assertThrows(AuthenticationException.class, readRequest::execute, "Should not have organization");

        // Look for tokens that might exist

        final CollectionResponse<TokenEntity> emptyTokens = fetchTokens(delegatedMacaroon);
        assertTrue(emptyTokens.getEntities().isEmpty(), "Should not have access tokens");

        final CollectionResponse<PublicKeyEntity> emptyKeys = fetchKeys(delegatedMacaroon);
        assertTrue(emptyKeys.getEntities().isEmpty(), "Should not have keys");
    }

    CollectionResponse<TokenEntity> fetchTokens(String token) throws IOException {
        try (final CloseableHttpClient client = HttpClients.createDefault()) {
            final HttpGet httpGet = new HttpGet(getBaseURL() + "/Token");
            httpGet.addHeader(HttpHeaders.AUTHORIZATION, String.format("Bearer %s", token));

            try (CloseableHttpResponse response = client.execute(httpGet)) {
                return this.mapper.readValue(response.getEntity().getContent(), new TypeReference<CollectionResponse<TokenEntity>>() {
                });
            }
        }
    }

    CollectionResponse<PublicKeyEntity> fetchKeys(String token) throws IOException {
        try (final CloseableHttpClient client = HttpClients.createDefault()) {
            final HttpGet httpGet = new HttpGet(getBaseURL() + "/Key");
            httpGet.addHeader(HttpHeaders.AUTHORIZATION, String.format("Bearer %s", token));

            try (CloseableHttpResponse response = client.execute(httpGet)) {
                return this.mapper.readValue(response.getEntity().getContent(), new TypeReference<CollectionResponse<PublicKeyEntity>>() {
                });
            }
        }
    }
}
