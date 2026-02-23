package com.atakmap.android.meshtastic.encryption;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

class DataPackageExporterTest {

    private static final String VALID_PSK = "K7gNU3sdo+OL0wNhqoVWhr3g6s1xYv72ol/pe/Unols=";

    private Map<String, String> readZipEntries(byte[] zipBytes) throws IOException {
        Map<String, String> entries = new LinkedHashMap<>();
        try (ZipInputStream zis = new ZipInputStream(new ByteArrayInputStream(zipBytes))) {
            ZipEntry entry;
            while ((entry = zis.getNextEntry()) != null) {
                byte[] buf = new byte[4096];
                ByteArrayOutputStream baos = new ByteArrayOutputStream();
                int n;
                while ((n = zis.read(buf)) != -1) baos.write(buf, 0, n);
                entries.put(entry.getName(), baos.toString(StandardCharsets.UTF_8.name()));
            }
        }
        return entries;
    }

    @Test
    void buildPackageBytes_containsManifestFile() throws Exception {
        byte[] zip = DataPackageExporter.buildPackageBytes(
                VALID_PSK, false, 6, "192.168.1.1", 8087);
        assertThat(readZipEntries(zip)).containsKey(DataPackageExporter.MANIFEST_FILENAME);
    }

    @Test
    void buildPackageBytes_containsConfigFile() throws Exception {
        byte[] zip = DataPackageExporter.buildPackageBytes(
                VALID_PSK, false, 6, "192.168.1.1", 8087);
        assertThat(readZipEntries(zip)).containsKey(DataPackageExporter.CONFIG_FILENAME);
    }

    @Test
    void buildPackageBytes_configContainsPsk() throws Exception {
        byte[] zip = DataPackageExporter.buildPackageBytes(
                VALID_PSK, false, 6, "192.168.1.1", 8087);
        String configContent = readZipEntries(zip).get(DataPackageExporter.CONFIG_FILENAME);
        assertThat(configContent).contains("\"psk\": \"" + VALID_PSK + "\"");
    }
}
