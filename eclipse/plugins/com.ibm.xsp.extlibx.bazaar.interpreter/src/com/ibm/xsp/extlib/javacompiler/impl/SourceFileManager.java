/*
 * � Copyright IBM Corp. 2013
 * 
 * Licensed under the Apache License, Version 2.0 (the "License"); 
 * you may not use this file except in compliance with the License. 
 * You may obtain a copy of the License at:
 * 
 * http://www.apache.org/licenses/LICENSE-2.0 
 * 
 * Unless required by applicable law or agreed to in writing, software 
 * distributed under the License is distributed on an "AS IS" BASIS, 
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or 
 * implied. See the License for the specific language governing 
 * permissions and limitations under the License.
 */
package com.ibm.xsp.extlib.javacompiler.impl;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.security.AccessController;
import java.security.PrivilegedAction;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.Dictionary;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.jar.JarEntry;
import java.util.jar.JarInputStream;
import java.util.stream.Stream;

import javax.tools.FileObject;
import javax.tools.ForwardingJavaFileManager;
import javax.tools.JavaFileManager;
import javax.tools.JavaFileObject;
import javax.tools.JavaFileObject.Kind;
import javax.tools.StandardLocation;

import org.eclipse.core.runtime.FileLocator;
import org.eclipse.core.runtime.Platform;
import org.eclipse.osgi.util.ManifestElement;
import org.osgi.framework.Bundle;
import org.osgi.framework.BundleException;

import com.ibm.commons.util.StringUtil;
import com.ibm.xsp.extlib.javacompiler.JavaSourceClassLoader;

/**
 * A JavaFileManager for Java source and classes consumed by the compiler.
 * 
 * @author priand
 */
public class SourceFileManager extends ForwardingJavaFileManager<JavaFileManager> implements AutoCloseable {

	private JavaSourceClassLoader classLoader;
	private Map<URI, JavaFileObjectJavaSource> fileObjects=new HashMap<URI, JavaFileObjectJavaSource>();
	private final boolean isOsgi = StringUtil.isNotEmpty(System.getProperty("osgi.framework.vendor"))
	                               || StringUtil.isNotEmpty(System.getProperty("org.osgi.framework.vendor"));
	
	private Collection<String> resolvedClassPath;
	private Set<Path> cleanup = new HashSet<>();
	private Collection<String> nonDelegatingPackages;
	
	private List<JavaFileObjectClass> classPathClasses;

	public SourceFileManager(JavaFileManager fileManager, JavaSourceClassLoader classLoader, String[] classPath, boolean resolve) {
		super(fileManager);
		this.classLoader=classLoader;
		if(resolve) {
			resolvedClassPath = resolveClasspath(classPath);
		} else {
			resolvedClassPath = Arrays.asList(classPath);
		}
	}
	
