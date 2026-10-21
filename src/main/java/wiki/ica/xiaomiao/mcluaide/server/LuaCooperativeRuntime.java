package wiki.ica.xiaomiao.mcluaide.server;

import org.luaj.vm2.Globals;
import org.luaj.vm2.LuaError;
import org.luaj.vm2.LuaThread;
import org.luaj.vm2.LuaValue;
import org.luaj.vm2.Varargs;
import org.luaj.vm2.lib.VarArgFunction;

import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

/** Separate virtual stacks, but exactly one flow may access a Lua VM at a time.
 * Only sleep releases the VM permit; neither events nor debugger samples run in parallel.
 * The permit is deliberately not thread-owned: a public LuaJ coroutine may call sleep
 * while its parent's Java thread is waiting inside coroutine.resume(). */
final class LuaCooperativeRuntime implements AutoCloseable {
	private final Globals globals;
	private final LuaValue hook;
	private final boolean debugEnabled;
	private final int hookInterval, capacity;
	private final Runnable checkRuntime;
	private final Consumer<Object> switchContext;
	private final Semaphore vm = new Semaphore(1, true);
	private final Set<Task> tasks = new HashSet<>();
	private final AtomicReference<Throwable> failure = new AtomicReference<>();
	private volatile boolean closed;
	private Task active;

	LuaCooperativeRuntime(Globals globals, LuaValue hook, boolean debugEnabled, int hookInterval,
			int capacity, Runnable checkRuntime, Consumer<Object> switchContext) {
		this.globals = globals;
		this.hook = new VarArgFunction() {
			@Override public Varargs invoke(Varargs args) {
				check();
				return hook.invoke(args);
			}
		};
		this.debugEnabled = debugEnabled;
		this.hookInterval = hookInterval;
		this.capacity = capacity;
		this.checkRuntime = checkRuntime;
		this.switchContext = switchContext;
		installCoroutineHooks();
	}

	boolean submit(Object context, Runnable body) {
		Task task = new Task(context);
		synchronized (tasks) {
			if (closed || failure.get() != null || tasks.size() >= capacity) return false;
			tasks.add(task);
		}
		// Acquire in submission order, then transfer the non-thread-owned permit to
		// the virtual stack. Listener registration order stays deterministic.
		vm.acquireUninterruptibly();
		try { check(); }
		catch (Throwable error) {
			vm.release();
			synchronized (tasks) { tasks.remove(task); tasks.notifyAll(); }
			throw error;
		}
		Thread.startVirtualThread(() -> {
			LuaThread previous = globals.running;
			try {
				activate(task, task.luaThread);
				check();
				body.run();
				check();
			} catch (Throwable error) {
				if (!closed) failure.compareAndSet(null, error);
			} finally {
				globals.running = previous;
				active = null;
				switchContext.accept(null);
				vm.release();
				synchronized (tasks) {
					tasks.remove(task);
					tasks.notifyAll();
				}
			}
		});
		return true;
	}

	void sleepMilliseconds(int milliseconds) {
		if (milliseconds < 0 || milliseconds > 3_600_000)
			throw new LuaError("等待时间必须在 0 到 3600000 毫秒之间");
		check();
		Task task = active;
		if (task == null) throw new LuaError("MC.sleep 只能在当前脚本执行流程中调用");
		LuaThread suspended = globals.running;
		long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(milliseconds);
		active = null;
		vm.release();
		try {
			synchronized (tasks) {
				while (true) {
					check();
					long remaining = deadline - System.nanoTime();
					if (remaining <= 0) break;
					TimeUnit.NANOSECONDS.timedWait(tasks,
							Math.min(remaining, TimeUnit.MILLISECONDS.toNanos(250)));
				}
			}
		} catch (InterruptedException error) {
			Thread.currentThread().interrupt();
			throw new LuaError("等待已中断");
		} finally {
			// Even cancellation must restore VM ownership before Lua unwinds its stack.
			vm.acquireUninterruptibly();
			activate(task, suspended);
		}
		check();
	}

	boolean hasTasks() {
		synchronized (tasks) { return !tasks.isEmpty(); }
	}

	Object currentFlow() { return active; }

	void checkFailure() {
		Throwable error = failure.get();
		if (error instanceof LuaError luaError) throw luaError;
		if (error != null) throw new LuaError(error);
	}

	void whenAvailable(Runnable action) {
		if (!vm.tryAcquire()) return;
		try { action.run(); }
		finally { vm.release(); }
	}

	void cancel() {
		closed = true;
		synchronized (tasks) { tasks.notifyAll(); }
	}

	@Override
	public void close() {
		cancel();
		boolean interrupted = Thread.interrupted();
		synchronized (tasks) {
			while (!tasks.isEmpty()) {
				try { tasks.wait(250); }
				catch (InterruptedException error) { interrupted = true; }
			}
		}
		if (interrupted) Thread.currentThread().interrupt();
	}

	private void activate(Task task, LuaThread thread) {
		globals.running = thread;
		active = task;
		switchContext.accept(task.context);
	}

	private void check() {
		if (closed) throw new LuaError("__MC_LUA_IDE_STOPPED__");
		checkFailure();
		checkRuntime.run();
	}

	private void attachHook(LuaThread thread) {
		thread.state.hookfunc = hook;
		thread.state.hookline = debugEnabled;
		thread.state.hookrtrn = debugEnabled;
		thread.state.hookcount = hookInterval;
	}

	private volatile java.util.function.Consumer<LuaThread> coroutineCreated = thread -> { };
	/** Told about every coroutine the script creates (the debugger lists them while paused). */
	void onCoroutineCreated(java.util.function.Consumer<LuaThread> listener) { coroutineCreated = listener; }

	private void installCoroutineHooks() {
		LuaValue coroutine = globals.get("coroutine");
		if (!coroutine.istable()) return; // Internal flows do not enable the sandboxed library.
		LuaValue create = coroutine.get("create");
		coroutine.set("create", new VarArgFunction() {
			@Override public Varargs invoke(Varargs args) {
				LuaThread thread = create.call(args.checkfunction(1)).checkthread();
				attachHook(thread);
				coroutineCreated.accept(thread);
				return thread;
			}
		});
		coroutine.set("wrap", new VarArgFunction() {
			@Override public Varargs invoke(Varargs args) {
				LuaThread thread = create.call(args.checkfunction(1)).checkthread();
				attachHook(thread);
				coroutineCreated.accept(thread);
				return new VarArgFunction() {
					@Override public Varargs invoke(Varargs values) {
						Varargs result = thread.resume(values);
						if (!result.arg1().toboolean()) throw new LuaError(result.arg(2).tojstring());
						return result.subargs(2);
					}
				};
			}
		});
	}

	private final class Task {
		final Object context;
		final LuaThread luaThread = new LuaThread(globals);
		Task(Object context) {
			this.context = context;
			luaThread.state.status = LuaThread.STATUS_RUNNING;
			attachHook(luaThread);
		}
	}
}
