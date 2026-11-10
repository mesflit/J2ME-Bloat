/*
 * Copyright 2026 mesflit
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package ru.woesss.j2me.installer;

import android.content.ContentResolver;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.provider.DocumentsContract;
import android.util.Log;

import androidx.annotation.NonNull;

import net.lingala.zip4j.ZipFile;
import net.lingala.zip4j.io.inputstream.ZipInputStream;
import net.lingala.zip4j.model.FileHeader;
import net.lingala.zip4j.model.LocalFileHeader;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Collects installable MIDlet files (JAR, JAD, KJX) from ZIP archives and folders.
 *
 * Files found inside archives are extracted into a staging directory and returned
 * as {@code file://} URIs, which {@link AppInstaller} can open directly.
 */
public final class ImportSources {
	private static final String TAG = "ImportSources";
	private static final int BUFFER_SIZE = 64 * 1024;
	/** How deep archives inside archives are opened (game packs often nest ZIPs). */
	private static final int MAX_ZIP_DEPTH = 3;
	/** Guards against malicious or broken folder trees. */
	private static final int MAX_FOLDER_DEPTH = 16;

	public interface Listener {
		/** Called from a background thread whenever a new installable file is found. */
		void onFileFound(int count, String name);
	}

	private ImportSources() {
	}

	/** Directory used to stage files extracted from archives. */
	public static File newStagingDir(Context context) {
		return new File(context.getCacheDir(), "import/" + System.currentTimeMillis());
	}

	public static boolean isInstallable(String name) {
		String lower = name.toLowerCase(Locale.ROOT);
		return lower.endsWith(".jar") || lower.endsWith(".jad") || lower.endsWith(".kjx");
	}

	public static boolean isZip(String name) {
		return name.toLowerCase(Locale.ROOT).endsWith(".zip");
	}

	/**
	 * Extracts every JAR/JAD/KJX from a ZIP archive, including archives nested in it.
	 */
	@NonNull
	public static List<Uri> fromZip(Context context, Uri zip, File stagingDir,
									AtomicBoolean cancelled, Listener listener) throws IOException {
		List<Uri> result = new ArrayList<>();
		File root = newArchiveDir(stagingDir);
		ContentResolver resolver = context.getContentResolver();
		try (InputStream in = resolver.openInputStream(zip)) {
			if (in == null) {
				throw new IOException("Cannot open " + zip);
			}
			extractStream(new ZipInputStream(in), root, 0, result, cancelled, listener);
			return result;
		} catch (IOException | RuntimeException e) {
			if (cancelled.get()) {
				return result;
			}
			// Streaming fails for some archives (e.g. entries that rely on the central
			// directory). Fall back to copying the archive and reading it as a file.
			Log.w(TAG, "Streaming extraction failed, retrying from a local copy", e);
			deleteRecursive(root);
			result.clear();
			return fromZipFile(context, zip, stagingDir, cancelled, listener);
		}
	}

	private static List<Uri> fromZipFile(Context context, Uri zip, File stagingDir,
										 AtomicBoolean cancelled, Listener listener) throws IOException {
		List<Uri> result = new ArrayList<>();
		File copy = new File(stagingDir, "archive-" + System.nanoTime() + ".zip");
		File parent = copy.getParentFile();
		if (parent != null && !parent.isDirectory() && !parent.mkdirs()) {
			throw new IOException("Cannot create " + parent);
		}
		try (InputStream in = context.getContentResolver().openInputStream(zip);
			 OutputStream out = new FileOutputStream(copy)) {
			if (in == null) {
				throw new IOException("Cannot open " + zip);
			}
			copyStream(in, out, cancelled);
		}
		File root = newArchiveDir(stagingDir);
		try (ZipFile zipFile = new ZipFile(copy)) {
			for (FileHeader header : zipFile.getFileHeaders()) {
				if (cancelled.get()) {
					break;
				}
				String name = header.getFileName();
				if (header.isDirectory() || name == null) {
					continue;
				}
				if (isInstallable(name)) {
					File target = safeTarget(root, name);
					try (InputStream in = zipFile.getInputStream(header)) {
						writeFile(in, target, cancelled);
					}
					addResult(target, result, listener);
				} else if (isZip(name)) {
					try (InputStream in = zipFile.getInputStream(header)) {
						extractStream(new ZipInputStream(in), newArchiveDir(root), 1,
								result, cancelled, listener);
					} catch (IOException | RuntimeException e) {
						Log.w(TAG, "Skipping broken nested archive: " + name, e);
					}
				}
			}
		} finally {
			//noinspection ResultOfMethodCallIgnored
			copy.delete();
		}
		return result;
	}

	private static void extractStream(ZipInputStream zis, File root, int depth, List<Uri> result,
									  AtomicBoolean cancelled, Listener listener) throws IOException {
		LocalFileHeader header;
		while (!cancelled.get() && (header = zis.getNextEntry()) != null) {
			String name = header.getFileName();
			if (header.isDirectory() || name == null) {
				continue;
			}
			if (isInstallable(name)) {
				File target = safeTarget(root, name);
				writeFile(zis, target, cancelled);
				addResult(target, result, listener);
			} else if (isZip(name) && depth + 1 < MAX_ZIP_DEPTH) {
				// Do not close the nested stream: that would close the outer one too.
				try {
					extractStream(new ZipInputStream(zis), newArchiveDir(root), depth + 1,
							result, cancelled, listener);
				} catch (IOException | RuntimeException e) {
					Log.w(TAG, "Skipping broken nested archive: " + name, e);
				}
			}
		}
	}