	public Collection<String> resolveClasspath(final String[] classPath) {
		Set<String> resolvedBundles = new HashSet<>();
		return AccessController.doPrivileged((PrivilegedAction<Collection<String>>)() -> {
			try {
				if(classPath!=null) {
					List<String> resolved = new ArrayList<String>();
					for(String cp : classPath) {
						// Known protocols
						if(cp.startsWith("file:") || cp.startsWith("jar:")) {
							resolved.add(cp);
						} else {
							// Resolve a simple bundle to its URL
							resolveBundle(resolved, resolvedBundles, cp);
						}
					}
					if(resolved.size()>0) {
						return resolved;
					}
				}
			} catch(IOException | BundleException ex) {
				ex.printStackTrace();
			}
			return Collections.emptyList();
		});
	}
	private void resolveBundle(Collection<String> resolved, Set<String> resolvedBundles, String bundleName) throws IOException, BundleException {
		if(!isOsgi) {
			return;
		}
		if(resolvedBundles.contains(bundleName)) {
			return;
		}
		
		Bundle b = org.eclipse.core.runtime.Platform.getBundle(bundleName);
		resolveBundle(resolved, resolvedBundles, b, true);
	}
	private void resolveBundle(Collection<String> resolved, Set<String> resolvedBundles, Bundle b, boolean includeFragments) throws IOException, BundleException {
		if(b!=null) {
			if(resolvedBundles.contains(b.getSymbolicName())) {
				return;
			}
			resolvedBundles.add(b.getSymbolicName());
			
			Path f = FileLocator.getBundleFile(b).toPath();
			
			// If it is a directory, then look inside
			if(Files.isDirectory(f)) {
				// In dev mode, we have a bin/ directory that is not reflected in the Bundle-classpath
				// So we hard code it here
				Path fbin = f.resolve("bin");
				if(Files.isDirectory(fbin)) {
			        String path = fbin.toUri().toString();
			        resolved.add(path);
				}
				Path fmaven = f.resolve("target").resolve("classes");
				if(Files.isDirectory(fmaven)) {
			        String path = fmaven.toUri().toString();
			        resolved.add(path);
				}
				Collection<String> classPath = getBundleClassPath(b,false);
				for(String cp: classPath) {
					if(StringUtil.isEmpty(cp)) {
						continue;
					}
				    Path cpPath = ".".equals(cp) ? f : f.resolve(cp);
				    if(Files.exists(cpPath)) {
				    	//resolveFile(resolved, path);
				        String path = cpPath.toUri().toString();
				        if(path.endsWith(".jar")) {
				            path = "jar:"+path;
				        }
				        resolved.add(path);
				    }
				}
			}
			
			// If it is a file, treat it as a jar file
			if(Files.isRegularFile(f)) {
				Collection<String> classPath = getBundleClassPath(b,includeFragments);
				// Make sure that this jar file is added, as this is not in Bundle-classpath when it is empty
				classPath.add(".");
				for(String cp: classPath) {
					if(StringUtil.isEmpty(cp)) {
						continue;
					}
					if(cp.equals(".")) {
			            String path = "jar:"+f.toUri().toString();
			            resolved.add(path);
			            continue;
					}
					
					// Then extract to a temporary directory
					// Note: b.getResource(cp) doesn't seem to work with dynamically-installed plugins
					try(InputStream is = Files.newInputStream(f)) {
						try(JarInputStream jis = new JarInputStream(is)) {
							JarEntry jarEntry;
							while((jarEntry = jis.getNextJarEntry()) != null) {
								if(cp.equals(jarEntry.getName())) {
									Path tempJar = Files.createTempFile(cp.replace('/', '-'), ".jar");
									cleanup.add(tempJar);
									Files.copy(jis, tempJar, StandardCopyOption.REPLACE_EXISTING);
									String fileUri = tempJar.toUri().toString();
									String url = "jar:" + fileUri;
									resolved.add(url);
									
									break;
								}
							}
						}
					}
				}
			}
			
			// Check the manifest for re-exported dependencies
			String req = b.getHeaders().get("Require-Bundle");
			if(StringUtil.isNotEmpty(req)) {
				ManifestElement[] elements = ManifestElement.parseHeader("Require-Bundle", req);
				for(ManifestElement element : elements) {
					String visibility = element.getDirective("visibility");
					if("reexport".equals(visibility)) {
						Bundle dep = Platform.getBundle(element.getValue());
						if(dep != null) {
							resolveBundle(resolved, resolvedBundles, dep, true);
						}
					}
				}
			}

			// Add the fragments separately as this needs a full File path
			if(includeFragments) {
				Bundle[] fragments = Platform.getFragments(b);
				if(fragments!=null) {
					for(Bundle fragment : fragments) {
						resolveBundle(resolved, resolvedBundles, fragment, false);
					}
				}
			}
		}
	}
	
	/**
	 * @return the fully resolved class path for this file manager
	 */
	public Collection<String> getResolvedClassPath() {
		return Collections.unmodifiableCollection(resolvedClassPath);
	}
    
    private static Collection<String> getBundleClassPath(Bundle b, boolean includeFragments) {
    	// Create a set to make sure that the same path is not added twice
    	// That breaks the order of the class loader, but this should not be a problem.
    	Set<String> classPath = new HashSet<String>();
    	gatherBundleClassPath(classPath, b);
    	if(includeFragments) {
	    	Bundle[] fragments = Platform.getFragments(b);
	    	if(fragments!=null) {
	    		for(int i=0; i<fragments.length; i++) {
	    			gatherBundleClassPath(classPath, fragments[i]);
	    		}
	    	}
    	}
    	return classPath;
    }
    private static void gatherBundleClassPath(Set<String> classPath, Bundle b) {
		Dictionary<String, String> header = b.getHeaders(); // "Bundle-ClassPath"
    	for(Enumeration<String> e=header.keys(); e.hasMoreElements(); ) {
    		String key = e.nextElement();
    		if(key.equals("Bundle-ClassPath")) {
    			String[] values = StringUtil.splitString(header.get(key),',',true);
    			for(int i=0; i<values.length; i++) {
    				String v = values[i];
    				if(StringUtil.isNotEmpty(v)) {
    					classPath.add(v);
    				}
    			}
    		}
    	}
    }
	
