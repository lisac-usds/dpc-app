package gov.cms.dpc.api.auth;

import com.github.nitram509.jmacaroons.Macaroon;
import gov.cms.dpc.api.jdbi.TokenDAO;
import gov.cms.dpc.common.hibernate.auth.DPCAuthManagedSessionFactory;
import gov.cms.dpc.macaroons.MacaroonBakery;
import gov.cms.dpc.macaroons.exceptions.BakeryException;
import io.dropwizard.auth.AuthFilter;
import io.dropwizard.auth.Authenticator;
import org.apache.http.HttpHeaders;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.annotation.Nullable;
import javax.ws.rs.WebApplicationException;
import javax.ws.rs.container.ContainerRequestContext;
import javax.ws.rs.core.UriInfo;
import java.io.IOException;
import java.util.Collections;
import java.util.UUID;

import static gov.cms.dpc.api.auth.AuthHelpers.BEARER_PREFIX;

/**
 * {@link AuthFilter} implementation which extracts the Macaroon (base64 encoded) from the request.
 * Once extracted, it passes it down along the authn/authz chain.
 * <p>
 * This assumes that the Macaroon is either passed via the {@link HttpHeaders#AUTHORIZATION} header
 * in the form 'Bearer {macaroon-values}'.
 * <p>
 * Or, directly via the 'token' query param (e.g. no Bearer prefix)
 */
abstract class DPCAuthFilter extends AuthFilter<DPCAuthCredentials, OrganizationPrincipal> {

    private static final Logger logger = LoggerFactory.getLogger(DPCAuthFilter.class);


    private final TokenDAO dao;
    private final MacaroonBakery bakery;


    DPCAuthFilter(MacaroonBakery bakery, Authenticator<DPCAuthCredentials, OrganizationPrincipal> auth, TokenDAO dao) {
        this.authenticator = auth;
        this.bakery = bakery;
        this.dao = dao;
    }

    protected abstract DPCAuthCredentials buildCredentials(String macaroon, UUID organizationID, UriInfo uriInfo);

    @Override
    public void filter(final ContainerRequestContext requestContext) throws IOException {
        final UriInfo uriInfo = requestContext.getUriInfo();
        final String macaroon = AuthHelpers.extractMacaroonFromRequest(requestContext, unauthorizedHandler.buildResponse(BEARER_PREFIX, realm));

        // If we have a path authorizer, do that, otherwise, continue
        final DPCAuthCredentials dpcAuthCredentials = validateMacaroon(macaroon, uriInfo);

        final boolean authenticated = this.authenticate(requestContext, dpcAuthCredentials, null);
        if (!authenticated) {
            throw new WebApplicationException(unauthorizedHandler.buildResponse(BEARER_PREFIX, realm));
        }
    }

    private DPCAuthCredentials validateMacaroon(String macaroon, UriInfo uriInfo) {

        logger.trace("Making request to validate token.");

        final Macaroon m1;
        try {
            m1 = bakery.deserializeMacaroon(macaroon);
        } catch (BakeryException e) {
            logger.error("Cannot deserialize Macaroon", e);
            throw new WebApplicationException(unauthorizedHandler.buildResponse(BEARER_PREFIX, realm));
        }

        // Lookup the organization by Macaroon id
        final UUID macaroonID = UUID.fromString(m1.identifier);
        final UUID orgID = this.dao.findOrgByToken(macaroonID);

        try {
            this.bakery.verifyMacaroon(Collections.singletonList(m1), String.format("organization_id = %s", orgID));
        } catch (BakeryException e) {
            logger.error("Macaroon verification failed", e);
            throw new WebApplicationException(unauthorizedHandler.buildResponse(BEARER_PREFIX, realm));
        }

        return buildCredentials(macaroon, orgID, uriInfo);
    }
}
