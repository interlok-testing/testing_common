package com.adaptris.testing;

import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.TestInstance;

import java.io.*;
import java.net.ServerSocket;
import java.nio.file.*;
import java.util.ArrayList;
import java.util.LinkedList;
import java.util.List;
import java.util.Properties;
import java.util.concurrent.Callable;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

/**
 * This class starts up and waits for the adapter to be available based on configuration files
 * located at ./config/bootstrap.properties, ./config/variables.properties. JMX must be enabled
 * and exposed.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
public abstract class MultiAdapterFunctionalTest extends AbstractAdapterFunctionalTest {
    protected WatchService watchService;
    protected CloseableHttpClient client = HttpClients.createDefault();

    protected int adapterStartWaitTime = 1000;
    protected int adapterStartMaxWaitTime = 10000;
    protected int adapterCloseMaxWaitTime = 10000;

    public abstract List<File> getBootstrapFiles();

    protected List<AdapterInstance> instances = new ArrayList<>();
    protected List<Object> locks = new LinkedList<>();

    @BeforeAll
    public void setup() throws Exception {
        watchService = FileSystems.getDefault().newWatchService();
        AtomicInteger ctr = new AtomicInteger();
        getBootstrapFiles().forEach(file -> {
            AdapterInstance instance = new AdapterInstance(file);
            instance.setPosition(ctr.getAndIncrement());
            instances.add(instance);
            try {
                new Thread(() -> {
                    try {
                        synchronized (ctr) {
                            withReservedSocket(serverSocket -> {
                                try {
                                    setupAdapter(instance, serverSocket, false);
                                } catch (Exception e) {
                                    throw new RuntimeException(e);
                                }
                            }, () -> {
                                launchAdapter(instance);
                                return null;
                            });
                        }
                    } catch (Exception e) {
                        throw new RuntimeException(e);
                    }
                }).run();
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });
    }

    @AfterAll
    public void tearDown() throws Exception {
        if (client != null) client.close();
        shutdownAdapter();
    }
    protected void setupAdapter(AdapterInstance instance, ServerSocket serverSocket, boolean withLicense) throws Exception {
        Integer serverPort = serverSocket.getLocalPort();
        instance.setServerPort(serverPort);
        if (withLicense) {
            instance.withLicense();
        }

        instance.customiseBootstrap(customiseBootstrap(instance, new Properties()));
        instance.customiseVariables(customiseVariables(instance, new Properties()));

        instance.writeVariables();
        instance.writeBootstrap();
    }

    protected Properties customiseBootstrap(AdapterInstance instance, Properties properties) throws Exception {
        return properties;
    }

    protected Properties customiseVariables(AdapterInstance instance, Properties properties) throws Exception {
        return properties;
    }

    protected void withReservedSocket(Consumer<ServerSocket> reservedFn, Callable<Void> afterClosedFn) throws Exception {
        try (ServerSocket serverSocket = new ServerSocket(0)) {
            reservedFn.accept(serverSocket);
        }
        if (afterClosedFn != null) afterClosedFn.call();
    }

    protected Void launchAdapter(AdapterInstance instance) throws Exception {
        try {
            instance.launch();
            //instance.waitForStarted(adapterStartWaitTime, adapterStartMaxWaitTime);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
        return null;
    }



    protected void shutdownAdapter() throws Exception {
        instances.forEach(instance -> {
            try {
                instance.shutdown(adapterCloseMaxWaitTime);
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });
    }

    protected WatchKey waitForFileEvent(Path path, long timeOutMs, WatchEvent.Kind<?>... eventKinds) throws Exception {
        path.register(watchService, eventKinds);
        return watchService.poll(timeOutMs, TimeUnit.MILLISECONDS);
    }
}
