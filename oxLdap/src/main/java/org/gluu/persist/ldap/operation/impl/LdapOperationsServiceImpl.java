/*
 * oxCore is available under the MIT License (2008). See http://opensource.org/licenses/MIT for full text.
 *
 * Copyright (c) 2014, Gluu
 */

package org.gluu.persist.ldap.operation.impl;

import java.io.Serializable;

import java.util.Arrays;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.unboundid.ldap.sdk.schema.AttributeTypeDefinition;
import org.apache.commons.lang.StringUtils;
import org.apache.log4j.Logger;
import org.gluu.persist.exception.mapping.MappingException;
import org.gluu.persist.exception.operation.ConnectionException;
import org.gluu.persist.exception.operation.DuplicateEntryException;
import org.gluu.persist.exception.operation.SearchException;
import org.gluu.persist.ldap.exception.InvalidSimplePageControlException;
import org.gluu.persist.ldap.impl.LdapBatchOperationWraper;
import org.gluu.persist.ldap.operation.LdapOperationService;
import org.gluu.persist.model.BatchOperation;
import org.gluu.persist.model.ListViewResponse;
import org.gluu.persist.model.SortOrder;
import org.xdi.util.ArrayHelper;
import org.xdi.util.Pair;
import org.xdi.util.StringHelper;

import com.unboundid.asn1.ASN1OctetString;
import com.unboundid.ldap.sdk.Attribute;
import com.unboundid.ldap.sdk.BindResult;
import com.unboundid.ldap.sdk.Control;
import com.unboundid.ldap.sdk.DeleteRequest;
import com.unboundid.ldap.sdk.Filter;
import com.unboundid.ldap.sdk.LDAPConnection;
import com.unboundid.ldap.sdk.LDAPConnectionPool;
import com.unboundid.ldap.sdk.LDAPException;
import com.unboundid.ldap.sdk.LDAPResult;
import com.unboundid.ldap.sdk.LDAPSearchException;
import com.unboundid.ldap.sdk.Modification;
import com.unboundid.ldap.sdk.ModificationType;
import com.unboundid.ldap.sdk.ModifyRequest;
import com.unboundid.ldap.sdk.ResultCode;
import com.unboundid.ldap.sdk.SearchRequest;
import com.unboundid.ldap.sdk.SearchResult;
import com.unboundid.ldap.sdk.SearchResultEntry;
import com.unboundid.ldap.sdk.SearchResultReference;
import com.unboundid.ldap.sdk.SearchScope;
import com.unboundid.ldap.sdk.controls.ServerSideSortRequestControl;
import com.unboundid.ldap.sdk.controls.SimplePagedResultsControl;
import com.unboundid.ldap.sdk.controls.SortKey;
import com.unboundid.ldap.sdk.controls.SubtreeDeleteRequestControl;
import com.unboundid.ldap.sdk.controls.VirtualListViewRequestControl;
import com.unboundid.ldap.sdk.controls.VirtualListViewResponseControl;
import com.unboundid.ldif.LDIFChangeRecord;

/**
 * OperationsFacade is the base class that performs all the ldap operations
 * using connectionpool
 *
 * @author Pankaj
 * @author Yuriy Movchan
 */
public class LdapOperationsServiceImpl implements LdapOperationService {

    private static final Logger LOG = Logger.getLogger(LdapOperationsServiceImpl.class);

    public static final String DN = "dn";
    public static final String UID = "uid";
    public static final String SUCCESS = "success";
    public static final String USER_PASSWORD = "userPassword";
    public static final String OBJECT_CLASS = "objectClass";

    private LdapConnectionProvider connectionProvider;
    private LdapConnectionProvider bindConnectionProvider;

    private static Map<String, Class<?>> ATTRIBUTE_DATA_TYPES = new HashMap<String, Class<?>>();
    private static final Map<String, Class<?>> OID_SYNTAX_CLASS_MAPPING;

    static {
        //Populates the mapping of syntaxes that will support comparison of attribute values.
        //Only accounting for the most common and existing in Gluu Schema
        OID_SYNTAX_CLASS_MAPPING = new HashMap<String, Class<?>>();
        //See RFC4517, section 3.3
        OID_SYNTAX_CLASS_MAPPING.put("1.3.6.1.4.1.1466.115.121.1.7", Boolean.class);
        OID_SYNTAX_CLASS_MAPPING.put("1.3.6.1.4.1.1466.115.121.1.11", String.class);   //Country String
        OID_SYNTAX_CLASS_MAPPING.put("1.3.6.1.4.1.1466.115.121.1.15", String.class);   //Directory String
        OID_SYNTAX_CLASS_MAPPING.put("1.3.6.1.4.1.1466.115.121.1.12", String.class);   //DN
        OID_SYNTAX_CLASS_MAPPING.put("1.3.6.1.4.1.1466.115.121.1.22", String.class);   //Facsimile
        OID_SYNTAX_CLASS_MAPPING.put("1.3.6.1.4.1.1466.115.121.1.24", Date.class);     //Generalized Time
        OID_SYNTAX_CLASS_MAPPING.put("1.3.6.1.4.1.1466.115.121.1.26", String.class);   //IA5 String (used in email)
        OID_SYNTAX_CLASS_MAPPING.put("1.3.6.1.4.1.1466.115.121.1.27", Integer.class);
        OID_SYNTAX_CLASS_MAPPING.put("1.3.6.1.4.1.1466.115.121.1.36", String.class);   //Numeric string
        OID_SYNTAX_CLASS_MAPPING.put("1.3.6.1.4.1.1466.115.121.1.41", String.class);   //Postal address
        OID_SYNTAX_CLASS_MAPPING.put("1.3.6.1.4.1.1466.115.121.1.50", String.class);   //Telephone number
    }

