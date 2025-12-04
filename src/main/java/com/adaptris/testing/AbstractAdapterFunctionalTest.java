package com.adaptris.testing;

import javax.management.remote.JMXServiceURL;
import java.io.*;
import java.util.Properties;

public abstract class AbstractAdapterFunctionalTest {
    protected Properties bootstrapProperties;
    protected Properties variablesProperties;

    protected void customiseBootstrapProperties(Properties props) {
    }

    protected void customiseBootstrapPropertiesAfterStore(Properties props) {
    }

    protected void customiseVariablesIfExists(Properties props) {
    }

    protected void setupBootstrap() {
        File bootstrapFile = new File(resolveBootstrapLocation());
        assert bootstrapFile.exists() : "Bootstrap file does not exist: " + bootstrapFile.getAbsolutePath();
        bootstrapProperties = new Properties();
        try (InputStream is = new FileInputStream(bootstrapFile)) {
            bootstrapProperties.load(is);
            customiseBootstrapProperties(bootstrapProperties);
            try (OutputStream os = new FileOutputStream(bootstrapFile)) {
                bootstrapProperties.store(os, null);
                customiseBootstrapPropertiesAfterStore(bootstrapProperties);
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    protected String resolveBootstrapLocation() {
        return "./config/bootstrap.properties";
    }

    protected String resolveVariablesLocation() {
        return "./config/variables.properties";
    }

    protected void setupVariables() {
        File variablesFile = new File(resolveVariablesLocation());
        if (variablesFile.exists()) {
            variablesProperties = new Properties();
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

}
