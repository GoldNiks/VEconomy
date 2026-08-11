package com.valorcraft.veconomy.config;

import com.valorcraft.veconomy.VEconomyMod;
import net.minecraftforge.fml.loading.FMLPaths;

import java.io.IOException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * Единственное пространство имён конфигурации VEconomy внутри каталога Forge {@code config}.
 * Каталог конкретного мода не пересекается с каталогами остальных модов серии V.
 */
public final class ConfigPaths {

    public static final String FORGE_DIRECTORY = "VMods/VEconomy";
    public static final String ECONOMY_CONFIG = FORGE_DIRECTORY + "/economy-core.toml";

    private static final List<String> LEGACY_FILES = List.of(
            "economy-core.toml",
            AuditConfig.FILE_NAME,
            MilestoneConfig.FILE_NAME,
            "veconomy-quests.json"
    );

    private ConfigPaths() {}

    /** Каталог всех конфигов VEconomy: {@code config/VMods/VEconomy}. */
    public static Path directory() {
        return directory(FMLPaths.CONFIGDIR.get());
    }

    static Path directory(Path forgeConfigDirectory) {
        return forgeConfigDirectory.resolve("VMods").resolve("VEconomy");
    }

    /**
     * Создаёт каталог мода и безопасно переносит прежние конфиги из корня {@code config}.
     * Существующие целевые файлы никогда не заменяются.
     */
    public static void prepare() {
        prepare(FMLPaths.CONFIGDIR.get());
    }

    static void prepare(Path forgeConfigDirectory) {
        Path targetDirectory = directory(forgeConfigDirectory);
        try {
            Files.createDirectories(targetDirectory);
        } catch (IOException e) {
            VEconomyMod.LOGGER.error("Не удалось создать каталог конфигов VEconomy {}: {}",
                    targetDirectory, e.toString());
            return;
        }

        for (String fileName : LEGACY_FILES) {
            Path source = forgeConfigDirectory.resolve(fileName);
            Path target = targetDirectory.resolve(fileName);
            if (!Files.isRegularFile(source)) {
                continue;
            }
            try {
                Files.copy(source, target);
                VEconomyMod.LOGGER.info("Старый конфиг VEconomy скопирован без удаления исходника: {} -> {}",
                        source, target);
            } catch (FileAlreadyExistsException ignored) {
                VEconomyMod.LOGGER.info("Конфиг VEconomy уже существует и не будет перезаписан: {}", target);
            } catch (IOException e) {
                VEconomyMod.LOGGER.error("Не удалось скопировать старый конфиг VEconomy {} в {}: {}",
                        source, target, e.toString());
            }
        }
    }
}