    @SuppressWarnings("unused")
    private LdapOperationsServiceImpl() {
    }

    public LdapOperationsServiceImpl(LdapConnectionProvider connectionProvider) {
        this(connectionProvider, null);
        populateAttributeDataTypesMapping(getSubschemaSubentry());
    }

    public LdapOperationsServiceImpl(LdapConnectionProvider connectionProvider, LdapConnectionProvider bindConnectionProvider) {
        this.connectionProvider = connectionProvider;
        this.bindConnectionProvider = bindConnectionProvider;
        populateAttributeDataTypesMapping(getSubschemaSubentry());
    }

    /*
     * (non-Javadoc)
     *
     * @see org.gluu.site.ldap.PlatformOperationFacade#getConnectionProvider()
     */
    @Override
    public LdapConnectionProvider getConnectionProvider() {
        return connectionProvider;
    }

    /*
     * (non-Javadoc)
     *
     * @see
     * org.gluu.site.ldap.PlatformOperationFacade#setConnectionProvider(org.gluu.
     * site.ldap.LdapConnectionProvider)
     */
    @Override
    public void setConnectionProvider(LdapConnectionProvider connectionProvider) {
        this.connectionProvider = connectionProvider;
    }

    /*
     * (non-Javadoc)
     *
     * @see org.gluu.site.ldap.PlatformOperationFacade#getBindConnectionProvider()
     */
    @Override
    public LdapConnectionProvider getBindConnectionProvider() {
        return bindConnectionProvider;
    }

    /*
     * (non-Javadoc)
     *
     * @see
     * org.gluu.site.ldap.PlatformOperationFacade#setBindConnectionProvider(org.gluu
     * .site.ldap.LdapConnectionProvider)
     */
    @Override
    public void setBindConnectionProvider(LdapConnectionProvider bindConnectionProvider) {
        this.bindConnectionProvider = bindConnectionProvider;
    }

    /*
     * (non-Javadoc)
     *
     * @see org.gluu.site.ldap.PlatformOperationFacade#getConnectionPool()
     */
    @Override
    public LDAPConnectionPool getConnectionPool() {
        return connectionProvider.getConnectionPool();
    }

    /*
     * (non-Javadoc)
     *
     * @see org.gluu.site.ldap.PlatformOperationFacade#getConnection()
     */
    @Override
    public LDAPConnection getConnection() throws LDAPException {
        return connectionProvider.getConnection();
    }

    /*
     * (non-Javadoc)
     *
     * @see
     * org.gluu.site.ldap.PlatformOperationFacade#releaseConnection(com.unboundid.
     * ldap.sdk.LDAPConnection)
     */
    @Override
    public void releaseConnection(LDAPConnection connection) {
        connectionProvider.releaseConnection(connection);
    }

    /*
     * (non-Javadoc)
     *
     * @see
     * org.gluu.site.ldap.PlatformOperationFacade#authenticate(java.lang.String,
     * java.lang.String, java.lang.String)
     */
    @Override
    public boolean authenticate(final String userName, final String password, final String baseDN) throws ConnectionException, SearchException {
        try {
            return authenticateImpl(userName, password, baseDN);
        } catch (LDAPException ex) {
            throw new ConnectionException("Failed to authenticate user", ex);
        }
    }

    /*
     * (non-Javadoc)
     *
     * @see
     * org.gluu.site.ldap.PlatformOperationFacade#authenticate(java.lang.String,
     * java.lang.String)
     */
    @Override
    public boolean authenticate(final String bindDn, final String password) throws ConnectionException {
        try {
            return authenticateImpl(bindDn, password);
        } catch (LDAPException ex) {
            throw new ConnectionException("Failed to authenticate dn", ex);
        }
    }

    private boolean authenticateImpl(final String userName, final String password, final String baseDN)
            throws SearchException, ConnectionException, LDAPException {
        return authenticateImpl(lookupDnByUid(userName, baseDN), password);
    }

    private boolean authenticateImpl(final String bindDn, final String password) throws LDAPException, ConnectionException {
        if (this.bindConnectionProvider == null) {
            return authenticateConnectionPoolImpl(bindDn, password);
        } else {
            return authenticateBindConnectionPoolImpl(bindDn, password);
        }
    }

