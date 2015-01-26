/**
 * Licensed to DigitalPebble Ltd under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * DigitalPebble licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.digitalpebble.storm.crawler.bolt;

import java.net.InetAddress;
import java.net.MalformedURLException;
import java.net.URL;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import backtype.storm.metric.api.MultiCountMetric;
import backtype.storm.task.OutputCollector;
import backtype.storm.task.TopologyContext;
import backtype.storm.topology.OutputFieldsDeclarer;
import backtype.storm.topology.base.BaseRichBolt;
import backtype.storm.tuple.Fields;
import backtype.storm.tuple.Tuple;
import backtype.storm.tuple.Values;

import com.digitalpebble.storm.crawler.Constants;
import com.digitalpebble.storm.crawler.util.ConfUtils;

import crawlercommons.url.PaidLevelDomain;

/**
 * Generates a partition key for a given URL based on the hostname, domain or IP
 * address.
 */
public class URLPartitionerBolt extends BaseRichBolt {

    private static final Logger LOG = LoggerFactory
            .getLogger(URLPartitionerBolt.class);

    private OutputCollector _collector;

    private MultiCountMetric eventCounter;

    private Map<String, String> cache;

    private String mode = Constants.PARTITION_MODE_HOST;

    @Override
    public void execute(Tuple tuple) {
        String url = tuple.getStringByField("url");
        HashMap<String, String[]> metadata = null;

        if (tuple.contains("metadata"))
            metadata = (HashMap<String, String[]>) tuple
                    .getValueByField("metadata");

        String partitionKey = null;
        String host = "";

        // IP in metadata?
        if (mode.equalsIgnoreCase(Constants.PARTITION_MODE_IP)
                && metadata != null) {
            String[] ips_provided = metadata.get("ip");
            if (ips_provided != null && ips_provided.length > 0) {
                partitionKey = ips_provided[0];
                eventCounter.scope("provided").incrBy(1);
            }
        }

        if (partitionKey == null) {
            URL u;
            try {
                u = new URL(url);
                host = u.getHost();
            } catch (MalformedURLException e1) {
                eventCounter.scope("Invalid URL").incrBy(1);
                LOG.warn("Invalid URL: {}", url);
                // ack it so that it doesn't get replayed
                _collector.ack(tuple);
                return;
            }
        }

        // partition by hostname
        if (mode.equalsIgnoreCase(Constants.PARTITION_MODE_HOST))
            partitionKey = host;

        // partition by domain : needs fixing
        else if (mode.equalsIgnoreCase(Constants.PARTITION_MODE_DOMAIN)) {
            partitionKey = PaidLevelDomain.getPLD(url);
        }

        // partition by IP
        if (mode.equalsIgnoreCase(Constants.PARTITION_MODE_IP)
                && partitionKey == null) {
            // try to get it from cache first
            partitionKey = cache.get(host);
            if (partitionKey != null) {
                eventCounter.scope("from cache").incrBy(1);
            } else {
                try {
                    long start = System.currentTimeMillis();
                    final InetAddress addr = InetAddress.getByName(host);
                    partitionKey = addr.getHostAddress();
                    long end = System.currentTimeMillis();
                    LOG.debug("Resolved IP {} in {} msec for : {}",
                            partitionKey, (end - start), url);

                    // add to cache
                    cache.put(host, partitionKey);

                } catch (final Exception e) {
                    eventCounter.scope("Unable to resolve IP").incrBy(1);
                    LOG.warn("Unable to resolve IP for: {}", host);
                    _collector.ack(tuple);
                    return;
                }
            }
        }

        LOG.debug("Partition Key for: {} > {}", url, partitionKey);

        _collector.emit(tuple, new Values(url, partitionKey, metadata));
        _collector.ack(tuple);
    }

    @Override
    public void declareOutputFields(OutputFieldsDeclarer declarer) {
        declarer.declare(new Fields("url", "key", "metadata"));
    }

    @Override
    public void prepare(Map stormConf, TopologyContext context,
            OutputCollector collector) {

        mode = ConfUtils.getString(stormConf,
                Constants.PARTITION_MODEParamName,
                Constants.PARTITION_MODE_HOST);

        // check that the mode is known
        if (!mode.equals(Constants.PARTITION_MODE_IP)
                && !mode.equals(Constants.PARTITION_MODE_DOMAIN)
                && !mode.equals(Constants.PARTITION_MODE_HOST)) {
            LOG.error("Unknown partition mode : {} - forcing to byHost", mode);
            mode = Constants.PARTITION_MODE_HOST;
        }

        LOG.info("Using partition mode : {}", mode);

        _collector = collector;
        // Register a "MultiCountMetric" to count different events in this bolt
        // Storm will emit the counts every n seconds to a special bolt via a
        // system stream
        // The data can be accessed by registering a "MetricConsumer" in the
        // topology
        this.eventCounter = context.registerMetric("URLPartitioner",
                new MultiCountMetric(), 10);

        final int MAX_ENTRIES = 500;
        cache = new LinkedHashMap(MAX_ENTRIES + 1, .75F, true) {
            // This method is called just after a new entry has been added
            @Override
            public boolean removeEldestEntry(Map.Entry eldest) {
                return size() > MAX_ENTRIES;
            }
        };

        // If the cache is to be used by multiple threads,
        // the cache must be wrapped with code to synchronize the methods
        cache = Collections.synchronizedMap(cache);
    }

}