package gov.cms.dpc.bluebutton.client;


import ca.uhn.fhir.rest.server.exceptions.ResourceNotFoundException;
import gov.cms.dpc.fhir.DPCIdentifierSystem;
import org.hl7.fhir.dstu3.model.Bundle;
import org.hl7.fhir.dstu3.model.CapabilityStatement;
import org.hl7.fhir.dstu3.model.ExplanationOfBenefit;
import org.hl7.fhir.dstu3.model.Patient;

import java.security.GeneralSecurityException;


public interface BlueButtonClient {

    /**
     * Queries Blue Button server for patient data
     *
     * @param patientID The requested patient's ID
     * @return {@link Patient} A FHIR Patient resource
     * @throws ResourceNotFoundException when no such patient with the provided ID exists
     */
    Patient requestPatientFromServer(String patientID) throws ResourceNotFoundException;

    /**
     * Search operation on BFD that returns {@link Bundle} of {@link Patient} resources with the corresponding {@link DPCIdentifierSystem#MBI_HASH}.
     * This should only ever return a single resource, but downstream users should still check for it.
     *
     * @param mbiHash - {@link String} hashed {@link DPCIdentifierSystem#MBI} value.
     * @return - {@link Bundle} of {@link Patient} (only 1) with the given MBI
     * @throws ResourceNotFoundException if no {@link Patient} records match
     */
    Bundle requestPatientFromServerByMbiHash(String mbiHash) throws ResourceNotFoundException;

    /**
     * Request a {@link Patient} resource from BlueButton using the {@link DPCIdentifierSystem#MBI}.
     * This calls {@link BlueButtonClient#hashMbi(String)} to generate the hashed value to send to BFD.
     * There should ONLY ever be a single patient returned from {@link BlueButtonClient#requestPatientFromServerByMbiHash(String)}.
     * We check for it and throw a {@link ResourceNotFoundException} if that occurs.
     *
     * @param mbi - {@link DPCIdentifierSystem#MBI} to search for matching patient
     * @return - {@link Patient} with matching MBI
     * @throws ResourceNotFoundException - if no patient has the corresponding {@link DPCIdentifierSystem#MBI} OR if more than 1 {@link Patient} resource is returned.
     */
    Patient requestPatientByMbi(String mbi) throws ResourceNotFoundException;

    /**
     * Queries Blue Button server for Explanations of Benefit associated with a given patient
     * <p>
     * There are two edge cases to consider when pulling EoB data given a MBI:
     * 1. No patient with the given ID exists: if this is the case, BlueButton should return a Bundle with no
     * entry, i.e. ret.hasEntry() will evaluate to false. For this case, the method will throw a
     * {@link ResourceNotFoundException}
     * <p>
     * 2. A patient with the given ID exists, but has no associated EoB records: if this is the case, BlueButton should
     * return a Bundle with an entry of size 0, i.e. ret.getEntry().size() == 0. For this case, the method simply
     * returns the Bundle it received from BlueButton to the caller, and the caller is responsible for handling Bundles
     * that contain no EoBs.
     *
     * @param mbi The requested patient's ID
     * @return {@link Bundle} Containing a number (possibly 0) of {@link ExplanationOfBenefit} objects
     * @throws ResourceNotFoundException when the requested patient does not exist
     */
    Bundle requestEOBFromServer(String mbi) throws ResourceNotFoundException;

    /**
     * Queries Blue Button server for Coverage associated with a given patient
     * <p>
     * Like for the EOB resource, there are two edge cases to consider when pulling coverage data given a mbi:
     * 1. No patient with the given ID exists: if this is the case, BlueButton should return a Bundle with no
     * entry, i.e. ret.hasEntry() will evaluate to false. For this case, the method will throw a
     * {@link ResourceNotFoundException}
     * <p>
     * 2. A patient with the given ID exists, but has no associated Coverage records: if this is the case, BlueButton should
     * return a Bundle with an entry of size 0, i.e. ret.getEntry().size() == 0. For this case, the method simply
     * returns the Bundle it received from BlueButton to the caller, and the caller is responsible for handling Bundles
     * that contain no coverage records.
     *
     * @param mbi The requested patient's ID
     * @return {@link Bundle} Containing a number (possibly 0) of {@link ExplanationOfBenefit} objects
     * @throws ResourceNotFoundException when the requested patient does not exist
     */
    Bundle requestCoverageFromServer(String mbi) throws ResourceNotFoundException;

    Bundle requestNextBundleFromServer(Bundle bundle) throws ResourceNotFoundException;

    CapabilityStatement requestCapabilityStatement() throws ResourceNotFoundException;

    String hashMbi(String mbi) throws GeneralSecurityException;
}