    private boolean authenticateConnectionPoolImpl(final String bindDn, final String password) throws LDAPException, ConnectionException {
        boolean loggedIn = false;

        if (bindDn == null) {
            return loggedIn;
        }

        boolean closeConnection = false;
        LDAPConnection connection = connectionProvider.getConnection();
        try {
            closeConnection = true;
            BindResult r = connection.bind(bindDn, password);
            if (r.getResultCode() == ResultCode.SUCCESS) {
                loggedIn = true;
            }
        } finally {
            connectionProvider.releaseConnection(connection);
            // We can't use connection which binded as ordinary user
            if (closeConnection) {
                connectionProvider.closeDefunctConnection(connection);
            }
        }

        return loggedIn;
    }

    private boolean authenticateBindConnectionPoolImpl(final String bindDn, final String password) throws LDAPException, ConnectionException {
        if (bindDn == null) {
            return false;
        }

        LDAPConnection connection = bindConnectionProvider.getConnection();
        try {
            BindResult r = connection.bind(bindDn, password);
            return r.getResultCode() == ResultCode.SUCCESS;
        } finally {
            bindConnectionProvider.releaseConnection(connection);
        }
    }

    /**
     * Looks the uid in ldap and return the DN
     */
    protected String lookupDnByUid(String uid, String baseDN) throws SearchException {
        Filter filter = Filter.createEqualityFilter(LdapOperationsServiceImpl.UID, uid);
        SearchResult searchResult = search(baseDN, filter, 1, 1);
        if ((searchResult != null) && searchResult.getEntryCount() > 0) {
            return searchResult.getSearchEntries().get(0).getDN();
        }

        return null;
    }

    /*
     * (non-Javadoc)
     *
     * @see org.gluu.site.ldap.PlatformOperationFacade#search(java.lang.String,
     * com.unboundid.ldap.sdk.Filter, int, int)
     */
    @Override
    public SearchResult search(String dn, Filter filter, int searchLimit, int sizeLimit) throws SearchException {
        return search(dn, filter, searchLimit, sizeLimit, null, (String[]) null);
    }

    /*
     * (non-Javadoc)
     *
     * @see org.gluu.site.ldap.PlatformOperationFacade#search(java.lang.String,
     * com.unboundid.ldap.sdk.Filter, int, int, com.unboundid.ldap.sdk.Control[],
     * java.lang.String)
     */
    @Override
    public SearchResult search(String dn, Filter filter, int searchLimit, int sizeLimit, Control[] controls, String... attributes)
            throws SearchException {
        return search(dn, filter, SearchScope.SUB, searchLimit, sizeLimit, controls, attributes);
    }

    /*
     * (non-Javadoc)
     *
     * @see org.gluu.site.ldap.PlatformOperationFacade#search(java.lang.String,
     * com.unboundid.ldap.sdk.Filter, org.xdi.ldap.model.SearchScope, int, int,
     * com.unboundid.ldap.sdk.Control[], java.lang.String)
     */
    @Override
    public SearchResult search(String dn, Filter filter, SearchScope scope, int searchLimit, int sizeLimit, Control[] controls, String... attributes)
            throws SearchException {
        return search(dn, filter, scope, null, 0, searchLimit, sizeLimit, controls, attributes);
    }

