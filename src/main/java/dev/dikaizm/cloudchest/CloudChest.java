package dev.dikaizm.cloudchest;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Properties;
import java.util.function.BiConsumer;
import java.util.stream.Stream;
import java.util.zip.GZIPInputStream;
import java.util.zip.InflaterInputStream;
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
	/** Set by the client entrypoint to show a toast; no-op on dedicated servers. */
	static volatile BiConsumer<String, String> notifier = (title, msg) -> {};
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
			if (dest.isEmpty()) dest = detectCloudFolder();
			if (dest == null) {
				LOG.warn("No Google Drive/iCloud/Dropbox folder found; set destination in config/cloudchest.properties");
				notifier.accept("Backup skipped", "No cloud folder found");
				return;
			}
			int keep = Integer.parseInt(cfg.getProperty("keep", "10").trim());
			Path world = server.getWorldPath(LevelResource.ROOT).toAbsolutePath().normalize();
			String name = world.getFileName().toString().equals(".") ? world.getParent().getFileName().toString() : world.getFileName().toString();
			Path dir = Path.of(dest.replaceFirst("^~", System.getProperty("user.home"))).resolve(name);
			Files.createDirectories(dir);

			boolean zip = cfg.getProperty("mode", "mirror").trim().equals("zip");
			verify(world, zip ? null : dir.resolve("world"));
			if (zip) {
				String file = name + "-" + LocalDateTime.now().format(STAMP) + ".zip";
				Path tmp = dir.resolve(file + ".part"); // cloud apps skip half-written files this way
				zip(world, tmp);
				Files.move(tmp, dir.resolve(file));
				LOG.info("Backed up {} -> {}", name, dir.resolve(file));
				notifier.accept("World backed up", name);
				prune(dir, keep);
			} else {
				int changed = mirror(world, dir.resolve("world"));
				LOG.info("Mirrored {} -> {} ({} files changed)", name, dir.resolve("world"), changed);
				notifier.accept("World backed up", name + ": " + changed + " files changed");
			}
		} catch (Corrupt e) {
			LOG.error("World looks corrupt, backup skipped (last good backup kept): {}", e.getMessage());
			notifier.accept("Backup skipped: world corrupt", e.getMessage());
		} catch (Exception e) {
			LOG.error("Backup failed", e);
			notifier.accept("Backup failed", "See latest.log");
		}
	}

	/** First synced folder found: Google Drive, iCloud Drive, Dropbox. Null if none. */
	static String detectCloudFolder() throws IOException {
		Path home = Path.of(System.getProperty("user.home"));
		Path cloud = home.resolve("Library/CloudStorage");
		if (Files.isDirectory(cloud)) {
			try (Stream<Path> s = Files.list(cloud)) {
				var drive = s.filter(p -> p.getFileName().toString().startsWith("GoogleDrive-"))
					.map(p -> p.resolve("My Drive")).filter(Files::isDirectory).findFirst();
				if (drive.isPresent()) return drive.get().resolve("MinecraftBackups").toString();
			}
		}
		for (String rel : new String[] {"Library/Mobile Documents/com~apple~CloudDocs", "Dropbox", "Google Drive/My Drive"}) {
			if (Files.isDirectory(home.resolve(rel))) return home.resolve(rel).resolve("MinecraftBackups").toString();
		}
		return null;
	}

	static Properties loadConfig() throws IOException {
		Path p = FabricLoader.getInstance().getConfigDir().resolve("cloudchest.properties");
		if (Files.notExists(p)) {
			Files.writeString(p, """
				# Leave empty to auto-detect (Google Drive, then iCloud, then Dropbox). Or set a folder, e.g.:
				#   Google Drive: ~/Library/CloudStorage/GoogleDrive-you@gmail.com/My Drive/MinecraftBackups
				#   iCloud:       ~/Library/Mobile Documents/com~apple~CloudDocs/MinecraftBackups
				destination=
				# mirror = copy only changed files (small uploads, no history). zip = full zip per backup.
				mode=mirror
				# zip mode only: zips kept per world, oldest deleted first
				keep=10
				""".replaceAll("(?m)^\t+", ""));
		}
		Properties props = new Properties();
		try (InputStream in = Files.newInputStream(p)) {
			props.load(in);
		}
		return props;
	}

	static class Corrupt extends IOException {
		Corrupt(String msg) { super(msg); }
	}

	static boolean changed(Path f, Path t) throws IOException {
		return Files.notExists(t) || Files.size(t) != Files.size(f)
			|| !Files.getLastModifiedTime(t).equals(Files.getLastModifiedTime(f));
	}

	/**
	 * Throws Corrupt if a file about to be backed up fails to parse, so a bad world never overwrites a good backup.
	 * Mirror mode (out != null) checks only changed files; zip mode checks all.
	 * Catches truncated/garbled region files and level.dat, not logical damage (valid-but-wrong data).
	 */
	static void verify(Path world, Path out) throws IOException {
		try (Stream<Path> files = Files.walk(world)) {
			for (Path f : (Iterable<Path>) files.filter(Files::isRegularFile)::iterator) {
				String n = f.getFileName().toString();
				if (out != null && !changed(f, out.resolve(world.relativize(f).toString()))) continue;
				try {
					if (n.endsWith(".mca")) checkRegion(f);
					else if (n.equals("level.dat")) {
						try (InputStream in = new GZIPInputStream(Files.newInputStream(f))) {
							in.transferTo(OutputStream.nullOutputStream());
						}
					}
				} catch (IOException e) {
					throw new Corrupt(world.relativize(f) + ": " + e);
				}
			}
		}
	}

	/** Region file: 8KB header (1024 chunk locations + timestamps), then 4KB sectors of [len][type][data]. */
	static void checkRegion(Path f) throws IOException {
		long size = Files.size(f);
		if (size == 0) return; // vanilla leaves empty region files
		if (size < 8192) throw new IOException("truncated header, " + size + " bytes");
		try (FileChannel ch = FileChannel.open(f)) {
			ByteBuffer locs = ByteBuffer.allocate(4096);
			ch.read(locs, 0);
			for (int i = 0; i < 1024; i++) {
				int e = locs.getInt(i * 4);
				if (e == 0) continue;
				long off = (e >>> 8) & 0xFFFFFF;
				int secs = e & 0xFF;
				if (off < 2 || secs == 0 || (off + secs) * 4096L > size) throw new IOException("chunk " + i + " outside file (truncated?)");
				ByteBuffer h = ByteBuffer.allocate(5);
				ch.read(h, off * 4096);
				int len = h.getInt(0), type = h.get(4);
				if (len < 1 || len > secs * 4096L - 4) throw new IOException("chunk " + i + " bad length " + len);
				if (type != 1 && type != 2) continue; // other/external compression: structure ok, skip payload
				ByteBuffer data = ByteBuffer.allocate(len - 1);
				ch.read(data, off * 4096 + 5);
				InputStream raw = new ByteArrayInputStream(data.array());
				try (InputStream in = type == 1 ? new GZIPInputStream(raw) : new InflaterInputStream(raw)) {
					in.transferTo(OutputStream.nullOutputStream());
				} catch (IOException x) {
					throw new IOException("chunk " + i + " data unreadable: " + x.getMessage());
				}
			}
		}
	}

	/** Copy only files whose size/mtime differ, delete files gone from the world. Returns files copied. */
	static int mirror(Path world, Path out) throws IOException {
		int copied = 0;
		try (Stream<Path> files = Files.walk(world)) {
			for (Path f : (Iterable<Path>) files.filter(Files::isRegularFile)::iterator) {
				if (f.getFileName().toString().equals("session.lock")) continue;
				Path t = out.resolve(world.relativize(f).toString());
				if (!changed(f, t)) continue;
				Files.createDirectories(t.getParent());
				Path tmp = t.resolveSibling(t.getFileName() + ".part");
				Files.copy(f, tmp, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.COPY_ATTRIBUTES);
				Files.move(tmp, t, StandardCopyOption.REPLACE_EXISTING);
				copied++;
			}
		}
		if (Files.isDirectory(out)) {
			try (Stream<Path> files = Files.walk(out)) {
				for (Path t : (Iterable<Path>) files.filter(Files::isRegularFile)::iterator) {
					if (Files.notExists(world.resolve(out.relativize(t).toString()))) Files.delete(t);
				}
			}
		}
		return copied;
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
