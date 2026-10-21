package wiki.ica.xiaomiao.mcluaide.terminal;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/** Finds the shells that can be started on this machine. */
public final class TerminalShells {
	private TerminalShells() { }

	public static boolean windows() { return System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT).contains("win"); }
	private static boolean mac() { return System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT).contains("mac"); }

	/** Shells in preference order; the first is the default. */
	public static List<PtyProcess.Shell> detect(Path directory) {
		List<PtyProcess.Shell> shells = new ArrayList<>();
		if (windows()) {
			String system = System.getenv("SystemRoot") == null ? "C:\\Windows" : System.getenv("SystemRoot");
			Path powershell = Path.of(system, "System32", "WindowsPowerShell", "v1.0", "powershell.exe");
			String pwsh = onPath("pwsh.exe");
			if (pwsh != null) shells.add(new PtyProcess.Shell("PowerShell 7", List.of(pwsh, "-NoLogo"), directory));
			if (Files.isRegularFile(powershell)) shells.add(new PtyProcess.Shell("PowerShell", List.of(powershell.toString(), "-NoLogo"), directory));
			Path cmd = Path.of(system, "System32", "cmd.exe");
			shells.add(new PtyProcess.Shell("Command Prompt", List.of(Files.isRegularFile(cmd) ? cmd.toString() : "cmd.exe"), directory));
			for (String root : new String[]{System.getenv("ProgramFiles"), System.getenv("ProgramW6432")}) {
				if (root == null) continue;
				Path bash = Path.of(root, "Git", "bin", "bash.exe");
				if (Files.isRegularFile(bash) && shells.stream().noneMatch(s -> s.name().equals("Git Bash")))
					shells.add(new PtyProcess.Shell("Git Bash", List.of(bash.toString(), "--login", "-i"), directory));
			}
			Path wsl = Path.of(system, "System32", "wsl.exe");
			if (Files.isRegularFile(wsl)) shells.add(new PtyProcess.Shell("WSL", List.of(wsl.toString()), directory));
		} else {
			String configured = System.getenv("SHELL");
			if (configured != null && Files.isExecutable(Path.of(configured))) shells.add(new PtyProcess.Shell(Path.of(configured).getFileName().toString(), List.of(configured, "-i"), directory));
			for (String candidate : new String[]{"/bin/bash", "/bin/zsh", "/bin/sh"})
				if (Files.isExecutable(Path.of(candidate)) && shells.stream().noneMatch(s -> s.command().getFirst().equals(candidate)))
					shells.add(new PtyProcess.Shell(Path.of(candidate).getFileName().toString(), List.of(candidate, "-i"), directory));
		}
		return shells;
	}

	/** The shell named {@code name} (case-insensitive), or the default when blank or unknown. */
	public static PtyProcess.Shell find(String name, Path directory) {
		List<PtyProcess.Shell> all = detect(directory);
		if (all.isEmpty()) throw new IllegalStateException("没有找到可用的 shell");
		if (name != null && !name.isBlank())
			for (var shell : all) if (shell.name().equalsIgnoreCase(name.strip())) return shell;
		return all.getFirst();
	}

	static String onPath(String executable) {
		String path = System.getenv("PATH");
		if (path == null) return null;
		for (String directory : path.split(File.pathSeparator)) {
			if (directory.isBlank()) continue;
			Path candidate = Path.of(directory, executable);
			if (Files.isRegularFile(candidate)) return candidate.toString();
		}
		return null;
	}

	static boolean mac_() { return mac(); }
}