    /*
     * (non-Javadoc)
     *
     * @see org.gluu.site.ldap.PlatformOperationFacade#search(java.lang.String,
     * com.unboundid.ldap.sdk.Filter, org.xdi.ldap.model.SearchScope,
     * org.gluu.site.ldap.persistence.BatchOperation, int, int, int,
     * com.unboundid.ldap.sdk.Control[], java.lang.String)
     */
    @Override
    public <T> SearchResult search(String dn, Filter filter, SearchScope scope, LdapBatchOperationWraper<T> batchOperationWraper, int startIndex,
            int searchLimit, int sizeLimit, Control[] controls, String... attributes) throws SearchException {
        SearchRequest searchRequest;

        BatchOperation<T> ldapBatchOperation = null;
        if (batchOperationWraper != null) {
            ldapBatchOperation = (BatchOperation<T>) batchOperationWraper.getBatchOperation();
        }

        if (LOG.isTraceEnabled()) {
            // Find whole tree search
            if (StringHelper.equalsIgnoreCase(dn, "o=gluu")) {
                LOG.trace("Search in whole LDAP tree", new Exception());
            }
        }

        if (attributes == null) {
            searchRequest = new SearchRequest(dn, scope, filter);
        } else {
            searchRequest = new SearchRequest(dn, scope, filter, attributes);
        }

        boolean useSizeLimit = sizeLimit > 0;

        if (useSizeLimit) {
            // Use paged result to limit search
            searchLimit = sizeLimit;
        }

        SearchResult searchResult = null;
        List<SearchResult> searchResultList = new ArrayList<SearchResult>();
        List<SearchResultEntry> searchResultEntries = new ArrayList<SearchResultEntry>();
        List<SearchResultReference> searchResultReferences = new ArrayList<SearchResultReference>();

        if ((searchLimit > 0) || (startIndex > 0)) {
            if (searchLimit == 0) {
                // Default page size
                searchLimit = 100;
            }

            boolean collectSearchResult;

            LDAPConnection ldapConnection = null;
            try {
                ldapConnection = getConnectionPool().getConnection();
                ASN1OctetString cookie = null;
                if (startIndex > 0) {
                    try {
                        cookie = scrollSimplePagedResultsControl(ldapConnection, dn, filter, scope, controls, startIndex);
                    } catch (InvalidSimplePageControlException ex) {
                        throw new LDAPSearchException(ex.getResultCode(), "Failed to scroll to specified startIndex", ex);
                    } catch (LDAPException ex) {
                        throw new LDAPSearchException(ex.getResultCode(), "Failed to scroll to specified startIndex", ex);
                    }
                }

                do {
                    collectSearchResult = true;
                    searchRequest.setControls(new Control[] {new SimplePagedResultsControl(searchLimit, cookie)});
                    setControls(searchRequest, controls);
                    searchResult = ldapConnection.search(searchRequest);

                    if (ldapBatchOperation != null) {
                        collectSearchResult = ldapBatchOperation.collectSearchResult(searchResult.getEntryCount());
                    }
                    if (collectSearchResult) {
                        searchResultList.add(searchResult);
                        searchResultEntries.addAll(searchResult.getSearchEntries());
                        searchResultReferences.addAll(searchResult.getSearchReferences());
                    }

                    if (ldapBatchOperation != null) {
                        List<T> entries = batchOperationWraper.createEntities(searchResult);
                        ldapBatchOperation.performAction(entries);
                    }
                    cookie = null;
                    try {
                        SimplePagedResultsControl c = SimplePagedResultsControl.get(searchResult);
                        if (c != null) {
                            cookie = c.getCookie();
                        }
                    } catch (LDAPException ex) {
                        LOG.error("Error while accessing cookies" + ex.getMessage());
                    }

                    if (useSizeLimit) {
                        break;
                    }
                } while ((cookie != null) && (cookie.getValueLength() > 0));
            } catch (LDAPException ex) {
                throw new SearchException("Failed to scroll to specified startIndex", ex, ex.getResultCode().intValue());
            } finally {
                if (ldapConnection != null) {
                    getConnectionPool().releaseConnection(ldapConnection);
                }
            }

            if (!collectSearchResult) {
                return new SearchResult(searchResult.getMessageID(), searchResult.getResultCode(), searchResult.getDiagnosticMessage(),
                        searchResult.getMatchedDN(), searchResult.getReferralURLs(), searchResultEntries, searchResultReferences,
                        searchResultEntries.size(), searchResultReferences.size(), searchResult.getResponseControls());
            }

            if (!searchResultList.isEmpty()) {
                SearchResult searchResultTemp = searchResultList.get(0);
                return new SearchResult(searchResultTemp.getMessageID(), searchResultTemp.getResultCode(), searchResultTemp.getDiagnosticMessage(),
                        searchResultTemp.getMatchedDN(), searchResultTemp.getReferralURLs(), searchResultEntries, searchResultReferences,
                        searchResultEntries.size(), searchResultReferences.size(), searchResultTemp.getResponseControls());
            }
        } else {
            setControls(searchRequest, controls);
            try {
                searchResult = getConnectionPool().search(searchRequest);
            } catch (LDAPSearchException ex) {
                throw new SearchException(ex.getMessage(), ex, ex.getResultCode().intValue());
            }
        }

        return searchResult;
    }

    private ASN1OctetString scrollSimplePagedResultsControl(LDAPConnection ldapConnection, String dn, Filter filter, SearchScope scope,
            Control[] controls, int startIndex) throws LDAPException, InvalidSimplePageControlException {
        SearchRequest searchRequest = new SearchRequest(dn, scope, filter, "dn");

        int currentStartIndex = startIndex;
        ASN1OctetString cookie = null;
        do {
            int pageSize = Math.min(currentStartIndex, 100);
            searchRequest.setControls(new Control[] {new SimplePagedResultsControl(pageSize, cookie, true)});
            setControls(searchRequest, controls);
            SearchResult searchResult = ldapConnection.search(searchRequest);

            currentStartIndex -= searchResult.getEntryCount();
            try {
                SimplePagedResultsControl c = SimplePagedResultsControl.get(searchResult);
                if (c != null) {
                    cookie = c.getCookie();
                }
            } catch (LDAPException ex) {
                LOG.error("Error while accessing cookie", ex);
                throw new InvalidSimplePageControlException(ex.getResultCode(), "Error while accessing cookie");
            }
        } while ((cookie != null) && (cookie.getValueLength() > 0) && (currentStartIndex > 0));

        return cookie;
    }

