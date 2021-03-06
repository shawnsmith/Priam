package com.netflix.priam;

import com.amazonaws.regions.Region;
import com.amazonaws.regions.RegionUtils;
import com.amazonaws.services.simpledb.AmazonSimpleDBClient;
import com.amazonaws.services.simpledb.model.Attribute;
import com.amazonaws.services.simpledb.model.Item;
import com.amazonaws.services.simpledb.model.SelectRequest;
import com.amazonaws.services.simpledb.model.SelectResult;
import com.google.common.base.Preconditions;
import com.google.common.collect.Maps;
import org.apache.commons.lang.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.inject.Inject;
import java.util.Map;

/**
 * Loads config data from SimpleDB.  {@link #initialize(String, String)} will query the SimpleDB domain "PriamProperties"
 * for any potential configurations.  The domain is set up to support multiple different clusters; this is done by using
 * amazon's auto scaling groups (ASG).
 * <p/>
 * Schema <ul>
 *   <li>"appId" // ASG up to first instance of '-'.  So ASG name priam-test will create appId priam, ASG priam_test
 *   will create appId priam_test.</li>
 *   <li>"property" // key to use for configs.</li>
 *   <li>"value" // value to set for the given property/key.</li>
 *   <li>"region" // region the config belongs to.  If left empty, then applies to all regions.</li>
 * </ul>
 */
public final class SimpleDBConfigSource extends AbstractConfigSource 
{
    private static final Logger logger = LoggerFactory.getLogger(SimpleDBConfigSource.class.getName());

    private static final String DOMAIN = "PriamProperties";
    private static final String ALL_QUERY = "select * from " + DOMAIN + " where " + Attributes.APP_ID + "='%s'";

    private final Map<String, String> data = Maps.newConcurrentMap();
    private final ICredential provider;
    private final Region sdbRegion;

    @Inject
    public SimpleDBConfigSource(ICredential provider, IConfiguration config)
    {
        this.provider = provider;
        this.sdbRegion = RegionUtils.getRegion(config.getSimpleDBRegion());  // defaults to "us-east-1"
    }

    @Override
    public void initialize(final String asgName, final String region)
    {
        super.initialize(asgName, region);

        AmazonSimpleDBClient simpleDBClient = new AmazonSimpleDBClient(provider.getAwsCredentialProvider());
        simpleDBClient.setRegion(this.sdbRegion);

        String nextToken = null;
        String appid = asgName.lastIndexOf('-') > 0 ? asgName.substring(0, asgName.indexOf('-')) : asgName;
        logger.info("appid used to fetch properties is: {}", appid);
        do 
        {
            SelectRequest request = new SelectRequest(String.format(ALL_QUERY, appid));
            request.setNextToken(nextToken);
            SelectResult result = simpleDBClient.select(request);
            nextToken = result.getNextToken();
            for (Item item : result.getItems())
            {
                addProperty(item);
            }

        } 
        while (nextToken != null);
    }

    private static class Attributes 
    {
        public final static String APP_ID = "appId"; // ASG
        public final static String PROPERTY = "property";
        public final static String PROPERTY_VALUE = "value";
        public final static String REGION = "region";
    }

    private void addProperty(Item item) 
    {
        String prop = "";
        String value = "";
        String dc = "";
        for (Attribute att : item.getAttributes())
        {
            if (att.getName().equals(Attributes.PROPERTY))
                prop = att.getValue();
            else if (att.getName().equals(Attributes.PROPERTY_VALUE))
                value = att.getValue();
            else if (att.getName().equals(Attributes.REGION))
                dc = att.getValue();
        }
        // Ignore, if not this region
        if (StringUtils.isNotBlank(dc) && !dc.equals(getRegion()))
            return;
        // Override only if region is specified
        if (data.containsKey(prop) && StringUtils.isBlank(dc))
            return;
        data.put(prop, value);
    }

    @Override
    public int size() 
    {
        return data.size();
    }

    @Override
    public String get(final String key) 
    {
        return data.get(key);
    }

    @Override
    public void set(final String key, final String value) 
    {
        Preconditions.checkNotNull(value, "Value can not be null for configurations.");
        data.put(key, value);
    }
}
