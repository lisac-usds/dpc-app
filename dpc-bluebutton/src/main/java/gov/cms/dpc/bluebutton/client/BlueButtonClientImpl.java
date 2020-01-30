package gov.cms.dpc.bluebutton.client;

import ca.uhn.fhir.rest.client.api.IGenericClient;
import ca.uhn.fhir.rest.gclient.ICriterion;
import ca.uhn.fhir.rest.gclient.IParam;
import ca.uhn.fhir.rest.gclient.IQuery;
import ca.uhn.fhir.rest.gclient.TokenClientParam;
import ca.uhn.fhir.rest.server.exceptions.ResourceNotFoundException;
import com.codahale.metrics.Meter;
import com.codahale.metrics.MetricRegistry;
import com.codahale.metrics.Timer;
import gov.cms.dpc.bluebutton.config.BBClientConfiguration;
import gov.cms.dpc.common.utils.MetricMaker;
import gov.cms.dpc.fhir.DPCIdentifierSystem;
import org.apache.commons.lang3.StringUtils;
import org.bouncycastle.util.encoders.Hex;
import org.hl7.fhir.dstu3.model.*;
import org.hl7.fhir.instance.model.api.IBaseBundle;
import org.hl7.fhir.instance.model.api.IBaseResource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.crypto.SecretKey;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;
import java.security.GeneralSecurityException;
import java.security.NoSuchAlgorithmException;
import java.security.spec.KeySpec;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;


public class BlueButtonClientImpl implements BlueButtonClient {

    private static final String REQUEST_PATIENT_METRIC = "requestPatient";
    private static final String REQUEST_EOB_METRIC = "requestEOB";
    private static final String REQUEST_COVERAGE_METRIC = "requestCoverage";
    private static final String REQUEST_NEXT_METRIC = "requestNextBundle";
    private static final String REQUEST_CAPABILITIES_METRIC = "requestCapabilities";
    private static final List<String> REQUEST_METRICS = List.of(REQUEST_PATIENT_METRIC, REQUEST_EOB_METRIC, REQUEST_COVERAGE_METRIC, REQUEST_NEXT_METRIC, REQUEST_CAPABILITIES_METRIC);

    private static final Logger logger = LoggerFactory.getLogger(BlueButtonClientImpl.class);

    private IGenericClient client;
    private BBClientConfiguration config;
    private Map<String, Timer> timers;
    private Map<String, Meter> exceptionMeters;
    private byte[] bfdHashPepper;
    private int bfdHashIter;
    private static final String HASH_ALGORITHM = "PBKDF2WithHmacSHA256";

    private static String formBeneficiaryID(String fromPatientID) {
        return "Patient/" + fromPatientID;
    }

    public BlueButtonClientImpl(IGenericClient client, BBClientConfiguration config, MetricRegistry metricRegistry) {
        this.client = client;
        this.config = config;
        final var metricMaker = new MetricMaker(metricRegistry, BlueButtonClientImpl.class);
        this.exceptionMeters = metricMaker.registerMeters(REQUEST_METRICS);
        this.timers = metricMaker.registerTimers(REQUEST_METRICS);

        bfdHashIter = config.getBfdHashIter();
        if (config.getBfdHashPepper() != null) {
            bfdHashPepper = Hex.decode(config.getBfdHashPepper());
        }
    }

    @Override
    public Patient requestPatientFromServer(String patientID) throws ResourceNotFoundException {
        logger.debug("Attempting to fetch patient ID {} from baseURL: {}", patientID, client.getServerBase());
        return instrumentCall(REQUEST_PATIENT_METRIC, () -> client
                .read()
                .resource(Patient.class)
                .withId(patientID)
                .execute());
    }

    /**
     * Queries Blue Button server for patient data by hashed Medicare Beneficiary Identifier (MBI).
     *
     * @param mbiHash The hashed MBI
     * @return {@link Bundle} A FHIR Bundle of Patient resources
     */
    @Override
    public Bundle requestPatientFromServerByMbiHash(String mbiHash) throws ResourceNotFoundException {
        logger.debug("Attempting to fetch patient with MBI hash {} from baseURL: {}", mbiHash, client.getServerBase());
        return instrumentCall(REQUEST_PATIENT_METRIC, () -> client
                .search()
                .forResource(Patient.class)
                .where(Patient.IDENTIFIER.exactly().systemAndIdentifier(DPCIdentifierSystem.MBI_HASH.getSystem(), mbiHash))
                .returnBundle(Bundle.class)
                .execute());
    }

    @Override
    public Patient requestPatientByMbi(String mbi) throws ResourceNotFoundException {
        logger.debug("Attempting to fetch patient with MBI {} from baseURL: {}", mbi, client.getServerBase());
        try {
            final Bundle patientBundle = requestPatientFromServerByMbiHash(hashMbi(mbi));
            if (patientBundle.getTotal() == 0) {
                final IdType idType = new IdType(DPCIdentifierSystem.MBI.getSystem(), mbi);
                throw new ResourceNotFoundException(idType);
            }
            if (patientBundle.getTotal() > 1) {
                logger.error("MULTIPLE PATIENTS MATCH MBI: {}", mbi);
                final IdType idType = new IdType(DPCIdentifierSystem.MBI.getSystem(), mbi);
                throw new ResourceNotFoundException(idType);
            }
            return (Patient) patientBundle.getEntryFirstRep().getResource();
        } catch (GeneralSecurityException e) {
            logger.error("Unable to hash MBI.", e);
            throw new RuntimeException(e);
        }
    }


