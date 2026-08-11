package com.valorcraft.veconomy.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ConfigPathsTest {

    @TempDir
    Path configDirectory;

    @Test
    void usesModSpecificDirectoryWithoutTouchingSiblingMods() throws Exception {
        Path siblingConfig = configDirectory.resolve("VMods/VOther/settings.toml");
        Files.createDirectories(siblingConfig.getParent());
        Files.writeString(siblingConfig, "other-mod", StandardCharsets.UTF_8);

        ConfigPaths.prepare(configDirectory);

        assertEquals(configDirectory.resolve("VMods/VEconomy"), ConfigPaths.directory(configDirectory));
        assertTrue(Files.isDirectory(ConfigPaths.directory(configDirectory)));
        assertEquals("other-mod", Files.readString(siblingConfig, StandardCharsets.UTF_8));
    }

    @Test
    void migratesLegacyConfigWithoutOverwritingExistingTarget() throws Exception {
        Path source = configDirectory.resolve("economy-core.toml");
        Path target = ConfigPaths.directory(configDirectory).resolve("economy-core.toml");
        Files.createDirectories(target.getParent());
        Files.writeString(source, "legacy", StandardCharsets.UTF_8);
        Files.writeString(target, "keep-me", StandardCharsets.UTF_8);

        ConfigPaths.prepare(configDirectory);

        assertEquals("legacy", Files.readString(source, StandardCharsets.UTF_8));
        assertEquals("keep-me", Files.readString(target, StandardCharsets.UTF_8));
    }

    @Test
    void copiesLegacyConfigWhenTargetDoesNotExist() throws Exception {
        Path source = configDirectory.resolve(AuditConfig.FILE_NAME);
        Path target = ConfigPaths.directory(configDirectory).resolve(AuditConfig.FILE_NAME);
        Files.writeString(source, "legacy-audit", StandardCharsets.UTF_8);

        ConfigPaths.prepare(configDirectory);

        assertEquals("legacy-audit", Files.readString(source, StandardCharsets.UTF_8));
        assertEquals("legacy-audit", Files.readString(target, StandardCharsets.UTF_8));
    }
}
