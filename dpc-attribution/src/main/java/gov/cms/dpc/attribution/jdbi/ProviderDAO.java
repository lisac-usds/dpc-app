package gov.cms.dpc.attribution.jdbi;

import gov.cms.dpc.common.entities.OrganizationEntity_;
import gov.cms.dpc.common.entities.ProviderEntity;
import gov.cms.dpc.common.entities.ProviderEntity_;
import gov.cms.dpc.common.hibernate.attribution.DPCManagedSessionFactory;
import io.dropwizard.hibernate.AbstractDAO;

import javax.inject.Inject;
import javax.persistence.criteria.CriteriaBuilder;
import javax.persistence.criteria.CriteriaQuery;
import javax.persistence.criteria.Predicate;
import javax.persistence.criteria.Root;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public class ProviderDAO extends AbstractDAO<ProviderEntity> {

    @Inject
    public ProviderDAO(DPCManagedSessionFactory factory) {
        super(factory.getSessionFactory());
    }

    public ProviderEntity persistProvider(ProviderEntity provider) {
        return this.persist(provider);
    }

    public Optional<ProviderEntity> getProvider(UUID providerID) {
        return Optional.ofNullable(get(providerID));
    }

    /**
     * Search for matching providers.
     * Organization ID is ALWAYS required. NPI or Resource ID are optional
     *
     * @param providerID     - {@link UUID} direct provider Resource ID
     * @param providerNPI    - {@link String} Provider NPI
     * @param organizationID - {@link UUID} REQUIRED organization resource ID
     * @return - {@link List} of matching {@link ProviderEntity}
     */
    public List<ProviderEntity> getProviders(UUID providerID, String providerNPI, UUID organizationID) {

        // Build a selection query to get records from the database
        final CriteriaBuilder builder = currentSession().getCriteriaBuilder();
        final CriteriaQuery<ProviderEntity> query = builder.createQuery(ProviderEntity.class);
        final Root<ProviderEntity> root = query.from(ProviderEntity.class);

        query.select(root);

        List<Predicate> predicates = new ArrayList<>();
        // Always restrict by Organization
        predicates.add(builder
                .equal(root.join(ProviderEntity_.organization).get(OrganizationEntity_.id),
                        organizationID));

        // If we're provided a resource ID, query for that
        if (providerID != null) {
            predicates.add(builder
                    .equal(root.get(ProviderEntity_.id), providerID));
        }

        // If we've provided an NPI, use it as a query restriction.
        // Otherwise, return everything
        if (providerNPI != null && !providerNPI.isEmpty()) {
            predicates.add(builder
                    .equal(root.get(ProviderEntity_.providerNPI),
                            providerNPI));
        }

        query.where(predicates.toArray(new Predicate[0]));
        return this.list(query);
    }

    /**
     * Remove the {@link ProviderEntity} and all associated resources.
     *
     * @param provider - {@link ProviderEntity} to remove
     */
    public void deleteProvider(ProviderEntity provider) {
        this.currentSession().remove(provider);
    }

    public ProviderEntity updateProvider(UUID providerID, ProviderEntity providerEntity) {
        final ProviderEntity existingProvider = this.getProvider(providerID)
                .orElseThrow(() -> new IllegalArgumentException("Cannot find provider"));

        final ProviderEntity fullyUpdated = existingProvider.update(providerEntity);

        currentSession().merge(fullyUpdated);
        return fullyUpdated;
    }
}
