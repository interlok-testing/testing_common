package com.adaptris.testing;

import com.adaptris.util.license.LicenseCreatorFactory;
import org.apache.commons.io.FileUtils;

import java.io.File;
import java.nio.charset.Charset;
import java.nio.file.Paths;
import java.time.LocalDate;
import java.util.Map;
import java.util.Properties;

public class LicensedSingleAdapterFunctionalTest extends SingleAdapterFunctionalTest {

    @Override
    protected void customiseBootstrapProperties(Properties props) {
        super.customiseBootstrapProperties(props);
        System.setProperty("adp.license.location", "config/license.properties");
        try {
            String license = generateLicense("Enterprise");
            File licenseFile = Paths.get("config/license.properties").toFile();
            if (licenseFile.exists()) {
                licenseFile.delete();
            }
            FileUtils.writeStringToFile(licenseFile, "adp.license.key=" + license, Charset.defaultCharset());
        } catch (Exception ex) {
            throw new RuntimeException(ex);
        }
    }

    public String generateLicense(String type) throws Exception {
        Map<String, Object> config = Map.of("expiryDate", LocalDate.now().plusDays(1), "type", type);
        return LicenseCreatorFactory.getCreator(config).create();
    }
}
