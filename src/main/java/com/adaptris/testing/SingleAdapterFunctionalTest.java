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
import java.io.*;
import java.net.ServerSocket;
import java.nio.file.*;
import java.util.Properties;
import java.util.concurrent.Callable;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * This class starts up and waits for the adapter to be available based on configuration files
 * located at ./config/bootstrap.properties, ./config/variables.properties. JMX must be enabled
 * and exposed.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
public class SingleAdapterFunctionalTest extends AbstractAdapterFunctionalTest {
    protected WatchService watchService;
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
        watchService = FileSystems.getDefault().newWatchService();
        withReservedSocket(this::setupAdapter, this::launchAdapter);
        connectJMX();
        waitForAdapterStarted();
    }

    @AfterAll
    public void tearDown() throws Exception {
        if (client != null) client.close();
        shutdownAdapter();
    }
    protected void setupAdapter(ServerSocket serverSocket) {
        serverPort = serverSocket.getLocalPort();
        File bootstrapFile = new File("./config/bootstrap.properties");
        assert bootstrapFile.exists() : "Bootstrap file does not exist: " + bootstrapFile.getAbsolutePath();
        Properties bootstrapProperties = new Properties();
        try (InputStream is = new FileInputStream(bootstrapFile)) {
            bootstrapProperties.load(is);
            customiseBootstrapProperties(bootstrapProperties);
            String jmxServiceUrlStr = bootstrapProperties.getProperty("jmxserviceurl", null);
            assert jmxServiceUrlStr != null : "Bootstrap file must have jmxserviceurl property";
            jmxServiceUrl = new JMXServiceURL(jmxServiceUrlStr);
            try (OutputStream os = new FileOutputStream(bootstrapFile)) {
                bootstrapProperties.store(os, null);
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        } catch (Exception e) {
            throw new RuntimeException(e);
        }

        File variablesFile = new File("./config/variables.properties");
        if (variablesFile.exists()) {
            Properties variablesProperties = new Properties();
            try (InputStream is = new FileInputStream(variablesFile)) {
                variablesProperties.load(is);
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
            customiseVariablesIfExists(variablesProperties);
            try (OutputStream os = new FileOutputStream(variablesFile)) {
                variablesProperties.store(os, null);
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }
    }

    protected void customiseBootstrapProperties(Properties props) {
        props.put("webServerPort", String.valueOf(serverPort));
    }

    protected void customiseVariablesIfExists(Properties props) {
        props.put("sysprop.jetty.http.port", String.valueOf(serverPort));
    }

    protected void withReservedSocket(Consumer<ServerSocket> reservedFn, Callable<Void> afterClosedFn) throws Exception {
        try (ServerSocket serverSocket = new ServerSocket(0)) {
            reservedFn.accept(serverSocket);
        }
        if (afterClosedFn != null) afterClosedFn.call();
    }

    protected Void launchAdapter() throws Exception{
        InterlokLauncher.main(new String[] {"./config/bootstrap.properties"});
        return null;
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

    protected WatchKey waitForFileEvent(Path path, long timeOutMs, WatchEvent.Kind<?>... eventKinds) throws Exception {
        path.register(watchService, eventKinds);
        return watchService.poll(timeOutMs, TimeUnit.MILLISECONDS);
    }
}