    /*
     * (non-Javadoc)
     *
     * @see org.gluu.site.ldap.PlatformOperationFacade#searchSearchResult(java.lang.
     * String, com.unboundid.ldap.sdk.Filter, org.xdi.ldap.model.SearchScope, int,
     * int, int, java.lang.String, org.xdi.ldap.model.SortOrder,
     * org.xdi.ldap.model.VirtualListViewResponse, java.lang.String)
     */
    @Override
    public SearchResult searchSearchResult(String dn, Filter filter, SearchScope scope, int startIndex, int count, int searchLimit, String sortBy,
            SortOrder sortOrder, ListViewResponse vlvResponse, String... attributes) throws Exception {

        if (StringHelper.equalsIgnoreCase(dn, "o=gluu")) {
            (new Exception()).printStackTrace();
        }

        SearchRequest searchRequest;
        if (attributes == null) {
            searchRequest = new SearchRequest(dn, scope, filter);
        } else {
            searchRequest = new SearchRequest(dn, scope, filter, attributes);
        }

        List<SearchResult> searchResultList = new ArrayList<SearchResult>();
        List<SearchResultEntry> searchResultEntries = new ArrayList<SearchResultEntry>();
        List<SearchResultReference> searchResultReferences = new ArrayList<SearchResultReference>();

        searchRequest.setControls(new SimplePagedResultsControl(searchLimit));
        SearchResult searchResult = getConnectionPool().search(searchRequest);
        List<SearchResultEntry> resultSearchResultEntries = searchResult.getSearchEntries();
        int totalResults = resultSearchResultEntries.size();

        if (StringUtils.isNotEmpty(sortBy)) {
            boolean ascending = sortOrder == null || sortOrder.equals(SortOrder.ASCENDING);
            resultSearchResultEntries = sortListByAttributes(resultSearchResultEntries, SearchResultEntry.class, false, ascending, sortBy);
        }

        List<SearchResultEntry> searchResultEntryList = new ArrayList<SearchResultEntry>();

        if (startIndex <= totalResults) {

            int diff = (totalResults - startIndex);
            if (diff <= count) {
                count = (diff + 1) >= count ? count : (diff + 1);
            }

            int startZeroIndex = startIndex - 1;
            searchResultEntryList = resultSearchResultEntries.subList(startZeroIndex, startZeroIndex + count);
        }

        searchResultList.add(searchResult);
        searchResultEntries.addAll(searchResultEntryList);
        searchResultReferences.addAll(searchResult.getSearchReferences());

        SearchResult searchResultTemp = searchResultList.get(0);
        searchResult = new SearchResult(searchResultTemp.getMessageID(), searchResultTemp.getResultCode(), searchResultTemp.getDiagnosticMessage(),
                searchResultTemp.getMatchedDN(), searchResultTemp.getReferralURLs(), searchResultEntries, searchResultReferences,
                searchResultEntries.size(), searchResultReferences.size(), searchResultTemp.getResponseControls());

        // Get results info
        vlvResponse.setItemsPerPage(count);
        vlvResponse.setTotalResults(totalResults);
        vlvResponse.setStartIndex(startIndex);

        return searchResult;
    }

    /*
     * (non-Javadoc)
     *
     * @see
     * org.gluu.site.ldap.PlatformOperationFacade#searchVirtualListView(java.lang.
     * String, com.unboundid.ldap.sdk.Filter, org.xdi.ldap.model.SearchScope, int,
     * int, java.lang.String, org.xdi.ldap.model.SortOrder,
     * org.xdi.ldap.model.VirtualListViewResponse, java.lang.String)
     */
    @Deprecated
    public SearchResult searchVirtualListView(String dn, Filter filter, SearchScope scope, int startIndex, int count, String sortBy,
            SortOrder sortOrder, ListViewResponse vlvResponse, String... attributes) throws Exception {

        if (StringHelper.equalsIgnoreCase(dn, "o=gluu")) {
            (new Exception()).printStackTrace();
        }

        SearchRequest searchRequest;

        if (attributes == null) {
            searchRequest = new SearchRequest(dn, scope, filter);
        } else {
            searchRequest = new SearchRequest(dn, scope, filter, attributes);
        }

        // startIndex and count should be "cleansed" before arriving here
        int targetOffset = startIndex;
        int beforeCount = 0;
        int afterCount = (count > 0) ? (count - 1) : 0;
        int contentCount = 0;

        boolean reverseOrder = false;
        if (sortOrder != null) {
            reverseOrder = sortOrder.equals(SortOrder.DESCENDING) ? true : false;
        }

        // Note that the VLV control always requires the server-side sort control.
        searchRequest.setControls(new ServerSideSortRequestControl(new SortKey(sortBy, reverseOrder)),
                new VirtualListViewRequestControl(targetOffset, beforeCount, afterCount, contentCount, null));

        SearchResult searchResult = getConnectionPool().search(searchRequest);

        /*
         * for (SearchResultEntry searchResultEntry : searchResult.getSearchEntries()) {
         * log.info("##### searchResultEntry = " + searchResultEntry.toString()); }
         */

        // LDAPTestUtils.assertHasControl(searchResult,
        // VirtualListViewResponseControl.VIRTUAL_LIST_VIEW_RESPONSE_OID);

        VirtualListViewResponseControl vlvResponseControl = VirtualListViewResponseControl.get(searchResult);

        // Get results info
        vlvResponse.setItemsPerPage(searchResult.getEntryCount());
        vlvResponse.setTotalResults(vlvResponseControl.getContentCount());
        vlvResponse.setStartIndex(vlvResponseControl.getTargetPosition());

        return searchResult;
    }

