package dev.dikaizm.cloudchest;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Properties;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.storage.LevelResource;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * On world close, zip the save into config/cloudchest.properties `destination`.
 * The cloud app (Google Drive / iCloud / Dropbox) syncs that folder; we only write files.
 */
public class CloudChest implements ModInitializer {
	static final Logger LOG = LoggerFactory.getLogger("cloudchest");
	static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");

	@Override
	public void onInitialize() {
		// SERVER_STOPPED fires after the final save, for singleplayer (integrated server) and dedicated.
		// ponytail: runs synchronously, delays game exit by the zip time; move to a thread if worlds get huge.
		ServerLifecycleEvents.SERVER_STOPPED.register(CloudChest::backup);
	}

	static void backup(MinecraftServer server) {
		try {
			Properties cfg = loadConfig();
			String dest = cfg.getProperty("destination", "").trim();
			if (dest.isEmpty()) {
				LOG.warn("No destination set in config/cloudchest.properties, skipping backup");
				return;
			}
			int keep = Integer.parseInt(cfg.getProperty("keep", "10").trim());
			Path world = server.getWorldPath(LevelResource.ROOT).toAbsolutePath().normalize();
			String name = world.getFileName().toString().equals(".") ? world.getParent().getFileName().toString() : world.getFileName().toString();
			Path dir = Path.of(dest.replaceFirst("^~", System.getProperty("user.home"))).resolve(name);
			Files.createDirectories(dir);

			String file = name + "-" + LocalDateTime.now().format(STAMP) + ".zip";
			Path tmp = dir.resolve(file + ".part"); // cloud apps skip half-written files this way
			zip(world, tmp);
			Files.move(tmp, dir.resolve(file));
			LOG.info("Backed up {} -> {}", name, dir.resolve(file));
			prune(dir, keep);
		} catch (Exception e) {
			LOG.error("Backup failed", e);
		}
	}

	static Properties loadConfig() throws IOException {
		Path p = FabricLoader.getInstance().getConfigDir().resolve("cloudchest.properties");
		if (Files.notExists(p)) {
			Files.writeString(p, """
				# Folder synced by your cloud app. Examples:
				#   Google Drive: ~/Library/CloudStorage/GoogleDrive-you@gmail.com/My Drive/MinecraftBackups
				#   iCloud:       ~/Library/Mobile Documents/com~apple~CloudDocs/MinecraftBackups
				destination=
				# Zips kept per world; oldest deleted first
				keep=10
				""".replaceAll("(?m)^\t+", ""));
		}
		Properties props = new Properties();
		try (InputStream in = Files.newInputStream(p)) {
			props.load(in);
		}
		return props;
	}

	static void zip(Path world, Path out) throws IOException {
		try (OutputStream os = Files.newOutputStream(out);
			 ZipOutputStream zos = new ZipOutputStream(os);
			 Stream<Path> files = Files.walk(world)) {
			for (Path f : (Iterable<Path>) files.filter(Files::isRegularFile)::iterator) {
				if (f.getFileName().toString().equals("session.lock")) continue;
				zos.putNextEntry(new ZipEntry(world.relativize(f).toString().replace('\\', '/')));
				Files.copy(f, zos);
				zos.closeEntry();
			}
		}
	}

	static void prune(Path dir, int keep) throws IOException {
		try (Stream<Path> s = Files.list(dir)) {
			var zips = s.filter(p -> p.toString().endsWith(".zip")).sorted().toList(); // timestamp in name = chronological
			for (Path old : zips.subList(0, Math.max(0, zips.size() - keep))) Files.delete(old);
		}
	}
}
