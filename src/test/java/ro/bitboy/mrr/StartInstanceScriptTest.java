package ro.bitboy.mrr;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@code --refresh} used to attach to an existing tmux session and never copy
 * {@code target/mrr.jar}. The running JVM would stay on the old artifact.
 */
class StartInstanceScriptTest {

    @TempDir
    Path tmp;

    @Test
    void refreshCopiesJarAndRestartsAnExistingSession() throws Exception {
        Fixture fx = fixture("OLD-JAR", true);

        Result result = run(fx, "--refresh");

        assertThat(result.exit()).isZero();
        assertThat(Files.readString(fx.instanceHome().resolve("mrr.jar"))).isEqualTo("NEW-JAR");
        assertThat(result.log()).contains("tmux kill-session -t mrr-test");
        assertThat(result.log()).contains("tmux new-session");
        assertThat(result.stdout()).contains("restarting tmux session 'mrr-test'");
        assertThat(result.stdout()).doesNotContain("already running");
    }

    @Test
    void seedCopiesTheCheckoutDbWithSqliteBackupNotCp() throws Exception {
        String script = Files.readString(Path.of("scripts/start-instance.sh"));
        assertThat(script).contains(".backup");
        assertThat(script).doesNotContain("cp -f \"$ROOT/data/mrr.db\"");
        assertThat(script).contains("without dropping WAL");
    }

    @Test
    void attachSkipsCopyWhenSessionExistsWithoutRefresh() throws Exception {
        Fixture fx = fixture("OLD-JAR", true);

        Result result = run(fx);

        assertThat(result.exit()).isZero();
        assertThat(Files.readString(fx.instanceHome().resolve("mrr.jar"))).isEqualTo("OLD-JAR");
        assertThat(result.log()).doesNotContain("kill-session");
        assertThat(result.log()).doesNotContain("new-session");
        assertThat(result.stdout()).contains("already running");
    }

    private Fixture fixture(String existingJar, boolean sessionAlive) throws IOException {
        Path root = tmp.resolve("repo");
        Path bin = tmp.resolve("bin");
        Path instance = tmp.resolve("instance");
        Path state = tmp.resolve("state");
        Files.createDirectories(root.resolve("scripts"));
        Files.createDirectories(root.resolve("target"));
        Files.createDirectories(bin);
        Files.createDirectories(instance.resolve("data"));
        Files.createDirectories(instance.resolve("logs"));
        Files.createDirectories(state);

        Path script = root.resolve("scripts/start-instance.sh");
        Files.copy(Path.of("scripts/start-instance.sh"), script);
        script.toFile().setExecutable(true);
        Files.writeString(root.resolve("target/mrr.jar"), "NEW-JAR");
        Files.writeString(instance.resolve("mrr.jar"), existingJar);

        Path sessionFlag = state.resolve("session-alive");
        if (sessionAlive) {
            Files.writeString(sessionFlag, "1");
        }
        Path log = state.resolve("stub.log");
        Files.writeString(log, "");

        writeStub(bin.resolve("tmux"), """
                #!/usr/bin/env bash
                echo "tmux $*" >> "$STUB_LOG"
                case "$1" in
                  has-session)
                    [[ -f "$SESSION_ALIVE" ]]; exit
                    ;;
                  kill-session)
                    rm -f "$SESSION_ALIVE"
                    exit 0
                    ;;
                  *)
                    exit 0
                    ;;
                esac
                """);
        writeStub(bin.resolve("java"), "#!/usr/bin/env bash\necho \"java $*\" >> \"$STUB_LOG\"\n");
        writeStub(bin.resolve("cloudflared"), "#!/usr/bin/env bash\necho \"cloudflared $*\" >> \"$STUB_LOG\"\n");
        writeStub(bin.resolve("curl"), "#!/usr/bin/env bash\necho \"curl $*\" >> \"$STUB_LOG\"\n");
        writeStub(bin.resolve("lsof"), "#!/usr/bin/env bash\necho \"lsof $*\" >> \"$STUB_LOG\"\nexit 1\n");
        writeStub(bin.resolve("open"), "#!/usr/bin/env bash\necho \"open $*\" >> \"$STUB_LOG\"\n");

        return new Fixture(root, bin, instance, sessionFlag, log, script);
    }

    private Result run(Fixture fx, String... args) throws Exception {
        List<String> cmd = new ArrayList<>();
        cmd.add(fx.script().toString());
        cmd.addAll(List.of(args));
        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.redirectErrorStream(true);
        Map<String, String> env = pb.environment();
        env.put("PATH", fx.bin() + ":/usr/bin:/bin");
        env.put("MRR_INSTANCE_HOME", fx.instanceHome().toString());
        env.put("MRR_INSTANCE_PORT", "18131");
        env.put("MRR_TMUX_SESSION", "mrr-test");
        env.put("STUB_LOG", fx.log().toString());
        env.put("SESSION_ALIVE", fx.sessionAlive().toString());
        Process p = pb.start();
        String stdout = new String(p.getInputStream().readAllBytes());
        int exit = p.waitFor();
        return new Result(exit, stdout, Files.readString(fx.log()));
    }

    private static void writeStub(Path path, String body) throws IOException {
        Files.writeString(path, body);
        try {
            Files.setPosixFilePermissions(path, PosixFilePermissions.fromString("rwxr-xr-x"));
        } catch (UnsupportedOperationException ignored) {
            path.toFile().setExecutable(true);
        }
    }

    private record Fixture(Path root, Path bin, Path instanceHome, Path sessionAlive, Path log, Path script) {}

    private record Result(int exit, String stdout, String log) {}
}