    private void setControls(SearchRequest searchRequest, Control... controls) {
        if (!ArrayHelper.isEmpty(controls)) {
            Control[] newControls;
            if (ArrayHelper.isEmpty(searchRequest.getControls())) {
                newControls = controls;
            } else {
                newControls = ArrayHelper.arrayMerge(searchRequest.getControls(), controls);
            }

            searchRequest.setControls(newControls);
        }
    }

    /*
     * (non-Javadoc)
     *
     * @see org.gluu.site.ldap.PlatformOperationFacade#lookup(java.lang.String)
     */
    @Override
    public SearchResultEntry lookup(String dn) throws ConnectionException {
        return lookup(dn, (String[]) null);
    }

    /*
     * (non-Javadoc)
     *
     * @see org.gluu.site.ldap.PlatformOperationFacade#lookup(java.lang.String,
     * java.lang.String)
     */
    @Override
    public SearchResultEntry lookup(String dn, String... attributes) throws ConnectionException {
        if (StringHelper.equalsIgnoreCase(dn, "o=gluu")) {
            (new Exception()).printStackTrace();
        }

        try {
            if (attributes == null) {
                return getConnectionPool().getEntry(dn);
            } else {
                return getConnectionPool().getEntry(dn, attributes);
            }
        } catch (Exception ex) {
            throw new ConnectionException("Failed to lookup entry", ex);
        }
    }

    /*
     * (non-Javadoc)
     *
     * @see org.gluu.site.ldap.PlatformOperationFacade#addEntry(java.lang.String,
     * java.util.Collection)
     */
    @Override
    public boolean addEntry(String dn, Collection<Attribute> atts) throws DuplicateEntryException, ConnectionException {
        try {
            LDAPResult result = getConnectionPool().add(dn, atts);
            if (result.getResultCode().getName().equalsIgnoreCase(LdapOperationsServiceImpl.SUCCESS)) {
                return true;
            }
        } catch (final LDAPException ex) {
            int errorCode = ex.getResultCode().intValue();
            if (errorCode == ResultCode.ENTRY_ALREADY_EXISTS_INT_VALUE) {
                throw new DuplicateEntryException();
            }
            if (errorCode == ResultCode.INSUFFICIENT_ACCESS_RIGHTS_INT_VALUE) {
                throw new ConnectionException("LDAP config error: insufficient access rights.", ex);
            }
            if (errorCode == ResultCode.TIME_LIMIT_EXCEEDED_INT_VALUE) {
                throw new ConnectionException("LDAP Error: time limit exceeded", ex);
            }
            if (errorCode == ResultCode.OBJECT_CLASS_VIOLATION_INT_VALUE) {
                throw new ConnectionException("LDAP config error: schema violation contact LDAP admin.", ex);
            }

            throw new ConnectionException("Error adding entry to directory. LDAP error number " + errorCode, ex);
        }

        return false;
    }

    /*
     * (non-Javadoc)
     *
     * @see org.gluu.site.ldap.PlatformOperationFacade#updateEntry(java.lang.String,
     * java.util.Collection)
     */
    @Override
    public boolean updateEntry(String dn, Collection<Attribute> attrs) throws DuplicateEntryException, ConnectionException {
        List<Modification> mods = new ArrayList<Modification>();

        for (Attribute attribute : attrs) {
            if (attribute.getName().equalsIgnoreCase(LdapOperationsServiceImpl.OBJECT_CLASS)
                    || attribute.getName().equalsIgnoreCase(LdapOperationsServiceImpl.DN)
                    || attribute.getName().equalsIgnoreCase(LdapOperationsServiceImpl.USER_PASSWORD)) {
                continue;
            } else {
                if (attribute.getName() != null && attribute.getValue() != null) {
                    mods.add(new Modification(ModificationType.REPLACE, attribute.getName(), attribute.getValue()));
                }
            }
        }

        return updateEntry(dn, mods);
    }

    /*
     * (non-Javadoc)
     *
     * @see org.gluu.site.ldap.PlatformOperationFacade#updateEntry(java.lang.String,
     * java.util.List)
     */
    @Override
    public boolean updateEntry(String dn, List<Modification> modifications) throws DuplicateEntryException, ConnectionException {
        ModifyRequest modifyRequest = new ModifyRequest(dn, modifications);
        return modifyEntry(modifyRequest);
    }

    /**
     * Use this method to add / replace / delete attribute from entry
     *
     * @param modifyRequest
     * @return true if modification is successful
     * @throws DuplicateEntryException
     * @throws ConnectionException
     */
    protected boolean modifyEntry(ModifyRequest modifyRequest) throws DuplicateEntryException, ConnectionException {
        LDAPResult modifyResult = null;
        try {
            modifyResult = getConnectionPool().modify(modifyRequest);
            return ResultCode.SUCCESS.equals(modifyResult.getResultCode());
        } catch (final LDAPException ex) {
            int errorCode = ex.getResultCode().intValue();
            if (errorCode == ResultCode.INSUFFICIENT_ACCESS_RIGHTS_INT_VALUE) {
                throw new ConnectionException("LDAP config error: insufficient access rights.", ex);
            }
            if (errorCode == ResultCode.TIME_LIMIT_EXCEEDED_INT_VALUE) {
                throw new ConnectionException("LDAP Error: time limit exceeded", ex);
            }
            if (errorCode == ResultCode.OBJECT_CLASS_VIOLATION_INT_VALUE) {
                throw new ConnectionException("LDAP config error: schema violation contact LDAP admin.", ex);
            }

            throw new ConnectionException("Error updating entry in directory. LDAP error number " + errorCode, ex);
        }
    }

