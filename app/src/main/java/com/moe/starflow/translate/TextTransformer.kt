package com.moe.starflow.translate

import com.moe.starflow.utils.LogCollector
import org.mozilla.javascript.ClassShutter
import org.mozilla.javascript.Context
import org.mozilla.javascript.ContextFactory
import org.mozilla.javascript.Function
import org.mozilla.javascript.Scriptable
import org.mozilla.javascript.Undefined
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.locks.ReentrantLock

/**
 * 用户脚本执行器：把一段 JavaScript 函数体（形如 `return s;`）作用到一个字符串上。
 *
 * 设计约束（改动前先看这几条）：
 * - **同步、纯 Java、必须离开主线程**：调用方在 `Dispatchers.Default` 上执行（`FloatingBallService`
 *   的 pre/post 都是）。不用 WebView —— Android 自 API 28 起没有内置 JS 引擎，WebView 那条路
 *   是异步且必须在主线程/UI 线程里跑。
 * - **串行执行**：Rhino 的 `Context` 按线程隔离，`deadline` 是本对象共享的，所以全部执行走同一把锁。
 *   锁是**带超时**的 `tryLock`：万一某次执行卡死（见下面「执行上限」的边界），后续调用等一会儿
 *   就放弃并原样返回，不会把翻译链路永久钉死。
 * - **只暴露字符串**：用 `initSafeStandardObjects()` + `ClassShutter` 全拒，脚本拿不到任何宿主
 *   Java 类；入参出参都只是 String，用户能做的只有文本处理。
 * - **有执行上限（但有边界）**：解释模式下按指令数回调计时，纯 JS 的死循环会被掐断。
 *   ⚠️ **它挡不住「单条指令内的 Java 耗时」**：灾难性回溯的正则（预置模板本身就是正则）、
 *   `'x'.repeat(1e9)` 这类一次性大分配都不经过解释器循环，超时回调永远等不到。
 *   因此本组件只适合跑「用户自己写的、自己负责」的脚本，不是安全边界。
 * - **失败一律按原文返回**：语法错误 / 抛异常 / 超时 / 返回 undefined 或 null，都退回本次输入
 *   并记 W 级日志 —— 翻译是用户的主流程，不能因为一段可选脚本而失败。
 */
object TextTransformer {

    private const val TAG = "TextTransformer"

    /** 单次脚本执行的墙钟上限（毫秒）。正常文本处理在微秒级，300ms 足够宽松。 */
    private const val TIMEOUT_MS = 300L

    /** Rhino 指令计数回调的间隔：越小越及时，越大越省开销。 */
    private const val INSTRUCTION_OBSERVER_THRESHOLD = 10_000

    private const val SCRIPT_NAME = "transform"

    /** 包装出来的函数名，只在引擎内部可见。 */
    private const val FUNCTION_NAME = "__starflow_transform"

    /** 等锁上限：拿到锁之后自己还有 [TIMEOUT_MS] 的执行预算。 */
    private const val LOCK_WAIT_MS = TIMEOUT_MS

    private val lock = ReentrantLock()

    /** 全局唯一的 Context 工厂（见 init 块的说明）。 */
    private val factory = GuardedContextFactory()

    init {
        // ⚠️ 必须把我们的工厂设为 Rhino 的**全局**工厂。
        // Rhino 的 `Context` 在构造时就把自己的 factory 记成 `ContextFactory.getGlobal()`，
        // 而指令计数回调是发给 `cx.getFactory()` 的 —— 只用 `factory.enterContext()`
        // （不设全局）时，回调发给全局工厂，我们的超时保护永远不会被调用（实测：死循环跑到底）。
        try {
            ContextFactory.initGlobal(factory)
        } catch (e: IllegalStateException) {
            // 已经被别人初始化过：脚本照常能跑，但没有超时保护（下面 withContext 会记 W 日志）
            LogCollector.w(TAG, "Rhino 全局工厂已被占用，脚本超时保护不可用：${e.message}")
        }
    }

    /**
     * 执行脚本。
     *
     * @param script JavaScript 函数体，可用参数名为 [TextTransformScripts.PARAM_NAME]
     * @param input 传入的字符串
     * @return 脚本返回值；脚本为空/预置的「什么都不做」时原样返回，执行失败时也原样返回
     */
    fun apply(script: String, input: String): String {
        if (TextTransformScripts.isIdentity(script)) return input
        if (!lock.tryLock(LOCK_WAIT_MS, TimeUnit.MILLISECONDS)) {
            // 上一次执行还没回来（可能卡在正则/大分配上）：宁可这次不处理，也不要把调用方钉住
            LogCollector.w(TAG, "上一次脚本执行仍未结束，本次按原文返回")
            return input
        }
        try {
            return runScript(script, input)
        } catch (t: Throwable) {
            // 连 Error 一起接：Rhino 的超时中断与递归过深都走 Error。
            // 但 OutOfMemoryError / StackOverflow 之外的真实 VM 错误不该被吞掉。
            if (t is VirtualMachineError) throw t
            LogCollector.w(TAG, "脚本执行失败，按原文返回：${t.message}")
            return input
        } finally {
            lock.unlock()
        }
    }

