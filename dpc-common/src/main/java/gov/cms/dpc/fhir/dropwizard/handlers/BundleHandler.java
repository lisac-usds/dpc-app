package gov.cms.dpc.fhir.dropwizard.handlers;

import com.google.common.reflect.TypeToken;
import gov.cms.dpc.fhir.FHIRMediaTypes;
import gov.cms.dpc.fhir.annotations.BundleReturnProperties;
import gov.cms.dpc.fhir.annotations.FHIR;
import org.hl7.fhir.dstu3.model.Bundle;
import org.hl7.fhir.dstu3.model.Resource;

import javax.inject.Inject;
import javax.ws.rs.Consumes;
import javax.ws.rs.Produces;
import javax.ws.rs.WebApplicationException;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.MultivaluedMap;
import javax.ws.rs.ext.MessageBodyWriter;
import javax.ws.rs.ext.Provider;
import java.io.IOException;
import java.io.OutputStream;
import java.lang.annotation.Annotation;
import java.lang.reflect.Type;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * {@link MessageBodyWriter} implementation that creates {@link Bundle} resources from a given {@link Collection} of {@link Resource}es.
 * This uses Guava's {@link TypeToken} to handle the runtime type reflection.
 */
@Provider
@FHIR
@Consumes({FHIRMediaTypes.FHIR_JSON})
@Produces({FHIRMediaTypes.FHIR_JSON})
@SuppressWarnings("UnstableApiUsage")
public class BundleHandler implements MessageBodyWriter<Collection<Resource>> {

    final FHIRHandler handler;
    private static final TypeToken<Collection<? extends Resource>> COLLECTION_TYPE_TOKEN = new TypeToken<>() {
    };

    @Inject
    public BundleHandler(FHIRHandler handler) {
        this.handler = handler;
    }

    @Override
    public boolean isWriteable(Class<?> type, Type genericType, Annotation[] annotations, MediaType mediaType) {
        final TypeToken<?> typeToken = TypeToken.of(genericType);
        return COLLECTION_TYPE_TOKEN.isSupertypeOf(typeToken);
    }

    @Override
    public void writeTo(Collection<Resource> baseResources, Class<?> type, Type genericType, Annotation[] annotations, MediaType mediaType, MultivaluedMap<String, Object> httpHeaders, OutputStream entityStream) throws IOException, WebApplicationException {
        final Bundle bundle = generateBaseBundle(annotations, baseResources.size());
        final List<Bundle.BundleEntryComponent> entries = baseResources
                .stream()
                .map(resource -> {
                    final Bundle.BundleEntryComponent bundleEntryComponent = new Bundle.BundleEntryComponent();
                    bundleEntryComponent.setResource(resource);
                    return bundleEntryComponent;
                })
                .collect(Collectors.toList());

        bundle.setEntry(entries);
        this.handler.writeTo(bundle, type, genericType, annotations, mediaType, httpHeaders, entityStream);
    }

    private Bundle generateBaseBundle(Annotation[] annotations, int entryCount) {
        final Optional<BundleReturnProperties> maybeAnnotation = Arrays.stream(annotations)
                .filter(a -> a.annotationType().equals(BundleReturnProperties.class))
                .map(a -> (BundleReturnProperties) a)
                .findAny();

        final Bundle.BundleType bundleType = maybeAnnotation.map(BundleReturnProperties::bundleType).orElse(Bundle.BundleType.SEARCHSET);

        final Bundle bundle = new Bundle();
        bundle.setType(bundleType);

        if (bundleType.equals(Bundle.BundleType.SEARCHSET)) {
            bundle.setTotal(entryCount);
        }

        return bundle;
    }
}
