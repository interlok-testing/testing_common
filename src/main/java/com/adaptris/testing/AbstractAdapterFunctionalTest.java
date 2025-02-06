package com.adaptris.testing;

import com.adaptris.core.StartedState;
import com.adaptris.core.runtime.AdapterManagerMBean;
import com.adaptris.core.runtime.AdapterRegistryMBean;
import com.adaptris.interlok.boot.InterlokLauncher;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.TestInstance;

import javax.management.JMX;
import javax.management.ObjectInstance;
import javax.management.ObjectName;
import javax.management.remote.JMXConnector;
import javax.management.remote.JMXConnectorFactory;
import javax.management.remote.JMXServiceURL;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.util.Properties;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
public abstract class AbstractAdapterFunctionalTest {

    protected CloseableHttpClient client = HttpClients.createDefault();
    protected Integer serverPort;
    protected JMXServiceURL jmxServiceUrl;
    protected JMXConnector jmxConnector;
    protected AdapterManagerMBean adapterManagerMBean;

    protected int adapterStartWaitTime = 1000;
    protected int adapterStartMaxWaitTime = 10000;
    protected int adapterCloseMaxWaitTime = 10000;

    protected String getBaseAdapterUrl() {
        return String.format("http://localhost:%d", serverPort);
    }

    @BeforeAll
    public void setup() throws Exception {
        try (ServerSocket serverSocket = new ServerSocket(0)) {
            serverPort = serverSocket.getLocalPort();
            File bootstrapFile = new File("./config/bootstrap.properties");
            assert bootstrapFile.exists() : "Bootstrap file does not exist: " + bootstrapFile.getAbsolutePath();
            Properties bootstrapProperties = new Properties();
            try (InputStream is = new FileInputStream(bootstrapFile)) {
                bootstrapProperties.load(is);
                String jmxServiceUrlStr = bootstrapProperties.getProperty("jmxserviceurl", null);
                assert jmxServiceUrlStr != null : "Bootstrap file must have jmxserviceurl property";
                jmxServiceUrl = new JMXServiceURL(jmxServiceUrlStr);
            }

            File variablesFile = new File("./config/variables.properties");
            Properties variablesProperties = new Properties();
            try (InputStream is = new FileInputStream(variablesFile)) {
                variablesProperties.load(is);
            }
            variablesProperties.put("sysprop.jetty.http.port", String.valueOf(serverPort));
            try (OutputStream os = new FileOutputStream(variablesFile)) {
                variablesProperties.store(os, null);
            }

        }
        launchAdapter();
        connectJMX();
        waitForAdapterStarted();
    }

    protected void launchAdapter() throws Exception{
        InterlokLauncher.main(new String[] {"./config/bootstrap.properties"});
    }

    protected void connectJMX() throws Exception {
        if (jmxServiceUrl == null) throw new RuntimeException("JMX service URL not set");
        jmxConnector = JMXConnectorFactory.connect(jmxServiceUrl);
        ObjectInstance adapterRegistry = jmxConnector.getMBeanServerConnection().getObjectInstance(ObjectName.getInstance(AdapterRegistryMBean.STANDARD_REGISTRY_JMX_NAME));
        AdapterRegistryMBean adapterRegistryProxy = JMX.newMBeanProxy(jmxConnector.getMBeanServerConnection(), adapterRegistry.getObjectName(), AdapterRegistryMBean.class);
        assert adapterRegistryProxy != null : "AdapterRegistry not found";
        ObjectName adapterName = adapterRegistryProxy.getAdapters().stream().findFirst().orElseThrow();
        adapterManagerMBean = JMX.newMBeanProxy(jmxConnector.getMBeanServerConnection(), adapterName, AdapterManagerMBean.class);
        assert adapterManagerMBean != null : "AdapterManagerMBean not found";
    }

    protected void waitForAdapterStarted() throws Exception {
        long startTime = System.currentTimeMillis();
        while(!adapterManagerMBean.getComponentState().getClass().equals(StartedState.class)) {
            Thread.sleep(adapterStartWaitTime);
            long totalWaitTime = System.currentTimeMillis() - startTime;
            if (totalWaitTime >= adapterStartMaxWaitTime) {
                throw new RuntimeException("AdapterManagerMBean wait time exceeded");
            }
        }
    }

    protected void shutdownAdapter() throws Exception {
        try {
            if (adapterManagerMBean != null) {
                adapterManagerMBean.requestClose(adapterCloseMaxWaitTime);
            }
        } finally {
            if (jmxConnector != null) {
                jmxConnector.close();
            }
        }
    }

    @AfterAll
    public void tearDown() throws Exception {
        if (client != null) client.close();
        shutdownAdapter();
    }
}
