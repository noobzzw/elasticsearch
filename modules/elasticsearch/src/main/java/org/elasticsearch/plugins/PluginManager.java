package org.elasticsearch.plugins;

import org.elasticsearch.Version;
import org.elasticsearch.common.collect.Tuple;
import org.elasticsearch.common.http.client.HttpDownloadHelper;
import org.elasticsearch.common.io.FileSystemUtils;
import org.elasticsearch.common.io.Streams;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.env.Environment;
import org.elasticsearch.node.internal.InternalSettingsPerparer;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.net.URL;
import java.util.Enumeration;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import static org.elasticsearch.common.settings.ImmutableSettings.Builder.*;

/**
 * @author kimchy (shay.banon)
 */
public class PluginManager {

    private final Environment environment;

    private String url;

    public PluginManager(Environment environment, String url) {
        this.environment = environment;
        this.url = url;
    }

    public void downloadAndExtract(String name) throws IOException {
        HttpDownloadHelper downloadHelper = new HttpDownloadHelper();

        File pluginFile = new File(url + "/" + name + "/elasticsearch-" + name + "-" + Version.number() + ".zip");
        boolean downloaded = false;
        String filterZipName = null;
        if (!pluginFile.exists()) {
            pluginFile = new File(url + "/elasticsearch-" + name + "-" + Version.number() + ".zip");
            if (!pluginFile.exists()) {
                pluginFile = new File(environment.pluginsFile(), name + ".zip");
                if (url != null) {
                    URL pluginUrl = new URL(url);
                    System.out.println("Trying " + pluginUrl.toExternalForm() + "...");
                    try {
                        downloadHelper.download(pluginUrl, pluginFile, new HttpDownloadHelper.VerboseProgress(System.out));
                        downloaded = true;
                    } catch (IOException e) {
                        // ignore
                    }
                } else {
                    url = "http://elasticsearch.googlecode.com/svn/plugins";
                }
                if (!downloaded) {
                    if (name.indexOf('/') != -1) {
                        // github repo
                        String[] elements = name.split("/");
                        String userName = elements[0];
                        String repoName = elements[1];
                        String version = null;
                        if (elements.length > 2) {
                            version = elements[2];
                        }
                        filterZipName = userName + "-" + repoName;
                        // the installation file should not include the userName, just the repoName
                        name = repoName;
                        if (name.startsWith("elasticsearch-")) {
                            // remove elasticsearch- prefix
                            name = name.substring("elasticsearch-".length());
                        } else if (name.startsWith("es-")) {
                            // remove es- prefix
                            name = name.substring("es-".length());
                        }
                        pluginFile = new File(environment.pluginsFile(), name + ".zip");
                        if (version == null) {
                            // try with ES version from downloads
                            URL pluginUrl = new URL("http://github.com/downloads/" + userName + "/" + repoName + "/" + repoName + "-" + Version.number() + ".zip");
                            System.out.println("Trying " + pluginUrl.toExternalForm() + "...");
                            try {
                                downloadHelper.download(pluginUrl, pluginFile, new HttpDownloadHelper.VerboseProgress(System.out));
                                downloaded = true;
                            } catch (IOException e) {
                                // try a tag with ES version
                                pluginUrl = new URL("http://github.com/" + userName + "/" + repoName + "/zipball/v" + Version.number());
                                System.out.println("Trying " + pluginUrl.toExternalForm() + "...");
                                try {
                                    downloadHelper.download(pluginUrl, pluginFile, new HttpDownloadHelper.VerboseProgress(System.out));
                                    downloaded = true;
                                } catch (IOException e1) {
                                    // download master
                                    pluginUrl = new URL("http://github.com/" + userName + "/" + repoName + "/zipball/master");
                                    System.out.println("Trying " + pluginUrl.toExternalForm() + "...");
                                    try {
                                        downloadHelper.download(pluginUrl, pluginFile, new HttpDownloadHelper.VerboseProgress(System.out));
                                        downloaded = true;
                                    } catch (IOException e2) {
                                        // ignore
                                    }
                                }
                            }
                        } else {
                            // download explicit version
                            URL pluginUrl = new URL("http://github.com/downloads/" + userName + "/" + repoName + "/" + repoName + "-" + version + ".zip");
                            System.out.println("Trying " + pluginUrl.toExternalForm() + "...");
                            try {
                                downloadHelper.download(pluginUrl, pluginFile, new HttpDownloadHelper.VerboseProgress(System.out));
                                downloaded = true;
                            } catch (IOException e) {
                                // try a tag with ES version
                                pluginUrl = new URL("http://github.com/" + userName + "/" + repoName + "/zipball/v" + version);
                                System.out.println("Trying " + pluginUrl.toExternalForm() + "...");
                                try {
                                    downloadHelper.download(pluginUrl, pluginFile, new HttpDownloadHelper.VerboseProgress(System.out));
                                    downloaded = true;
                                } catch (IOException e1) {
                                    // ignore
                                }
                            }
                        }
                    } else {
                        URL pluginUrl = new URL(url + "/" + name + "/elasticsearch-" + name + "-" + Version.number() + ".zip");
                        System.out.println("Trying " + pluginUrl.toExternalForm() + "...");
                        try {
                            downloadHelper.download(pluginUrl, pluginFile, new HttpDownloadHelper.VerboseProgress(System.out));
                            downloaded = true;
                        } catch (IOException e) {
                            // ignore
                        }
                    }
                }
            } else {
                System.out.println("Using plugin from local fs: " + pluginFile.getAbsolutePath());
                downloaded = true;
            }
        } else {
            System.out.println("Using plugin from local fs: " + pluginFile.getAbsolutePath());
            downloaded = true;
        }

        if (!downloaded) {
            throw new IOException("failed to download");
        }

        // extract the plugin
        File extractLocation = new File(environment.pluginsFile(), name);
        ZipFile zipFile = null;
        try {
            zipFile = new ZipFile(pluginFile);
            Enumeration<? extends ZipEntry> zipEntries = zipFile.entries();
            while (zipEntries.hasMoreElements()) {
                ZipEntry zipEntry = zipEntries.nextElement();
                if (zipEntry.isDirectory()) {
                    continue;
                }
                String zipName = zipEntry.getName().replace('\\', '/');
                if (filterZipName != null) {
                    if (zipName.startsWith(filterZipName)) {
                        zipName = zipName.substring(zipName.indexOf('/'));
                    }
                }
                File target = new File(extractLocation, zipName);
                target.getParentFile().mkdirs();
                Streams.copy(zipFile.getInputStream(zipEntry), new FileOutputStream(target));
            }
        } catch (Exception e) {
            System.err.println("failed to extract plugin [" + pluginFile + "]");
        } finally {
            if (zipFile != null) {
                try {
                    zipFile.close();
                } catch (IOException e) {
                    // ignore
                }
            }
            pluginFile.delete();
        }

        // try and identify the plugin type, see if it has no .class or .jar files in it
        // so its probably a _site, and it it does not have a _site in it, move everything to _site
        if (!new File(extractLocation, "_site").exists()) {
            if (!FileSystemUtils.hasExtensions(extractLocation, ".class", ".jar")) {
                System.out.println("Identified as a _site plugin, moving to _site structure ...");
                File site = new File(extractLocation, "_site");
                File tmpLocation = new File(environment.pluginsFile(), name + ".tmp");
                extractLocation.renameTo(tmpLocation);
                extractLocation.mkdirs();
                tmpLocation.renameTo(site);
            }
        }

        System.out.println("Installed " + name);
    }

