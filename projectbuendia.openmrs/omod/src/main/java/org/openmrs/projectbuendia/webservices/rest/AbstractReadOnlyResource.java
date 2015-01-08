package org.openmrs.projectbuendia.webservices.rest;

import java.text.DateFormat;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.TimeZone;

import org.openmrs.OpenmrsObject;
import org.openmrs.module.webservices.rest.SimpleObject;
import org.openmrs.module.webservices.rest.web.Hyperlink;
import org.openmrs.module.webservices.rest.web.RequestContext;
import org.openmrs.module.webservices.rest.web.RestConstants;
import org.openmrs.module.webservices.rest.web.annotation.Resource;
import org.openmrs.module.webservices.rest.web.representation.Representation;
import org.openmrs.module.webservices.rest.web.resource.api.Listable;
import org.openmrs.module.webservices.rest.web.resource.api.Retrievable;
import org.openmrs.module.webservices.rest.web.resource.api.Searchable;
import org.openmrs.module.webservices.rest.web.response.ObjectNotFoundException;
import org.openmrs.module.webservices.rest.web.response.ResponseException;

/**
 * Abstract superclass for resources where the REST API only supports read-only
 * operations. Subclasses are required to provide the Resource annotation to specify the URL etc. 
 */
public abstract class AbstractReadOnlyResource<T extends OpenmrsObject> implements Listable, Retrievable, Searchable {

    private static final TimeZone UTC = TimeZone.getTimeZone("Etc/UTC");
    private static final DateFormat FORMAT = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US);
    static {
        FORMAT.setTimeZone(UTC);
    }

    static final String RESULTS = "results";
    static final String SNAPSHOT_TIME = "snapshotTime";
    static final String UUID = "uuid";
    static final String LINKS = "links";
    static final String SELF = "self";
    static final RequestLogger logger = RequestLogger.LOGGER;

    // TODO(nfortescue): Move out, or find somewhere else this is already done.
    protected static String toIso8601(Date dateTime) {
        return FORMAT.format(dateTime);
    }

    private final String resourceAlias;
    private final List<Representation> availableRepresentations;

    protected AbstractReadOnlyResource(String resourceAlias, Representation... representations) {
        availableRepresentations = Arrays.asList(representations);
        this.resourceAlias = resourceAlias;
    }
    
    @Override
    public String getUri(Object instance) {
        OpenmrsObject mrsObject = (OpenmrsObject) instance;
        Resource res = getClass().getAnnotation(Resource.class);
        return RestConstants.URI_PREFIX + res.name() + "/" + mrsObject.getUuid();
    }

    @Override
    public SimpleObject search(RequestContext context) throws ResponseException {
        try {
            logger.request(context, this, "search");
            long snapshotTime = System.currentTimeMillis();
            SimpleObject result = searchInner(context, snapshotTime);
            logger.reply(context, this, "search", result);
            return result;
        } catch (Exception e) {
            logger.error(context, this, "search", e);
            throw e;
        }
    }

    private SimpleObject searchInner(RequestContext context, long snapshotTime) throws ResponseException {
        List<SimpleObject> results = new ArrayList<>();
        for (T item : searchImpl(context, snapshotTime)) {
            results.add(convertToJson(item, context, snapshotTime));
        }
        SimpleObject response = new SimpleObject();
        response.put(RESULTS, results);
        response.put(SNAPSHOT_TIME, toIso8601(new Date(snapshotTime)));
        return response;
    }

    @Override
    public Object retrieve(String uuid, RequestContext context) throws ResponseException {
        try {
            logger.request(context, this, "retrieve", uuid);
            Object result = retrieveInner(uuid, context, System.currentTimeMillis());
            logger.reply(context, this, "retrieve", result);
            return result;
        } catch (Exception e) {
            logger.error(context, this, "retrieve", e);
            throw e;
        }
    }

    private Object retrieveInner(String uuid, RequestContext context, long snapshotTime) throws ResponseException {
        T item = retrieveImpl(uuid, context, snapshotTime);
        if (item == null) {
            throw new ObjectNotFoundException();
        }
        return convertToJson(item, context, snapshotTime);
    }

    @Override
    public List<Representation> getAvailableRepresentations() {
        return availableRepresentations;
    }

    /**
     * Delegates directly to search; the implementation of searchImpl should list all
     * appropriate items if no query parameters have been given. 
     */
    @Override
    public SimpleObject getAll(RequestContext context) throws ResponseException {
        return search(context);
    }

    /**
     * Find all the domain objects matching the query within the request context.
     * Note that this method is also used for "get all" so it's entirely possible
     * for the context to not contain any query parameters.
     */
    protected abstract Iterable<T> searchImpl(RequestContext context, long snapshotTime);
    
    /**
     * Retrieve a single domain object by UUID, returning null if it can't be found.
     */
    protected abstract T retrieveImpl(String uuid, RequestContext context, long snapshotTime);
    
    /**
     * Converts a single domain object to JSON. By default, this populates the UUID
     * and a self link automatically, then delegates to populateJsonProperties for the
     * remaining information. This is expected to be sufficient for most cases, but
     * subclasses can override this method if they want more flexibility.
     */
    protected SimpleObject convertToJson(T item, RequestContext context, long snapshotTime) {
        SimpleObject json = new SimpleObject();
        json.put(UUID, item.getUuid());
        json.put(LINKS, getLinks(item));
        // TODO(jonskeet): Version, date created etc?
        populateJsonProperties(item, context, json, snapshotTime);
        return json;
    }
    
    protected abstract void populateJsonProperties(T item, RequestContext context, SimpleObject json,
                                                   long snapshotTime);
    
    /**
     * Retrieves the links for the given item. The default implementation just adds a self link. 
     */
    protected List<Hyperlink> getLinks(T item) {
        Hyperlink self = new Hyperlink(SELF, getUri(item));
        self.setResourceAlias(resourceAlias);
        List<Hyperlink> links = new ArrayList<>();
        links.add(self);
        return links;
    }
}