	/**
	 * Walks a folder picked with the system folder picker and returns all installable
	 * files in it. ZIP archives found in the folder are extracted as well.
	 */
	@NonNull
	public static List<Uri> fromTree(Context context, Uri treeUri, File stagingDir,
									 AtomicBoolean cancelled, Listener listener) {
		List<Uri> result = new ArrayList<>();
		String rootId = DocumentsContract.getTreeDocumentId(treeUri);
		walkTree(context, treeUri, rootId, 0, stagingDir, result, cancelled, listener);
		return result;
	}

	private static void walkTree(Context context, Uri treeUri, String documentId, int depth,
								 File stagingDir, List<Uri> result,
								 AtomicBoolean cancelled, Listener listener) {
		if (depth > MAX_FOLDER_DEPTH || cancelled.get()) {
			return;
		}
		Uri children = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, documentId);
		String[] projection = {
				DocumentsContract.Document.COLUMN_DOCUMENT_ID,
				DocumentsContract.Document.COLUMN_DISPLAY_NAME,
				DocumentsContract.Document.COLUMN_MIME_TYPE
		};
		List<String> subDirs = new ArrayList<>();
		try (Cursor cursor = context.getContentResolver()
				.query(children, projection, null, null, null)) {
			if (cursor == null) {
				return;
			}
			while (cursor.moveToNext() && !cancelled.get()) {
				String id = cursor.getString(0);
				String name = cursor.getString(1);
				String mime = cursor.getString(2);
				if (id == null || name == null) {
					continue;
				}
				if (DocumentsContract.Document.MIME_TYPE_DIR.equals(mime)) {
					subDirs.add(id);
				} else if (isInstallable(name)) {
					result.add(DocumentsContract.buildDocumentUriUsingTree(treeUri, id));
					if (listener != null) {
						listener.onFileFound(result.size(), name);
					}
				} else if (isZip(name)) {
					Uri zip = DocumentsContract.buildDocumentUriUsingTree(treeUri, id);
					try {
						result.addAll(fromZip(context, zip, stagingDir, cancelled, listener));
					} catch (IOException | RuntimeException e) {
						Log.w(TAG, "Skipping unreadable archive: " + name, e);
					}
				}
			}
		} catch (RuntimeException e) {
			Log.w(TAG, "Cannot list folder " + documentId, e);
		}
		for (String id : subDirs) {
			walkTree(context, treeUri, id, depth + 1, stagingDir, result, cancelled, listener);
		}
	}

	public static void deleteRecursive(File file) {
		if (file == null || !file.exists()) {
			return;
		}
		File[] files = file.listFiles();
		if (files != null) {
			for (File f : files) {
				deleteRecursive(f);
			}
		}
		//noinspection ResultOfMethodCallIgnored
		file.delete();
	}

	private static File newArchiveDir(File parent) throws IOException {
		File dir = new File(parent, "a" + System.nanoTime());
		if (!dir.isDirectory() && !dir.mkdirs()) {
			throw new IOException("Cannot create " + dir);
		}
		return dir;
	}

	/** Resolves an archive entry inside {@code root}, rejecting "zip slip" paths. */
	private static File safeTarget(File root, String entryName) throws IOException {
		String clean = entryName.replace('\\', '/');
		StringBuilder path = new StringBuilder();
		for (String part : clean.split("/")) {
			if (part.isEmpty() || part.equals(".") || part.equals("..")) {
				continue;
			}
			if (path.length() > 0) {
				path.append('/');
			}
			path.append(part.replaceAll("[\\x00-\\x1f:*?\"<>|]", "_"));
		}
		if (path.length() == 0) {
			throw new IOException("Invalid entry name: " + entryName);
		}
		File target = new File(root, path.toString());
		String rootPath = root.getCanonicalPath() + File.separator;
		if (!target.getCanonicalPath().startsWith(rootPath)) {
			throw new IOException("Entry is outside of the target dir: " + entryName);
		}
		File parent = target.getParentFile();
		if (parent != null && !parent.isDirectory() && !parent.mkdirs()) {
			throw new IOException("Cannot create " + parent);
		}
		return target;
	}

	private static void addResult(File file, List<Uri> result, Listener listener) {
		result.add(Uri.fromFile(file));
		if (listener != null) {
			listener.onFileFound(result.size(), file.getName());
		}
	}

	private static void writeFile(InputStream in, File target, AtomicBoolean cancelled)
			throws IOException {
		try (OutputStream out = new FileOutputStream(target)) {
			copyStream(in, out, cancelled);
		}
	}

	private static void copyStream(InputStream in, OutputStream out, AtomicBoolean cancelled)
			throws IOException {
		byte[] buffer = new byte[BUFFER_SIZE];
		int len;
		while ((len = in.read(buffer)) != -1) {
			if (cancelled.get()) {
				throw new IOException("Cancelled");
			}
			out.write(buffer, 0, len);
		}
	}
}
