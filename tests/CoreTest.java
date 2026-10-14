import java.nio.file.*;
import java.net.*;
import javax.tools.ToolProvider;

/** Runs without Gradle or an Android SDK; requires a JDK compiler module. */
class CoreTest {
    public static void main(String[] args) throws Exception {
        var compiler = ToolProvider.getSystemJavaCompiler();
        if (compiler == null) throw new IllegalStateException("A full JDK is required");
        Path output = Files.createTempDirectory("adbox-core-checks-");
        String dir = "app/src/main/java/dev/adbox/toolkit/";
        int result = compiler.run(null, System.out, System.err, "-source", "17", "-target", "17", "-Xlint:-options", "-d", output.toString(),
            dir+"AdbChannel.java", dir+"AdbIdentity.java", dir+"AdbWireConnection.java", dir+"UsbAdbOutputStream.java", dir+"QueuedUsbTransport.java", dir+"SyncTransfer.java", dir+"AdbResponses.java", dir+"FastbootClient.java", dir+"FastbootDiagnostics.java", dir+"FastbootImageSelection.java", dir+"ScreenProtocol.java", dir+"ScreenInputQueue.java", dir+"SideloadClient.java", dir+"ShellV2.java", dir+"TerminalBuffer.java", dir+"SparseImage.java", dir+"TarSafety.java", "tests/CoreChecks.java", "tests/FastbootChecks.java", "tests/FastbootDiagnosticsChecks.java", "tests/AdvancedChecks.java", "tests/UsbChecks.java", "tests/QueuedUsbChecks.java", "tests/ScreenChecks.java", "tests/OutputChecks.java");
        if (result != 0) throw new IllegalStateException("Core compilation failed: " + result);
        try(var loader = new URLClassLoader(new URL[]{output.toUri().toURL()})) {
            loader.loadClass("CoreChecks").getMethod("main", String[].class).invoke(null, (Object)new String[0]);
            loader.loadClass("FastbootChecks").getMethod("main", String[].class).invoke(null, (Object)new String[0]);
            loader.loadClass("FastbootDiagnosticsChecks").getMethod("main", String[].class).invoke(null, (Object)new String[0]);
            loader.loadClass("ScreenChecks").getMethod("main", String[].class).invoke(null, (Object)new String[0]);
            loader.loadClass("dev.adbox.toolkit.OutputChecks").getMethod("main", String[].class).invoke(null, (Object)new String[0]);
            loader.loadClass("AdvancedChecks").getMethod("main", String[].class).invoke(null, (Object)new String[0]);
            loader.loadClass("UsbChecks").getMethod("main", String[].class).invoke(null, (Object)new String[0]);
            loader.loadClass("QueuedUsbChecks").getMethod("main", String[].class).invoke(null, (Object)new String[0]);
        } finally {
            try(var paths=Files.walk(output)){paths.sorted(java.util.Comparator.reverseOrder()).forEach(p->{try{Files.deleteIfExists(p);}catch(Exception ignored){}});}
        }
    }
}