    /**
     * 只做语法检查，用于设置页保存前给出提示。
     * @return 通过返回 null，否则返回可直接展示的错误信息
     */
    fun validate(script: String): String? {
        if (TextTransformScripts.isIdentity(script)) return null
        if (!lock.tryLock(LOCK_WAIT_MS, TimeUnit.MILLISECONDS)) {
            // 设置页在主线程：不能无限等另一条在途脚本
            return "a script is still running, please retry"
        }
        try {
            factory.deadline.set(System.nanoTime() + TIMEOUT_MS * 1_000_000L)
            return withContext { cx ->
                compile(cx, script)
                null
            }
        } catch (t: Throwable) {
            if (t is VirtualMachineError) throw t
            return t.message ?: t.javaClass.simpleName
        } finally {
            lock.unlock()
        }
    }

    private fun runScript(script: String, input: String): String {
        factory.deadline.set(System.nanoTime() + TIMEOUT_MS * 1_000_000L)
        return withContext { cx ->
            // safe 作用域：不含 Java 包访问（Packages/java/JavaAdapter 等都拿不到）
            val scope: Scriptable = cx.initSafeStandardObjects()
            val fn = compile(cx, script, scope)
            val result = fn.call(cx, scope, scope, arrayOf<Any>(input))
            // ⚠️ 忘了写 return（或写了 return null）拿到的是 undefined/null：
            // 直接 toString 会得到字面量 "undefined"/"null" 并当作译文喂给引擎、写进历史。
            // 一律退回原文，让「脚本没写对」表现为「没生效」而不是「翻出个 undefined」。
            if (result == null || result === Undefined.instance) {
                LogCollector.w(TAG, "脚本没有返回值，按原文返回")
                input
            } else {
                Context.toString(result)
            }
        }
    }

    /**
     * 进入一个按我们默认值布置好的 Context 执行 [block]。
     *
     * 正常路径下全局工厂就是 [factory]，`makeContext` 已经把默认值设好了；
     * 万一全局工厂被占用（initGlobal 失败），这里再把同样的默认值补到当前 Context 上，
     * 保证「解释模式 + ES6 + 拒绝 Java 访问」这些**隔离**措施始终生效。
     */
    private fun <T> withContext(block: (Context) -> T): T {
        val cx = Context.enter()
        try {
            if (cx.factory !== factory) {
                LogCollector.w(TAG, "当前 Context 不是我们的工厂，本次脚本没有超时保护")
                cx.optimizationLevel = -1
                cx.languageVersion = Context.VERSION_ES6
                cx.setClassShutter(ClassShutter { false })
            }
            return block(cx)
        } finally {
            Context.exit()
        }
    }

    private fun compile(cx: Context, script: String): Function =
        compile(cx, script, cx.initSafeStandardObjects())

    /**
     * 把用户脚本编译成函数：**自己包一层 `function (s) { ... }`**，再从作用域里取出来。
     *
     * ⚠️ 不要改用 `Context.compileFunction(...)`：实测该版本没有把源码当**函数体**解析，
     * 用户脚本第一行的 `return s;` 会直接报 `EvaluatorException: invalid return`。
     * 这里包一层函数声明，`return` 就在函数体内，语义也最直白（用户写的就是函数体）。
     */
    private fun compile(cx: Context, script: String, scope: Scriptable): Function {
        val source = "function $FUNCTION_NAME(${TextTransformScripts.PARAM_NAME}) {\n$script\n}"
        cx.evaluateString(scope, source, SCRIPT_NAME, 1, null)
        return scope.get(FUNCTION_NAME, scope) as? Function
            ?: throw IllegalStateException("脚本没有生成可调用的函数")
    }

    /**
     * 带执行时间上限的 Context 工厂。
     *
     * ⚠️ 必须 `optimizationLevel = -1`（解释执行）：Rhino 只在解释模式下回调
     * `observeInstructionCount`，开了字节码优化这条超时保护就失效了。
     */
    private class GuardedContextFactory : ContextFactory() {

        /** 单调时钟（nanoTime）截止点：避免系统校时跳变把 300ms 变成负数/几小时。 */
        val deadline = AtomicLong(0L)

        override fun makeContext(): Context = Context().apply {
            optimizationLevel = -1
            languageVersion = Context.VERSION_ES6
            instructionObserverThreshold = INSTRUCTION_OBSERVER_THRESHOLD
            // 脚本一律拿不到宿主 Java 类（safe 标准对象之外再加一道闸）
            setClassShutter(ClassShutter { false })
        }

        override fun observeInstructionCount(cx: Context?, instructionCount: Int) {
            if (System.nanoTime() - deadline.get() > 0) {
                throw ScriptTimeoutError()
            }
        }
    }

    /** 脚本超时：故意用 Error，避免被用户脚本自己的 try/catch 吃掉。 */
    private class ScriptTimeoutError : Error("脚本执行超时（超过 ${TIMEOUT_MS}ms）")
}
