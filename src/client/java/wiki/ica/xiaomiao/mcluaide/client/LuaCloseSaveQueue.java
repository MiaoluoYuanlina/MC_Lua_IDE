package wiki.ica.xiaomiao.mcluaide.client;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.function.Function;

/** Wait for each acknowledgement; never attempt later saves or close after a failure. */
final class LuaCloseSaveQueue {
	static <T> CompletableFuture<Void> saveAll(List<T> documents,
			Function<T, CompletableFuture<Void>> save, Executor clientExecutor) {
		CompletableFuture<Void> result = CompletableFuture.completedFuture(null);
		for (T document : documents) result = result.thenComposeAsync(ignored -> save.apply(document), clientExecutor);
		return result;
	}
}
