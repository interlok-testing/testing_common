package com.adaptris.testing;

import com.adaptris.core.ComponentState;
import com.adaptris.core.StartedState;
import com.adaptris.core.fs.FsHelper;
import com.adaptris.core.runtime.AdapterManagerMBean;
import com.adaptris.core.runtime.AdapterRegistryMBean;
import com.adaptris.interlok.boot.InterlokLauncher;
import com.adaptris.util.license.LicenseCreatorFactory;
import org.apache.commons.io.FileUtils;

import javax.management.JMX;
import javax.management.ObjectInstance;
import javax.management.ObjectName;
import javax.management.remote.JMXConnector;
import javax.management.remote.JMXConnectorFactory;
import javax.management.remote.JMXServiceURL;
import java.io.*;
import java.nio.charset.Charset;
import java.nio.file.Paths;
import java.time.LocalDate;
import java.util.*;

public class AdapterInstance {
    protected File bootstrapFile;
    protected File variablesFile;
    protected File licenseFile;
    Properties bootstrapProperties = new Properties();
    Properties variablesProperties = new Properties();
    protected Integer serverPort;
    protected JMXServiceURL jmxServiceUrl;
    protected JMXConnector jmxConnector;
    protected AdapterManagerMBean adapterManagerMBean;
    protected boolean isFailover = false;
    protected Process process;

    protected Object lock = null;

    protected int position;

    public AdapterInstance(File bootstrapFile) {
        load(bootstrapFile);
    }

    protected void load(File bootstrapFile) {
        this.bootstrapFile = bootstrapFile;
        loadBootstrap();
        loadVariables();
    }

    protected void setPosition(int position) {
        this.position = position;
    }