	@Override
	public FileObject getFileForInput(Location location, String packageName, String relativeName) throws IOException {
		try {
			URI uri = new URI(location.getName()+'/'+packageName+'/'+relativeName);
			JavaFileObjectJavaSource o=fileObjects.get(uri);
			if(o!=null) {
				return o;
			}
		} catch (URISyntaxException e) {
			throw new IllegalArgumentException(e);
		}
		return super.getFileForInput(location, packageName, relativeName);
	}

	public void addSourceFile(StandardLocation location, String packageName, String relativeName, JavaFileObjectJavaSource file) {
		try {
			URI uri = new URI(location.getName()+'/'+packageName+'/'+relativeName);
			fileObjects.put(uri, file);
		} catch (URISyntaxException e) {
			throw new IllegalArgumentException(e);
		}
	}
	
	/**
	 * Removes the source for the provided class from the file manager, if present
	 * 
	 * @param location the {@link StandardLocation} expected to house the class
	 * @param qualifiedClassName the fully-qualified class name
	 * @since 2.0.4
	 */
	public void purgeSourceFile(StandardLocation location, String qualifiedClassName) {
		try {
			int dotPos=qualifiedClassName.lastIndexOf('.');
			String packageName=dotPos<0 ? "" : qualifiedClassName.substring(0, dotPos);
			String className=dotPos<0 ? qualifiedClassName : qualifiedClassName.substring(dotPos+1);
			String javaName=className+JavaSourceClassLoader.JAVA_EXTENSION;
			URI uri = new URI(location.getName()+'/'+packageName+'/'+javaName);
			fileObjects.remove(uri);
		} catch (URISyntaxException e) {
			throw new IllegalArgumentException(e);
		}
	}

	@Override
	public JavaFileObject getJavaFileForOutput(Location location, String qualifiedName, Kind kind, FileObject outputFile) throws IOException {
		JavaFileObjectJavaCompiled file=new JavaFileObjectJavaCompiled(qualifiedName, kind);
		classLoader.addCompiledFile(qualifiedName, file);
		return file;
	}

	@Override
	public ClassLoader getClassLoader(JavaFileManager.Location location) {
		return classLoader;
	}

	@Override
	public String inferBinaryName(Location loc, JavaFileObject file) {
		if(file instanceof JavaFileObjectClass) {
			return ((JavaFileObjectClass) file).binaryName();
		}
		if(file instanceof JavaFileObjectJavaSource) {
			return file.getName();
		}
		return super.inferBinaryName(loc, file);
	}

	@Override
	public Iterable<JavaFileObject> list(Location location, String packageName, Set<Kind> kinds, boolean recurse) throws IOException {
		ArrayList<JavaFileObject> javaFiles=new ArrayList<JavaFileObject>();
		
		// We add all the file registered into the manager for source access
		if(location==StandardLocation.SOURCE_PATH) {
			if(kinds.contains(JavaFileObject.Kind.SOURCE)) {
				for(JavaFileObject file : fileObjects.values()) {
					if(file.getKind()==Kind.SOURCE && file.getName().startsWith(packageName)) {
						javaFiles.add(file);
					}
				}
			}
		}
		
		// We handle class files for compilation purposes from the classpath, identified by the content class loader
		if(location==StandardLocation.CLASS_PATH) {
			if(kinds.contains(JavaFileObject.Kind.CLASS)) {
				// java.* must come from the SystemClassLoader, so no reason to look
				// from our ClassLoader
				if(!packageName.startsWith("java.")) {
					listPackage(javaFiles,packageName);
				}
			}
		}
		// Particular case for the servlet classes
		if(location==StandardLocation.PLATFORM_CLASS_PATH) {
			if(kinds.contains(JavaFileObject.Kind.CLASS)) {
				if(packageName.startsWith("javax.servlet")) {
					listPackage(javaFiles,packageName);
					return javaFiles;
				}
			}
		}
		
		if(this.nonDelegatingPackages != null) {
			if(this.nonDelegatingPackages.stream().anyMatch(p -> packageName.equals(p) || packageName.startsWith(p+"."))) {
				return javaFiles;
			}
		}

		// And then what comes from the default implementation
		Iterable<JavaFileObject> result=super.list(location, packageName, kinds, recurse);
		if(javaFiles.isEmpty()) {
			return result;
		} else {
			for(JavaFileObject file : result) {
				javaFiles.add(file);
			}
			return javaFiles;
		}
	}
	