    public void removePlugin(String name) throws IOException {
        File pluginToDelete = new File(environment.pluginsFile(), name);
        if (pluginToDelete.exists()) {
            FileSystemUtils.deleteRecursively(pluginToDelete, true);
        }
        pluginToDelete = new File(environment.pluginsFile(), name + ".zip");
        if (pluginToDelete.exists()) {
            pluginToDelete.delete();
        }
    }

    public static void main(String[] args) {
        Tuple<Settings, Environment> initialSettings = InternalSettingsPerparer.prepareSettings(EMPTY_SETTINGS, true);

        if (!initialSettings.v2().pluginsFile().exists()) {
            initialSettings.v2().pluginsFile().mkdirs();
        }

        String url = null;
        for (int i = 0; i < args.length; i++) {
            if ("url".equals(args[i]) || "-url".equals(args[i])) {
                url = args[i + 1];
                break;
            }
        }

        PluginManager pluginManager = new PluginManager(initialSettings.v2(), url);

        if (args.length < 1) {
            System.out.println("Usage:");
            System.out.println("    -url     [plugins location]  : Set URL to download plugins from");
            System.out.println("    -install [plugin name]       : Downloads and installs listed plugins");
            System.out.println("    -remove  [plugin name]       : Removes listed plugins");
        }
        for (int c = 0; c < args.length; c++) {
            String command = args[c];
            if (command.equals("install") || command.equals("-install")) {
                String pluginName = args[++c];
                System.out.println("-> Installing " + pluginName + "...");
                try {
                    pluginManager.downloadAndExtract(pluginName);
                } catch (IOException e) {
                    System.out.println("Failed to install " + pluginName + ", reason: " + e.getMessage());
                }
            } else if (command.equals("remove") || command.equals("-remove")) {
                String pluginName = args[++c];
                System.out.println("-> Removing " + pluginName + " ");
                try {
                    pluginManager.removePlugin(pluginName);
                } catch (IOException e) {
                    System.out.println("Failed to remove " + pluginName + ", reason: " + e.getMessage());
                }
            } else {
                // not install or remove, continue
                c++;
            }
        }
    }
}
