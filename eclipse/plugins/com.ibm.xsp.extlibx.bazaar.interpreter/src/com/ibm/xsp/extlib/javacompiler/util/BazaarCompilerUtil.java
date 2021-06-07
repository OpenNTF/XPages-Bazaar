package com.ibm.xsp.extlib.javacompiler.util;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.FileSystem;
import java.nio.file.Files;
import java.nio.file.OpenOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Objects;

/**
 * Utility methods shared by compiler classes.
 * 
 * @author Jesse Gallagher
 * @since 2.0.7
 */
public enum BazaarCompilerUtil {
	;
	
	/**
	 * Opens an {@link InputStream} for the provided path.
	 * 
	 * <p>This method differs from {@link Files#newInputStream} in that it has special handling
	 * for ZIP filesystems to work around bugs in the Java 8 implementation. Specifically, when
	 * {@code path} is in a ZIP filesystem, this method first extracts the file to a temporary file.
	 * This file is deleted when the input stream is closed.</p>
	 *
     * @param path the path to the file to open
     * @param options options specifying how the file is opened
     * @return a new input stream
	 * @throws IOException if a lower-level I/O exception occurs
	 * @since 3.5.0
	 */
	public static InputStream newInputStream(Path path, OpenOption... options) throws IOException {
		Objects.requireNonNull(path, "path cannot be null");
		FileSystem fs = path.getFileSystem();
		if("jar".equals(fs.provider().getScheme())) { //$NON-NLS-1$
			// In practice, Files.copy in ZIP FS copies the file properly, while Files.newInputStream adds nulls
			Path tempFile = Files.createTempFile(BazaarCompilerUtil.class.getSimpleName(), ".bin"); //$NON-NLS-1$
			Files.copy(path, tempFile, StandardCopyOption.REPLACE_EXISTING);
			return Files.newInputStream(tempFile, options);
		} else {
			// Otherwise, just use the normal method
			return Files.newInputStream(path, options);
		}
	}
}