    /*
     * (non-Javadoc)
     *
     * @see org.gluu.site.ldap.PlatformOperationFacade#delete(java.lang.String)
     */
    @Override
    public void delete(String dn) throws ConnectionException {
        try {
            getConnectionPool().delete(dn);
        } catch (Exception ex) {
            throw new ConnectionException("Failed to delete entry", ex);
        }
    }

    /*
     * (non-Javadoc)
     *
     * @see org.gluu.site.ldap.PlatformOperationFacade#deleteWithSubtree(java.lang.
     * String)
     */
    @Override
    public void deleteWithSubtree(String dn) throws ConnectionException {
        try {
            final DeleteRequest deleteRequest = new DeleteRequest(dn);
            deleteRequest.addControl(new SubtreeDeleteRequestControl());
            getConnectionPool().delete(deleteRequest);
        } catch (Exception ex) {
            throw new ConnectionException("Failed to delete entry", ex);
        }
    }

    /*
     * (non-Javadoc)
     *
     * @see
     * org.gluu.site.ldap.PlatformOperationFacade#processChange(com.unboundid.ldif.
     * LDIFChangeRecord)
     */
    @Override
    public boolean processChange(LDIFChangeRecord ldifRecord) throws LDAPException {
        LDAPConnection connection = getConnection();
        try {
            LDAPResult ldapResult = ldifRecord.processChange(connection);

            return ResultCode.SUCCESS.equals(ldapResult.getResultCode());
        } finally {
            releaseConnection(connection);
        }
    }

    /*
     * (non-Javadoc)
     *
     * @see org.gluu.site.ldap.PlatformOperationFacade#getSupportedLDAPVersion()
     */
    @Override
    public int getSupportedLDAPVersion() {
        return this.connectionProvider.getSupportedLDAPVersion();
    }

    /*
     * (non-Javadoc)
     *
     * @see org.gluu.site.ldap.PlatformOperationFacade#getSubschemaSubentry()
     */
    @Override
    public String getSubschemaSubentry() {
        return this.connectionProvider.getSubschemaSubentry();
    }

    /*
     * (non-Javadoc)
     *
     * @see org.gluu.site.ldap.PlatformOperationFacade#destroy()
     */
    @Override
    public boolean destroy() {
        boolean result = true;

        if (connectionProvider != null) {
            try {
                connectionProvider.closeConnectionPool();
            } catch (Exception ex) {
                LOG.error("Failed to close connection pool correctly");
                result = false;
            }
        }

        if (bindConnectionProvider != null) {
            try {
                bindConnectionProvider.closeConnectionPool();
            } catch (Exception ex) {
                LOG.error("Failed to close bind connection pool correctly");
                result = false;
            }
        }

        return result;
    }

    /*
     * (non-Javadoc)
     *
     * @see org.gluu.site.ldap.PlatformOperationFacade#isBinaryAttribute(java.lang.
     * String)
     */
    @Override
    public boolean isBinaryAttribute(String attributeName) {
        return this.connectionProvider.isBinaryAttribute(attributeName);
    }

    /*
     * (non-Javadoc)
     *
     * @see
     * org.gluu.site.ldap.PlatformOperationFacade#isCertificateAttribute(java.lang.
     * String)
     */
    @Override
    public boolean isCertificateAttribute(String attributeName) {
        return this.connectionProvider.isCertificateAttribute(attributeName);
    }

    /*
     * (non-Javadoc)
     *
     * @see
     * org.gluu.site.ldap.PlatformOperationFacade#getCertificateAttributeName(java.
     * lang.String)
     */
    @Override
    public String getCertificateAttributeName(String attributeName) {
        return this.connectionProvider.getCertificateAttributeName(attributeName);
    }

    @Override
    public <T> List<T> sortListByAttributes(List<T> searchResultEntries, Class<T> cls, boolean caseSensitive,
                                            boolean ascending, String... sortByAttributes) {
        // Check input parameters
        if (searchResultEntries == null) {
            throw new MappingException("Entries list to sort is null");
        }

        if (searchResultEntries.size() == 0) {
            return searchResultEntries;
        }

        SearchResultEntryComparator<T> comparator = new SearchResultEntryComparator<T>(sortByAttributes, caseSensitive, ascending);

        //The following line does not work because of type erasure
        //T array[]=(T[])searchResultEntries.toArray();

        //Converting the list to an array gets rid of unmodifiable list problem, see issue #68
        T[] dummyArr = (T[]) java.lang.reflect.Array.newInstance(cls, 0);
        T[] array = searchResultEntries.toArray(dummyArr);
        Arrays.sort(array, comparator);
        return Arrays.asList(array);

    }