    protected void loadBootstrap() {
        assert bootstrapFile.exists() : "Bootstrap file does not exist: " + bootstrapFile.getAbsolutePath();
        bootstrapProperties = new Properties();
        try (InputStream is = new FileInputStream(bootstrapFile)) {
            bootstrapProperties.load(is);
            String jmxServiceUrlStr = bootstrapProperties.getProperty("jmxserviceurl", null);
            assert jmxServiceUrlStr != null : "Bootstrap file must have jmxserviceurl property";
            jmxServiceUrl = new JMXServiceURL(jmxServiceUrlStr);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    protected void customiseBootstrap(Properties props) {
        bootstrapProperties.putAll(props);
    }

    protected void writeBootstrap() {
        try (OutputStream os = new FileOutputStream(bootstrapFile)) {
            bootstrapProperties.setProperty("variable-substitution.properties.url.0", buildPositionalVariablesFile());
            bootstrapProperties.store(os, null);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    protected String buildPositionalVariablesFile() {
        return variablesFile.getAbsolutePath() + "." + position;
    }

    protected void customiseVariables(Properties props) {
        variablesProperties.put("testing.adapterPosition",  String.valueOf(position));
        variablesProperties.putAll(props);
    }

    protected void loadVariables() {
        String variablesUrlStr = bootstrapProperties.getProperty("variable-substitution.properties.url.0");
        if (variablesUrlStr != null && !variablesUrlStr.isEmpty()) {
            variablesFile = FsHelper.toFile(variablesUrlStr, null);
            if (variablesFile != null && variablesFile.exists()) {
                try (InputStream is = new FileInputStream(variablesFile)) {
                    variablesProperties.load(is);
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            }
        }
    }

    protected void writeVariables() {
        try (OutputStream os = new FileOutputStream(buildPositionalVariablesFile())) {
            variablesProperties.store(os, null);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    public boolean isVariablesFileExists() {
        return variablesFile != null && variablesFile.exists();
    }

    public void setServerPort(Integer serverPort) {
        this.serverPort = serverPort;
        variablesProperties.put("sysprop.jetty.http.port", String.valueOf(serverPort));
        bootstrapProperties.put("webServerPort", String.valueOf(serverPort));
    }

    public Integer getServerPort() {
        return serverPort;
    }

    public File getBootstrapFile() {
        return bootstrapFile;
    }

    public File getVariablesFile() {
        return variablesFile;
    }

    public File getLicenseFile() {
        return licenseFile;
    }

    public void connectJMX() throws Exception {
        if (jmxServiceUrl == null) throw new RuntimeException("JMX service URL not set");
        jmxConnector = JMXConnectorFactory.connect(jmxServiceUrl);
        ObjectInstance adapterRegistry = jmxConnector.getMBeanServerConnection().getObjectInstance(ObjectName.getInstance(AdapterRegistryMBean.STANDARD_REGISTRY_JMX_NAME));
        AdapterRegistryMBean adapterRegistryProxy = JMX.newMBeanProxy(jmxConnector.getMBeanServerConnection(), adapterRegistry.getObjectName(), AdapterRegistryMBean.class);
        assert adapterRegistryProxy != null : "AdapterRegistry not found";
        ObjectName adapterName = adapterRegistryProxy.getAdapters().stream().findFirst().orElseThrow();
        adapterManagerMBean = JMX.newMBeanProxy(jmxConnector.getMBeanServerConnection(), adapterName, AdapterManagerMBean.class);
        assert adapterManagerMBean != null : "AdapterManagerMBean not found";
    }

    public ComponentState getComponentState() {
        return adapterManagerMBean.getComponentState();
    }

    public Void launch() throws Exception {
        if (this.isLicensed()) {
            System.setProperty("adp.license.location", licenseFile.getAbsolutePath());
        }
        List<String> args = new LinkedList<>(List.of(bootstrapFile.getAbsolutePath()));
        if (this.isFailover) {
            args.add("--failover");
        }
        try {
            InterlokLauncher.main(args.toArray(new String[0]));
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
        return null;
    }

    public Void launchProcess() throws Exception {
        ProcessBuilder pb = new ProcessBuilder();
        List<String> commands = new LinkedList<>();
        String javaCommand = Arrays.stream(ProcessHandle.current().info().commandLine().get().split(" ")).findFirst().get();
        commands.add(javaCommand);
        commands.add("-jar");
        commands.add("lib/interlok-boot.jar");
        if (this.isLicensed()) {
            commands.add("-Dadp.license.location=" + licenseFile.getAbsolutePath());
        }
        commands.add(bootstrapFile.getAbsolutePath());
        if (this.isFailover) {
            commands.add("--failover");
        }
        pb.command(commands);
        pb.redirectError(new File("adapter.log." + position));
        pb.redirectOutput(new File("adapter.log." + position));
        pb.directory(new File("").getAbsoluteFile());
        process = pb.start();
        Runtime.getRuntime().addShutdownHook(new Thread(this::shutdownProcess));
        return null;
    }

    public void shutdownProcess() {
        if (process != null && process.isAlive()) process.destroy();
    }

    public boolean isJmxConnected() {
        return jmxConnector != null;
    }

    protected void waitForStarted(int adapterStartWaitTime, int adapterStartMaxWaitTime) throws Exception {
        long startTime = System.currentTimeMillis();
        while(this.adapterManagerMBean == null || !adapterManagerMBean.getComponentState().getClass().equals(StartedState.class)) {
            if (this.adapterManagerMBean == null) {
                try {
                    connectJMX();
                } catch (Exception ex) {
                    Thread.sleep(adapterStartWaitTime);
                    // ignore
                    ex.printStackTrace();
                }
            } else {
                Thread.sleep(adapterStartWaitTime);
            }
            long totalWaitTime = System.currentTimeMillis() - startTime;
            if (totalWaitTime >= adapterStartMaxWaitTime) {
                throw new RuntimeException("AdapterManagerMBean wait time exceeded");
            }
        }
    }

    public void shutdown(int adapterCloseMaxWaitTime) throws Exception {
        try {
            if (adapterManagerMBean != null) {
                try {
                    adapterManagerMBean.requestStop(adapterCloseMaxWaitTime);
                } finally {
                    adapterManagerMBean.requestClose(adapterCloseMaxWaitTime);
                    adapterManagerMBean.forceClose();
                }
            }
        } finally {
            if (jmxConnector != null) {
                try {
                    jmxConnector.close();
                } finally {
                    jmxConnector = null;
                    shutdownProcess();
                }
            }
        }
    }

    public AdapterInstance withLicense() throws Exception {
        return withLicense("Enterprise");
    }

    public AdapterInstance withFailover() throws Exception {
        this.isFailover = true;
        return this;
    }

    public AdapterInstance withLicense(String type) throws Exception {
        Map<String, Object> config = Map.of("expiryDate", LocalDate.now().plusDays(1), "type", type);
        String license = LicenseCreatorFactory.getCreator(config).create();
        licenseFile = Paths.get("config/license.properties").toFile();
        if (licenseFile.exists()) {
            licenseFile.delete();
        }
        FileUtils.writeStringToFile(licenseFile, "adp.license.key=" + license, Charset.defaultCharset());
        return this;
    }

    public boolean isLicensed() {
        return licenseFile != null && licenseFile.exists();
    }

}