	/**
	 * Sets a collection of package prefixes to skip root classloader delegation for. When
	 * packages match this collection, only the configured classpath from the constructor
	 * will be consulted.
	 * 
	 * @param nonDelegatingPackages a {@link Collection} of full or partial package names
	 *      to skip delegation for, or {@code null} to unset
	 * 
	 * @since 2.0.7
	 */
	public void setNonDelegatingPackages(Collection<String> nonDelegatingPackages) {
		if(nonDelegatingPackages == null) {
			this.nonDelegatingPackages = null;
		} else {
			this.nonDelegatingPackages = new HashSet<>(nonDelegatingPackages);
		}
	}
	
	/**
	 * Cleans up any temporary files created during processing.
	 */
	@Override
	public void close() {
		try {
			super.close();
		} catch (IOException e) {
			e.printStackTrace();
		}
		
		this.classPathClasses.forEach(JavaFileObjectClass::close);
		
		for(Path path : cleanup) {
			try {
				deltree(path);
			} catch (IOException e) {
			}
		}
	}
	
	public static void deltree(Path path) throws IOException {
		if(Files.isDirectory(path)) {
			try(Stream<Path> walk = Files.list(path)) {
				walk.forEach(p -> {
					try {
						deltree(p);
					} catch(IOException e) {
						throw new UncheckedIOException(e);
					}
				});
			}
		}
		try {
			Files.deleteIfExists(path);
		} catch(IOException e) {
			// This is likely a Windows file-locking thing
			e.printStackTrace();
		}
	}

	protected synchronized void listPackage(List<JavaFileObject> list, String packageName) throws IOException {
		try {
			if(classPathClasses == null) {
				classPathClasses = new ArrayList<>();
				if(resolvedClassPath != null) {
					for(String path : resolvedClassPath) {
						if(path.startsWith("jar:")) {
							URL url = new URL(path.substring("jar:".length()));
							listJarFile(classPathClasses, url);
						} else {
							Path directory = Paths.get(new URI(path));
							listDirectory(classPathClasses, directory);
						}
					}
				}
			}
			classPathClasses.stream()
				.filter(o -> o.binaryName().startsWith(packageName) && o.binaryName().indexOf('.', packageName.length()+1) == -1)
				.forEach(list::add);
		} catch(URISyntaxException e) {
			throw new IOException(e);
		} catch(IOException e) {
			throw e;
		}
	}

	protected void listDirectory(List<JavaFileObjectClass> list, Path directory) throws IOException {
		try(Stream<Path> dirStream = Files.find(directory, Integer.MAX_VALUE,
			(file, attr) -> 
				attr.isRegularFile() && file.getFileName().toString().endsWith(JavaSourceClassLoader.CLASS_EXTENSION)
			)) {
			
			dirStream.map(path -> {
				String binaryName = removeClassExtension(path.toString()).replace(directory.getFileSystem().getSeparator(), ".");
				return new JavaFileObjectClass(path.toUri(), binaryName);
			})
			.forEach(list::add);
		}	
	}

	/**
	 * @param list the result collection to add to
	 * @param url a URL pointing to a JAR file, e.g. "file:///foo.jar"
	 * @throws IOException if there is a problem reading from the JAR
	 */
	protected void listJarFile(List<JavaFileObjectClass> list, URL url) throws IOException {
		// The jar file may not contain an entry for the package folder explicitly (e.g. Notes.jar),
		//   so look for entries starting with it
		try(InputStream is = url.openStream()) {
			try(JarInputStream jis = new JarInputStream(is)) {
				JarEntry entry;
				while((entry = jis.getNextJarEntry()) != null) {
					String name = entry.getName();
					if(name.endsWith(JavaSourceClassLoader.CLASS_EXTENSION)) {
						try {
							String jarUrl = "jar:" + url + "!/";
							URI uri = new URI(jarUrl + name);
							String binaryName = removeClassExtension(StringUtil.replace(name, '/' , '.'));
							list.add(new JavaFileObjectClass(uri, binaryName));
						} catch (URISyntaxException e) {
							throw new RuntimeException("Exception while extracting class name from jar", e);
						}
					}
				}
			}
		}
	}
	
	private static String removeClassExtension(String s) {
		return s.substring(0, s.length()-JavaSourceClassLoader.CLASS_EXTENSION.length());
	}
}