    @Override
    public Bundle requestEOBFromServer(String mbi) {
        logger.debug("Attempting to fetch EOBs for patient with MBI {} from baseURL: {}", mbi, client.getServerBase());
        final Patient patient = requestPatientByMbi(mbi);
        List<ICriterion<? extends IParam>> criteria = new ArrayList<>();
        final String patientID = patient.getIdElement().getIdPart();
        criteria.add(ExplanationOfBenefit.PATIENT.hasId(patientID));
        criteria.add(new TokenClientParam("excludeSAMHSA").exactly().code("true"));

        return instrumentCall(REQUEST_EOB_METRIC, () ->
                fetchBundle(ExplanationOfBenefit.class,
                        criteria,
                        patientID));
    }

    @Override
    public Bundle requestCoverageFromServer(String mbi) throws ResourceNotFoundException {
        logger.debug("Attempting to fetch Coverage for patient with MBI {} from baseURL: {}", mbi, client.getServerBase());

        final Patient patient = requestPatientByMbi(mbi);
        List<ICriterion<? extends IParam>> criteria = new ArrayList<>();
        final String patientID = patient.getIdElement().getIdPart();
        criteria.add(Coverage.BENEFICIARY.hasId(formBeneficiaryID(patientID)));

        return instrumentCall(REQUEST_COVERAGE_METRIC, () ->
                fetchBundle(Coverage.class, criteria, patientID));
    }

    @Override
    public Bundle requestNextBundleFromServer(Bundle bundle) throws ResourceNotFoundException {
        return instrumentCall(REQUEST_NEXT_METRIC, () -> {
            var nextURL = bundle.getLink(Bundle.LINK_NEXT).getUrl();
            logger.debug("Attempting to fetch next bundle from url: {}", nextURL);
            return client
                    .loadPage()
                    .next(bundle)
                    .execute();
        });
    }

    @Override
    public CapabilityStatement requestCapabilityStatement() throws ResourceNotFoundException {
        return instrumentCall(REQUEST_CAPABILITIES_METRIC, () -> client
                .capabilities()
                .ofType(CapabilityStatement.class)
                .execute());
    }

    @Override
    public String hashMbi(String mbi) throws GeneralSecurityException {
        if (StringUtils.isBlank(mbi)) {
            logger.error("Could not generate hash; provided MBI string was null or empty");
            return "";
        }

        final SecretKeyFactory instance;
        try {
            instance = SecretKeyFactory.getInstance(HASH_ALGORITHM);
        } catch (NoSuchAlgorithmException e) {
            logger.error("Secret key factory could not be created due to invalid algorithm: {}", HASH_ALGORITHM);
            throw new GeneralSecurityException(e);
        }

        KeySpec keySpec = new PBEKeySpec(mbi.toCharArray(), bfdHashPepper, bfdHashIter, 256);
        SecretKey secretKey = instance.generateSecret(keySpec);
        return Hex.toHexString(secretKey.getEncoded());
    }

    /**
     * Read a FHIR Bundle from BlueButton. Limits the returned size by resourcesPerRequest.
     *
     * @param resourceClass - FHIR Resource class
     * @param criteria      - For the resource class the correct criteria that match the patientID
     * @param patientID     - id of patient
     * @return FHIR Bundle resource
     */
    private <T extends IBaseResource> Bundle fetchBundle(Class<T> resourceClass,
                                                         List<ICriterion<? extends IParam>> criteria,
                                                         String patientID) {
        IQuery<IBaseBundle> query = client.search()
                .forResource(resourceClass)
                .where(criteria.remove(0));

        for (ICriterion<? extends IParam> criterion : criteria) {
            query = query.and(criterion);
        }

        final Bundle bundle = query.count(config.getResourcesCount())
                .returnBundle(Bundle.class)
                .execute();

        // Case where patientID does not exist at all
        if (!bundle.hasEntry()) {
            throw new ResourceNotFoundException("No patient found with MBI: " + patientID);
        }
        return bundle;
    }

    /**
     * Instrument a call to Blue Button.
     *
     * @param metricName - The name of the method
     * @param supplier   - the call as lambda to instrumented
     * @param <T>        - the type returned by the call
     * @return the value returned by the supplier (i.e. call)
     */
    private <T> T instrumentCall(String metricName, Supplier<T> supplier) {
        final var timerContext = timers.get(metricName).time();
        try {
            return supplier.get();
        } catch (Exception ex) {
            final var exceptionMeter = exceptionMeters.get(metricName);
            exceptionMeter.mark();
            throw ex;
        } finally {
            timerContext.stop();
        }
    }
}
