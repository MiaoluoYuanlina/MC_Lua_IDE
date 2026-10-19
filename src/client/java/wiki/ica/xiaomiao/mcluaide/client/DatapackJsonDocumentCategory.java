package wiki.ica.xiaomiao.mcluaide.client;

import wiki.ica.xiaomiao.mcluaide.editor.DatapackScaffoldCatalog;

/** Infers the editor category from an opened data-pack file, including mod-defined folders. */
final class DatapackJsonDocumentCategory {
	private DatapackJsonDocumentCategory() { }

	static String fromPath(String file) {
		if (file == null) return null;
		String path = file.replace('\\', '/');
		if (path.endsWith("/pack.mcmeta")) return "pack.mcmeta";
		int data = path.indexOf("/data/");
		if (data < 0 || !path.endsWith(".json")) return null;
		String remaining = path.substring(data + 6);
		int namespaceEnd = remaining.indexOf('/');
		if (namespaceEnd < 1) return null;
		remaining = remaining.substring(namespaceEnd + 1);
		int fileEnd = remaining.lastIndexOf('/');
		if (fileEnd < 1) return null;
		String directory = remaining.substring(0, fileEnd);
		return DatapackScaffoldCatalog.jsonType(directory);
	}
}
