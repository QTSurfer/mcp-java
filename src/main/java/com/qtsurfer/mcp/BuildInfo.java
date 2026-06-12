package com.qtsurfer.mcp;

import java.io.IOException;
import java.io.InputStream;
import java.util.Properties;

/**
 * Build-time metadata loaded from {@code build.properties}, which is produced by Maven
 * resource filtering (version) and git-commit-id-maven-plugin (commit hash, build time).
 *
 * <p>Falls back gracefully when the file is absent or a property was not expanded
 * (e.g. running directly from IDE source without a Maven build).
 */
public final class BuildInfo {

    public static final String VERSION;
    public static final String GIT_COMMIT;
    public static final String BUILD_TIME;

    static {
        Properties props = new Properties();
        try (InputStream in = BuildInfo.class.getClassLoader()
                .getResourceAsStream("build.properties")) {
            if (in != null) props.load(in);
        } catch (IOException ignored) {}
        VERSION    = resolve(props, "build.version",    "dev");
        GIT_COMMIT = resolve(props, "build.git.commit", "dev");
        BUILD_TIME = resolve(props, "build.time",       "");
    }

    private BuildInfo() {}

    /** Returns the property value, or {@code fallback} if absent or still a Maven placeholder. */
    private static String resolve(Properties props, String key, String fallback) {
        String val = props.getProperty(key, fallback);
        return (val == null || val.startsWith("${")) ? fallback : val;
    }
}
