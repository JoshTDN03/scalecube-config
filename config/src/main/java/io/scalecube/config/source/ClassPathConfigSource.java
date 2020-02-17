package io.scalecube.config.source;

import io.scalecube.config.ConfigProperty;
import io.scalecube.config.utils.ThrowableUtil;
import java.io.File;
import java.io.IOException;
import java.net.MalformedURLException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.FileSystem;
import java.nio.file.FileSystems;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.Enumeration;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.StringJoiner;
import java.util.TreeMap;
import java.util.function.Predicate;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.stream.Collectors;

public final class ClassPathConfigSource extends FilteredPathConfigSource {
  private static final String CLASSPATH = System.getProperty("java.class.path");
  private static final String PATH_SEPARATOR = System.getProperty("path.separator");

  private Map<String, ConfigProperty> loadedConfig;

  /**
   * Constructor.
   *
   * @param predicate predicate to match configuration files
   */
  public ClassPathConfigSource(Predicate<Path> predicate) {
    this(Collections.singletonList(predicate));
  }

  /**
   * Constructor.
   *
   * @param predicates list of predicates to match configuration files
   */
  public ClassPathConfigSource(List<Predicate<Path>> predicates) {
    super(predicates);
  }

  /**
   * Factory method to create {@code ClassPathConfigSource} instance using filename plus its
   * prefixes.
   *
   * @param filename filename for template of configuration property file
   * @param prefixes list of prefixes (comma separated list of strings)
   * @return new {@code ClassPathConfigSource} instance
   */
  public static ClassPathConfigSource createWithPattern(String filename, List<String> prefixes) {
    Objects.requireNonNull(filename, "ClassPathConfigSource: filename is required");
    Objects.requireNonNull(prefixes, "ClassPathConfigSource: prefixes is required");
    return new ClassPathConfigSource(preparePatternPredicates(filename, prefixes));
  }

  @Override
  public Map<String, ConfigProperty> loadConfig() {
    if (loadedConfig != null) {
      return loadedConfig;
    }

    Collection<Path> pathCollection = new ArrayList<>();
    getClassPathEntries(getClass().getClassLoader()).stream()
        .filter(uri -> uri.getScheme().equals("file"))
        .forEach(
            uri -> {
              File file = new File(uri);
              if (file.exists()) {
                try {
                  if (file.isDirectory()) {
                    scanDirectory(file, "", Collections.emptySet(), pathCollection);
                  } else {
                    scanJar(file, pathCollection);
                  }
                } catch (Exception e) {
                  throw ThrowableUtil.propagate(e);
                }
              }
            });

    Map<String, ConfigProperty> result = new TreeMap<>();
    filterAndCollectInOrder(
        predicates.iterator(),
        loadConfigMap(pathCollection),
        (path, map) ->
            map.entrySet()
                .forEach(
                    entry ->
                        result.putIfAbsent(
                            entry.getKey(),
                            LoadedConfigProperty.withNameAndValue(entry)
                                .origin(path.toString())
                                .build())));
    return loadedConfig = result;
  }

  private static Collection<URI> getClassPathEntries(ClassLoader classloader) {
    Collection<URI> entries = new LinkedHashSet<>();
    ClassLoader parent = classloader.getParent();
    if (parent != null) {
      entries.addAll(getClassPathEntries(parent));
    }
    for (URL url : getClassLoaderUrls(classloader)) {
      if (url.getProtocol().equals("file")) {
        entries.add(toFile(url).toURI());
      }
    }
    return new LinkedHashSet<>(entries);
  }

  private static File toFile(URL url) {
    if (!url.getProtocol().equals("file")) {
      throw new IllegalArgumentException("Unsupported protocol in url: " + url);
    }
    try {
      return new File(url.toURI());
    } catch (URISyntaxException e) {
      return new File(url.getPath());
    }
  }

  private static Collection<URL> getClassLoaderUrls(ClassLoader classloader) {
    if (classloader instanceof URLClassLoader) {
      return Arrays.stream(((URLClassLoader) classloader).getURLs()).collect(Collectors.toSet());
    }
    if (classloader.equals(ClassLoader.getSystemClassLoader())) {
      return parseJavaClassPath();
    }
    return Collections.emptySet();
  }

  private static Collection<URL> parseJavaClassPath() {
    Collection<URL> urls = new LinkedHashSet<>();
    for (String entry : CLASSPATH.split(PATH_SEPARATOR)) {
      try {
        try {
          urls.add(new File(entry).toURI().toURL());
        } catch (SecurityException e) {
          urls.add(new URL("file", null, new File(entry).getAbsolutePath()));
        }
      } catch (MalformedURLException ex) {
        throw ThrowableUtil.propagate(ex);
      }
    }
    return new LinkedHashSet<>(urls);
  }

  private static void scanDirectory(
      File directory, String prefix, Set<File> ancestors, Collection<Path> collector)
      throws IOException {
    File canonical = directory.getCanonicalFile();
    if (ancestors.contains(canonical)) {
      return;
    }
    File[] files = directory.listFiles();
    if (files == null) {
      return;
    }
    Set<File> objects = new LinkedHashSet<>(ancestors);
    objects.add(canonical);
    Set<File> newAncestors = Collections.unmodifiableSet(objects);
    for (File f : files) {
      String name = f.getName();
      if (f.isDirectory()) {
        scanDirectory(f, prefix + name + "/", newAncestors, collector);
      } else {
        collector.add(f.toPath());
      }
    }
  }

  private static void scanJar(File file, Collection<Path> collector) throws IOException {
    JarFile jarFile;
    try {
      jarFile = new JarFile(file);
    } catch (IOException ignore) {
      return;
    }
    try (FileSystem zipfs = FileSystems.newFileSystem(file.toPath(), null)) {
      Enumeration<JarEntry> entries = jarFile.entries();
      while (entries.hasMoreElements()) {
        JarEntry entry = entries.nextElement();
        collector.add(zipfs.getPath(entry.getName()));
      }
    } finally {
      try {
        jarFile.close();
      } catch (IOException ignore) {
        // ignore
      }
    }
  }

  @Override
  public String toString() {
    return new StringJoiner(", ", ClassPathConfigSource.class.getSimpleName() + "[", "]")
        .add("classLoader=" + getClass().getClassLoader())
        .toString();
  }
}
