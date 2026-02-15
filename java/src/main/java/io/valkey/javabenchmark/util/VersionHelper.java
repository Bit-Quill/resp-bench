/*
 * Copyright 2025 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.valkey.javabenchmark.util;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.InputStream;
import java.net.URL;
import java.util.Properties;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Utility class for detecting library versions at runtime.
 * 
 * <p>Uses Maven's pom.properties file embedded in JAR artifacts to reliably
 * detect versions, as Package.getImplementationVersion() often returns null.</p>
 *
 * @author Ilia Kolominsky
 */
public final class VersionHelper {
    
    private static final Logger logger = LoggerFactory.getLogger(VersionHelper.class);
    
    private VersionHelper() {
        // Utility class
    }
    
    /**
     * Get the version of a library from its Maven pom.properties file.
     * 
     * <p>Maven automatically generates pom.properties in META-INF/maven/{groupId}/{artifactId}/
     * for all artifacts. This method reads that file to get the accurate version.</p>
     * 
     * @param clazz a class from the library (used to locate the JAR/classpath)
     * @param groupId the Maven groupId (e.g., "redis.clients")
     * @param artifactId the Maven artifactId (e.g., "jedis")
     * @return the version string, or "unknown" if not found
     */
    public static String getVersion(Class<?> clazz, String groupId, String artifactId) {
        // Try pom.properties first (most reliable)
        String version = getVersionFromPomProperties(clazz, groupId, artifactId);
        if (version != null) {
            return version;
        }
        
        // Fallback: extract version from JAR filename/classpath
        version = getVersionFromJarFilename(clazz, artifactId);
        if (version != null) {
            return version;
        }
        
        // Fallback: read from build-time driver-versions.properties
        version = getVersionFromBuildProperties(artifactId);
        if (version != null) {
            return version;
        }
        
        // Fallback to Package implementation version
        version = getVersionFromPackage(clazz);
        if (version != null) {
            return version;
        }
        
        logger.warn("Could not determine version for {}/{} (class: {})", groupId, artifactId, clazz.getName());
        return "unknown";
    }
    
    /**
     * Read version from META-INF/maven/{groupId}/{artifactId}/pom.properties
     */
    private static String getVersionFromPomProperties(Class<?> clazz, String groupId, String artifactId) {
        String resourcePath = String.format("/META-INF/maven/%s/%s/pom.properties", groupId, artifactId);
        
        try (InputStream is = clazz.getResourceAsStream(resourcePath)) {
            if (is != null) {
                Properties props = new Properties();
                props.load(is);
                String version = props.getProperty("version");
                if (version != null && !version.isEmpty()) {
                    logger.trace("Found version {} for {}/{} from pom.properties", version, groupId, artifactId);
                    return version;
                }
            }
        } catch (Exception e) {
            logger.trace("Failed to read pom.properties for {}/{}: {}", groupId, artifactId, e.getMessage());
        }
        
        return null;
    }
    
    /**
     * Extract version from JAR filename.
     * Works for JARs like: valkey-glide-2.2.5-linux-x86_64.jar → 2.2.5
     */
    private static String getVersionFromJarFilename(Class<?> clazz, String artifactId) {
        // First try protection domain
        try {
            URL location = clazz.getProtectionDomain().getCodeSource().getLocation();
            if (location != null) {
                String path = location.getPath();
                String version = extractVersionFromPath(path, artifactId);
                if (version != null) {
                    return version;
                }
            }
        } catch (Exception e) {
            logger.trace("Failed protection domain approach for {}: {}", artifactId, e.getMessage());
        }
        
        // Fallback: scan classpath for matching JAR
        try {
            String classpath = System.getProperty("java.class.path", "");
            for (String entry : classpath.split(System.getProperty("path.separator"))) {
                String version = extractVersionFromPath(entry, artifactId);
                if (version != null) {
                    return version;
                }
            }
        } catch (Exception e) {
            logger.trace("Failed classpath scan for {}: {}", artifactId, e.getMessage());
        }
        
        return null;
    }
    
    /**
     * Extract version from a path that may contain artifactId-version.
     */
    private static String extractVersionFromPath(String path, String artifactId) {
        if (path == null || !path.contains(artifactId)) {
            return null;
        }
        
        // Pattern: artifactId-version[-classifier]
        // e.g., valkey-glide-2.2.5-linux-x86_64.jar → 2.2.5
        Pattern pattern = Pattern.compile(Pattern.quote(artifactId) + "-(\\d+\\.\\d+\\.\\d+(?:-rc\\d+)?)", Pattern.CASE_INSENSITIVE);
        Matcher matcher = pattern.matcher(path);
        if (matcher.find()) {
            String version = matcher.group(1);
            logger.debug("Found version {} from path {} for {}", version, path, artifactId);
            return version;
        }
        return null;
    }
    
    /** Cached build-time driver versions */
    private static Properties buildVersionsCache;
    
    /**
     * Read version from build-time driver-versions.properties.
     * These versions are filtered from pom.xml during Maven build.
     */
    private static String getVersionFromBuildProperties(String artifactId) {
        try {
            if (buildVersionsCache == null) {
                buildVersionsCache = new Properties();
                try (InputStream is = VersionHelper.class.getResourceAsStream("/driver-versions.properties")) {
                    if (is != null) {
                        buildVersionsCache.load(is);
                    }
                }
            }
            String version = buildVersionsCache.getProperty(artifactId + ".version");
            if (version != null && !version.isEmpty() && !version.startsWith("${")) {
                logger.debug("Found version {} for {} from driver-versions.properties", version, artifactId);
                return version;
            }
        } catch (Exception e) {
            logger.trace("Failed to read driver-versions.properties: {}", e.getMessage());
        }
        return null;
    }
    
    /**
     * Read version from Package.getImplementationVersion()
     */
    private static String getVersionFromPackage(Class<?> clazz) {
        try {
            Package pkg = clazz.getPackage();
            if (pkg != null) {
                String version = pkg.getImplementationVersion();
                if (version != null && !version.isEmpty()) {
                    logger.trace("Found version {} from package for {}", version, clazz.getName());
                    return version;
                }
            }
        } catch (Exception e) {
            logger.trace("Failed to get package version for {}: {}", clazz.getName(), e.getMessage());
        }
        
        return null;
    }
}
