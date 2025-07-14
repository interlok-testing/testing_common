package com.adaptris.testing;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.TestInstance;
import org.testcontainers.containers.ComposeContainer;

import java.net.InetSocketAddress;

/**
 * This class uses the TestContainers library to setup a JUnit test case according to a docker compose
 * file.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
public abstract class DockerComposeFunctionalTest extends AbstractAdapterFunctionalTest {


    private ComposeContainer environment;

    @BeforeAll
    public void setup() throws Exception {
        this.environment = setupContainers();
        this.environment.start();
    }

    @AfterAll
    public void teardown() throws Exception {
        if (this.environment != null) {
            this.environment.stop();
        }
    }


    protected InetSocketAddress getHostAddressForService(String serviceName, int port) {
        return new InetSocketAddress(environment.getServiceHost(serviceName, port), environment.getServicePort(serviceName, port));
    }

    protected abstract ComposeContainer setupContainers() throws Exception;

}