    private void populateAttributeDataTypesMapping(String schemaEntryDn) {

        try {
            if (ATTRIBUTE_DATA_TYPES.size() == 0) {
                //schemaEntryDn="ou=schema";
                SearchResultEntry entry = lookup(schemaEntryDn, "attributeTypes");
                Attribute attrAttributeTypes = entry.getAttribute("attributeTypes");

                Map<String, Pair<String, String>> tmpMap = new HashMap<String, Pair<String, String>>();

                for (String strAttributeType : attrAttributeTypes.getValues()) {
                    AttributeTypeDefinition attrTypeDef = new AttributeTypeDefinition(strAttributeType);
                    String[] names = attrTypeDef.getNames();

                    if (names != null) {
                        for (String name : names) {
                            tmpMap.put(name, new Pair<String, String>(attrTypeDef.getBaseSyntaxOID(), attrTypeDef.getSuperiorType()));
                        }
                    }
                }

                //Fill missing values
                for (String name : tmpMap.keySet()) {
                    Pair<String, String> currPair = tmpMap.get(name);
                    String sup = currPair.getSecond();

                    if (currPair.getFirst() == null && sup != null) {     //No OID syntax?
                        //Try to lookup superior type
                        Pair<String, String> pair = tmpMap.get(sup);
                        if (pair != null) {
                            currPair.setFirst(pair.getFirst());
                        }
                    }
                }

                //Populate map of attribute names vs. Java classes
                for (String name : tmpMap.keySet()) {
                    String syntaxOID = tmpMap.get(name).getFirst();

                    if (syntaxOID != null) {
                        Class<?> cls = OID_SYNTAX_CLASS_MAPPING.get(syntaxOID);
                        if (cls != null) {
                            ATTRIBUTE_DATA_TYPES.put(name, cls);
                        }
                    }
                }
            }
        } catch (Exception e) {
            LOG.error(e.getMessage(), e);
        }

    }


    private static final class SearchResultEntryComparator<T> implements Comparator<T>, Serializable {

        private static final long serialVersionUID = 574848841116711467L;
        private String[] sortByAttributes;
        private boolean caseSensitive;
        private boolean ascending;

        private SearchResultEntryComparator(String[] sortByAttributes, boolean caseSensitive, boolean ascending) {
            this.sortByAttributes = sortByAttributes;
            this.caseSensitive = caseSensitive;
            this.ascending = ascending;
        }

        public int compare(T entry1, T entry2) {

            int result = 0;

            if (entry1 == null) {
                if (entry2 == null) {
                    result = 0;
                } else {
                    result = -1;
                }
            } else {
                if (entry2 == null) {
                    result = 1;
                } else {
                    for (String currSortByAttribute : sortByAttributes) {
                        result = compare(entry1, entry2, currSortByAttribute);
                        if (result != 0) {
                            break;
                        }
                    }
                }
            }

            if (!ascending) {
                result *= -1;
            }

            return result;

        }

        //This comparison assumes a default sort order of "ascending"
        public int compare(T entry1, T entry2, String attributeName) {

            int result = 0;
            try {

                if (entry1 instanceof SearchResultEntry) {

                    SearchResultEntry resultEntry1 = (SearchResultEntry) entry1;
                    SearchResultEntry resultEntry2 = (SearchResultEntry) entry2;

                    //Obtain a string representation first and do nulls treatments
                    String value1 = resultEntry1.getAttributeValue(attributeName);
                    String value2 = resultEntry2.getAttributeValue(attributeName);

                    if (value1 == null) {
                        if (value2 == null) {
                            result = 0;
                        } else {
                            result = -1;
                        }
                    } else {
                        if (value2 == null) {
                            result = 1;
                        } else {
                            Class<?> cls = ATTRIBUTE_DATA_TYPES.get(attributeName);

                            if (cls != null) {
                                if (cls.equals(String.class)) {
                                    if (caseSensitive) {
                                        result = value1.compareTo(value2);
                                    } else {
                                        result = value1.toLowerCase().compareTo(value2.toLowerCase());
                                    }
                                } else
                                if (cls.equals(Integer.class)) {
                                    result = resultEntry1.getAttributeValueAsInteger(attributeName)
                                            .compareTo(resultEntry2.getAttributeValueAsInteger(attributeName));
                                } else
                                if (cls.equals(Boolean.class)) {
                                    result = resultEntry1.getAttributeValueAsBoolean(attributeName)
                                            .compareTo(resultEntry2.getAttributeValueAsBoolean(attributeName));
                                } else
                                if (cls.equals(Date.class)) {
                                    result = resultEntry1.getAttributeValueAsDate(attributeName)
                                            .compareTo(resultEntry2.getAttributeValueAsDate(attributeName));
                                }
                            }
                        }
                    }
                }
            } catch (Exception e) {
                LOG.error("Error occurred when comparing entries with SearchResultEntryComparator");
                LOG.error(e.getMessage(), e);
            }
            return result;

        }

    }

}

