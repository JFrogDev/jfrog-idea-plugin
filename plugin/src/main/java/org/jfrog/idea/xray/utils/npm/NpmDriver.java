package org.jfrog.idea.xray.utils.npm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectReader;
import com.google.common.collect.Lists;
import org.apache.commons.lang.StringUtils;
import org.jfrog.idea.xray.utils.Utils;

import java.io.File;
import java.io.IOException;
import java.util.List;

import static org.jfrog.idea.xray.utils.Utils.readStream;

/**
 * Created by Yahav Itzhak on 17 Dec 2017.
 */
public class NpmDriver {

    private static ObjectReader jsonReader = new ObjectMapper().reader();

    /**
     * Execute a npm command in the current directory.
     * @param args - Command arguments
     * @return NpmCommandRes
     */
    private static NpmCommandRes exeNpmCommand(List<String> args) throws InterruptedException, IOException {
        File execDir = new File(".");
        return exeNpmCommand(execDir, args);
    }

    /**
     * Execute a npm command.
     * @param execDir - The execution dir (Usually path to project)
     * @param args - Command arguments
     * @return NpmCommandRes
     */
    private static NpmCommandRes exeNpmCommand(File execDir, List<String> args) throws InterruptedException, IOException {
        args.add(0, "npm");
        Process process = null;
        try {
            NpmCommandRes npmCommandRes = new NpmCommandRes();
            process = Utils.exeCommand(execDir, args);
            npmCommandRes.res = readStream(process.getInputStream());
            if (process.waitFor() != 0) {
                npmCommandRes.err = readStream(process.getErrorStream());
            }
            return npmCommandRes;
        } finally {
            if (process != null) {
                process.getInputStream().close();
                process.getErrorStream().close();
                process.getOutputStream().close();
            }
        }
    }

    public static boolean isNpmInstalled() {
        List<String> args = Lists.newArrayList("version");
        try {
            NpmCommandRes npmCommandRes = exeNpmCommand(args);
            return npmCommandRes.isOk();
        } catch (IOException|InterruptedException e) {
            return false;
        }
    }

    public void install(String appDir) throws IOException {
        try {
            File execDir = new File(appDir);
            List<String> args = Lists.newArrayList("install", "--only=production");
            NpmCommandRes npmCommandRes = exeNpmCommand(execDir, args);
            if (!npmCommandRes.isOk()) {
                throw new IOException(npmCommandRes.err);
            }
        } catch (IOException|InterruptedException e) {
            throw new IOException("'npm install' failed", e);
        }
    }

    public JsonNode list(String appDir) throws IOException {
        File execDir = new File(appDir);
        List<String> args = Lists.newArrayList("ls", "--json");
        try {
            NpmCommandRes npmCommandRes = exeNpmCommand(execDir, args);
            return jsonReader.readTree(npmCommandRes.res);
        } catch (IOException|InterruptedException e) {
            throw new IOException("'npm ls' failed", e);
        }
    }

    private static class NpmCommandRes {
        String res;
        String err;

        private boolean isOk() {
            return StringUtils.isBlank(err);
        }
    }
}