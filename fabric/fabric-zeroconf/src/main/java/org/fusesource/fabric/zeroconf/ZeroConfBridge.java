/*
 * Copyright (C) 2011, FuseSource Corp.  All rights reserved.
 * http://fusesource.com
 *
 * The software in this package is published under the terms of the
 * CDDL license a copy of which has been included with this distribution
 * in the license.txt file.
 */
package org.fusesource.fabric.zeroconf;

import org.apache.activemq.jmdns.JmDNS;
import org.apache.activemq.jmdns.ServiceEvent;
import org.apache.activemq.jmdns.ServiceInfo;
import org.apache.activemq.jmdns.ServiceListener;
import org.apache.zookeeper.CreateMode;
import org.apache.zookeeper.ZooDefs;
import org.fusesource.fabric.api.FabricService;
import org.linkedin.zookeeper.client.IZKClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.StringWriter;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 */
public class ZeroConfBridge {
    private static final transient Logger LOG = LoggerFactory.getLogger(ZeroConfBridge.class);

    private final CopyOnWriteArrayList<ServiceInfo> serviceInfos = new CopyOnWriteArrayList<ServiceInfo>();
    private JmDNS jmdns;
    private InetAddress localAddress;
    private String localhost;
    private int weight;
    private int priority;
    private IZKClient zkClient;
    private FabricService fabricService;
    private boolean doList = true;

    private String[] types = {
            "_graphite._tcp.local.",
            "_ganglia._tcp.local.",
    };

    public void start() throws Exception {
        // force lazy construction
        getJmdns();

        ServiceListener listener = new ServiceListener() {
            public void serviceAdded(ServiceEvent event) {
                log("Added", event);
                JmDNS dns = event.getDNS();
                String type = event.getType();
                String name = event.getName();

                dns.requestServiceInfo(type, name);
                ServiceInfo info = dns.getServiceInfo(type, name);
                if (info != null) {
                    addToZooKeeper(info);
                }
            }

            public void serviceRemoved(ServiceEvent event) {
                log("Removed", event);
                removeFromZooKeper(event);
            }

            public void serviceResolved(ServiceEvent event) {
                log("Resolved", event);
                ServiceInfo info = event.getInfo();
                if (info != null) {
                    addToZooKeeper(info);
                }
            }
        };

        for (String type : getTypes()) {
            LOG.info("Listening for ZeroConf services of type: " + type);

            jmdns.addServiceListener(type, listener);
        }

        if (doList) {
            // lets check to see if we've got an entry...
            for (String type : getTypes()) {
                ServiceInfo[] list = jmdns.list(type);
                if (list != null) {
                    for (ServiceInfo info : list) {
                        addToZooKeeper(info);
                    }
                }
            }
        }
    }


    public void stop() {
        if (jmdns != null) {
            for (Iterator<ServiceInfo> iter = serviceInfos.iterator(); iter.hasNext(); ) {
                ServiceInfo si = iter.next();
                jmdns.unregisterService(si);
            }

            // Close it down async since this could block for a while.
            final JmDNS closeTarget = jmdns;
            Thread thread = new Thread() {
                public void run() {
                    closeTarget.close();
                }
            };

            thread.setDaemon(true);
            thread.start();

            jmdns = null;
        }
    }

    public void registerService(String name, int port, String type) throws IOException {
        ServiceInfo si = createServiceInfo(name, new HashMap(), port, type);
        registerService(si);
    }


    public void registerService(ServiceInfo si) throws IOException {
        serviceInfos.add(si);
        getJmdns().registerService(si);
    }

    // Properties
    //-------------------------------------------------------------------------
    public int getPriority() {
        return priority;
    }

    public void setPriority(int priority) {
        this.priority = priority;
    }

    public int getWeight() {
        return weight;
    }

    public void setWeight(int weight) {
        this.weight = weight;
    }

    public JmDNS getJmdns() throws IOException {
        if (jmdns == null) {
            jmdns = createJmDNS();
        }
        return jmdns;
    }

    public void setJmdns(JmDNS jmdns) {
        this.jmdns = jmdns;
    }

    public InetAddress getLocalAddress() throws UnknownHostException {
        if (localAddress == null) {
            localAddress = createLocalAddress();
        }
        return localAddress;
    }

    public void setLocalAddress(InetAddress localAddress) {
        this.localAddress = localAddress;
    }

    public String getLocalhost() {
        return localhost;
    }

    public void setLocalhost(String localhost) {
        this.localhost = localhost;
    }

    public String[] getTypes() {
        return types;
    }

    public void setTypes(String[] types) {
        this.types = types;
    }

    public FabricService getFabricService() {
        return fabricService;
    }

    public void setFabricService(FabricService fabricService) {
        this.fabricService = fabricService;
    }

    public IZKClient getZkClient() {
        return zkClient;
    }

    public void setZkClient(IZKClient zkClient) {
        this.zkClient = zkClient;
    }

    // Implementation methods
    // -------------------------------------------------------------------------
    private void addToZooKeeper(ServiceInfo info) {
        String type = info.getType();
        String server = info.getServer();
        int port = info.getPort();
        String name = info.getName();

        LOG.debug("Found: " + type + " " + name + " => " + server + ":" + port);
        addToZooKeeper(type, name, server, port);
    }

    protected void addToZooKeeper(String type, String name, String server, int port) {
        String key = "/fabric/registry/clusters/stats";

        // lets make a properties entry...
        Properties properties = new Properties();
        properties.setProperty("name", name);
        properties.setProperty("zeroConfType", type);
        properties.setProperty("host", server);
        properties.setProperty("port", "" + port);

        IZKClient zk = getZkClient();
        if (zk == null) {
            LOG.warn("No ZooKeeper client set so cannot write entry " + key + " with properties: " + properties);
        } else {
            try {
                StringWriter buffer = new StringWriter();
                properties.store(buffer, "Generated from ZeroConf");
                String data = buffer.toString();
                zk.createOrSetWithParents(key, data, ZooDefs.Ids.OPEN_ACL_UNSAFE, CreateMode.EPHEMERAL);
            } catch (Exception e) {
                LOG.warn("Failed to write entry " + key + "to ZooKeeper: " + e, e);
            }
        }
    }

    protected void removeFromZooKeper(ServiceEvent event) {
        // TODO

    }

    protected void log(String message, ServiceEvent event) {
        if (LOG.isDebugEnabled()) {
            LOG.debug(message + ": " + event.getType() + " : " + event.getName());
        }
    }

    protected ServiceInfo createServiceInfo(String name, Map map, int port, String type) {
        if (LOG.isDebugEnabled()) {
            LOG.debug("Registering service type: " + type + " name: " + name + " details: " + map);
        }
        return new ServiceInfo(type, name + "." + type, port, weight, priority, "");
    }

    protected JmDNS createJmDNS() throws IOException {
        return JmDNSFactory.create(getLocalAddress());
    }

    protected InetAddress createLocalAddress() throws UnknownHostException {
        if (localhost != null) {
            return InetAddress.getByName(localhost);
        }
        return InetAddress.getLocalHost();
    }
}

