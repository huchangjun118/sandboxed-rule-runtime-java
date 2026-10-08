# 沙箱化规则脚本运行时

外部提交的规则脚本在一个受控沙箱内对请求/事件做判定。本文件说明**沙箱边界**、
**配额项**、**版本语义**、语言/HTTP 接口以及失败原因码。

---

## 1. 能力总览与需求对应

| 需求 | 实现方式 |
| --- | --- |
| 带版本的发布单元；任一时刻只有一个已提交版本；读不到半成品/字段级混合 | 不可变 `ScriptVersion` + 单原子引用；先完整解析+沙箱扫描，再原子切换 |
| 文件/进程/网络/反射在**执行前**拒绝，原因可区分 | 执行前 AST 策略扫描（`SandboxValidator`）+ 解释器根本不暴露这些能力（纵深防御） |
| 时间/内存/输出/递归深度上限；死循环中断；干净回滚 | 每次执行独立 `RuntimeContext` 记账；协作式 tick + `Future` 硬超时 + 线程中断 |
| 热替换/回滚；期间请求不丢失、不重复判定；旧版本在途调用正常结束 | 执行前快照当前不可变版本；原子指针切换；旧版本对象被在途调用持有 |
| 并发互相隔离；慢脚本不阻塞快脚本 | 独立线程池、零共享可变状态、SynchronousQueue hand-off + 显式过载拒绝 |
| 结果可复现：相同输入+版本+随机种子逐条一致 | 封闭值世界、确定性 xorshift RNG、无时钟/随机环境 API、输入确定性派生种子 |
| 自动化测试 + 文档 | 38 个测试（越权/超限/热替换/并发隔离/回滚/HTTP），本文件 |

---

## 2. 沙箱边界（脚本能碰什么、不能碰什么）

### 2.1 为什么文件/进程/网络/反射“根本不存在”

脚本不是在通用 JS/Groovy 引擎里运行，而是由一个**自研的、封闭的解释器**执行：

- 脚本值只能是 `null / boolean / number(double) / string / array / object`
  （封闭的 `Value` 类型族），**拿不到任何 Java 对象句柄**、拿不到 class loader、
  没有 import/require/eval 语法，也没有任何系统属性、环境变量、时钟或网络 API。
- 内建函数是一张**白名单**（见第 5 节），其余函数调用一律拒绝。
- 因此文件系统 / 进程 / 网络 / 反射不是“运行到了再拦”，而是**语言层面就不可达**。

### 2.2 执行前策略扫描（显式、可区分的拒绝点）

发布时，在**任何脚本指令执行之前**，`SandboxValidator` 对 AST 做一遍策略扫描。
任何对受限能力的请求都在提交前被拒绝，脚本不会成为已提交版本。

拒绝原因码（reason code）可区分：

| 原因码 | 含义 | 典型样例 |
| --- | --- | --- |
| `SANDBOX_FILESYSTEM` | 文件系统 | `readFile(...)`、`Files.read(...)`、`File.path`、`fs.write(...)` |
| `SANDBOX_PROCESS` | 进程/运行时控制 | `exec(...)`、`system(...)`、`Runtime.exec(...)`、`ProcessBuilder.start(...)`、`getenv` |
| `SANDBOX_NETWORK` | 网络 | `fetch(...)`、`http.get(...)`、`openSocket(...)`、`url.openConnection(...)` |
| `SANDBOX_REFLECTION` | 反射/运行时内部 | `eval(...)`、`Class.forName(...)`、`System.exit(...)`、`Thread....`、`input.getClass()` |
| `SANDBOX_DENIED` | 其它任何沙箱外能力 | `require(...)`、`java.version`、`globalThis.x`、未知函数、对值的方法调用 |

说明：

- 点号成员链（`System.out.exit` 之类）按其**链根标识符**分类（`System`→反射，
  `Runtime`→进程，`http`→网络，`File/Files/path/fs`→文件……）。
- 对任意值的“方法调用”（`a.b(...)`）在封闭值世界里没有方法句柄，因此一律拒绝。
- 受限根名（如 `http`、`file`、`runtime`、`socket`、`url`）是**保留标识符**，
  不应作为请求输入字段名；输入请用普通业务字段（`input.score`、`input.user.id`）。

---

## 3. 单次执行配额（quota）

每次执行使用一个**全新的** `RuntimeContext`，默认配额如下（可通过配置或按请求覆盖）：

| 配额 | 默认值 | 配置键 | 超限原因码 |
| --- | --- | --- | --- |
| 执行时间（墙钟） | `200 ms` | `sandbox.rule.timeout-millis` | `RESOURCE_TIME_LIMIT` |
| 内存（已记账分配） | `64 KB` | `sandbox.rule.memory-bytes` | `RESOURCE_MEMORY_LIMIT` |
| 输出规模（log + 结果序列化） | `8 KB` | `sandbox.rule.output-bytes` | `RESOURCE_OUTPUT_LIMIT` |
| 递归深度（用户函数栈） | `64` | `sandbox.rule.max-recursion-depth` | `RESOURCE_RECURSION_LIMIT` |
| 指令步数（语句/表达式/循环迭代） | `2,000,000` | `sandbox.rule.max-steps` | `RESOURCE_STEP_LIMIT` |

执行/隔离机制：

- **时间**：解释器每条语句、每次循环迭代都 `tick()`，周期性比较墙钟；`while(true){}`
  这类死循环在预算用尽即抛 `RESOURCE_TIME_LIMIT`。此外外层用 `Future.get(超时)` 做硬兜底，
  超时 `cancel(true)`，解释器还协作响应线程中断标志——纯计算死循环也会被打断，
  worker 线程与宿主进程保持存活。
- **内存**：字符串/数组/对象等脚本侧分配按保守字节数记账（含嵌套），新建大数组、
  无限拼接字符串都会触发 `RESOURCE_MEMORY_LIMIT`。
- **输出**：`log` 追加内容受输出配额约束，防止日志洪水。
- **递归/步数**：用户函数入栈计数，递归过深提前抛错；指令步数是对“不看墙钟的
  忙碌循环”的第二道保险。
- **干净回滚**：越限只是一次普通异常展开，执行线程不死亡；下一次执行使用全新上下文、
  全新环境链和全新输入快照，没有任何可变状态残留。

> 内存/步数是解释器内的**会计式**限制（精确作用于脚本可产生的对象与指令），
> 不是对 JVM 堆的硬隔离；硬实时由时间配额 + 线程中断保证。

---

## 4. 版本语义（发布 / 热替换 / 回滚）

- **发布单元不可变**：`ScriptVersion{ruleId, version, source, parsedAst, committedAt}`
  一旦构造不再变化。
- **任一时刻一个已提交版本**：每个规则用一个 `AtomicReference` 指向当前版本。
  发布时先完成「词法 → 语法 → 沙箱扫描」全部准备，**最后**才做单次原子引用切换。
  因此读取方要么看到上一个完整版本，要么看到整个新版本，绝不会看到半成品或字段级混合。
- **版本号单调递增**：可省略（自动取 last+1，分配与提交在同一把锁内，并发不撞号），
  也可显式传入，但必须严格大于当前最大版本。
- **回滚**：把当前引用重新指向历史上某个**已存在的不可变版本**；历史不被改写或删除，
  回滚后可继续向前发布。
- **热替换期间不丢/不重判**：执行入口先抓取当前版本快照再派发。替换发生在请求途中时：
  - 已派发到旧版本的调用持有自己的 `ScriptVersion`，**在旧版本上正常结束**；
  - 之后的请求看到新版本；
  - 每个请求只得到一次判定（旧结果或新结果），不会两个版本各判一次。
- **并发**：多个不同脚本/规则在独立任务里执行，互不共享可变状态；某个脚本异常、
  超时或资源超限只影响它自己的结果。

---

## 5. 规则语言与内建白名单（简表）

语句：`var`、`if/else`、`while`、`for (x in 数组|对象|字符串)`、函数声明
`function f(a,b){...}`、`return`、`log expr;`、块 `{ ... }`。

表达式：算术 `+ - * / %`、比较 `< <= > >= == !=`、逻辑 `&& || !`、数组 `[..]`、
对象 `{k: v}`、成员 `a.b`（含 `.length`）、下标 `a[i]`、三元括号分组。无 `new/import/eval`。

白名单内建（其余函数调用即 `SANDBOX_DENIED`）：

- 数值：`abs floor ceil round min max pow sqrt`
- 转换/检查：`str num bool len type`
- 字符串：`contains startsWith endsWith lower upper trim substring split join`
- 集合：`keys values range`
- 随机（**仅**来自每执行种子）：`random()`、`randInt(bound)`

输入通过只读全局 `input` 获取。脚本**没有**时钟、无 `Date/now/random` 系统源，
保证可复现。

---

## 6. 可复现性

- 随机数仅来自每执行的确定性 xorshift64*（`random()` / `randInt()`）。
- 可在执行请求中显式给 `seed`；不给时种子由 `ruleId + 版本号 + 输入图` 确定性派生。
- 相同 **输入 + 同一脚本版本 + 相同种子** 的逐条输出（结果与 log）一致；
  对象按插入序遍历，无哈希不确定性。

---

## 7. HTTP 接口

| 方法 & 路径 | 作用 |
| --- | --- |
| `POST /api/rules/{ruleId}/versions` | 发布：`{"version":3,"source":"..."}`（version 可省略语义由服务自动分配时请省略字段） |
| `POST /api/rules/{ruleId}/rollback` | 回滚：`{"version":1}` |
| `POST /api/rules/{ruleId}/execute` | 执行当前版本：`{"input":{...},"seed":123}`（seed 可空） |
| `GET  /api/rules/{ruleId}` | 当前版本视图 |
| `GET  /api/rules/{ruleId}/versions` | 历史版本号列表 |

执行结果：`{ruleId, version, seed, result, output, elapsedMillis, matched, deterministic}`。

错误响应：`{"reason": "<原因码>", "message": "..."}`，状态码映射：

| 场景 | HTTP | 原因码 |
| --- | --- | --- |
| 越权能力 | 403 | `SANDBOX_FILESYSTEM / _PROCESS / _NETWORK / _REFLECTION / _DENIED` |
| 脚本语法错误 | 400 | `SCRIPT_COMPILE_ERROR` |
| 脚本运行时错误 | 422 | `SCRIPT_RUNTIME_ERROR` |
| 资源超限 | 429 | `RESOURCE_TIME/MEMORY/OUTPUT/RECURSION/STEP_LIMIT` |
| 池饱和（过载保护） | 503 | `RUNTIME_OVERLOADED` |
| 规则不存在 | 404 | `RULE_NOT_FOUND` |
| 请求参数非法 | 400 | `INVALID_ARGUMENT` |

线程池用 hand-off 队列（`SynchronousQueue`），**慢脚本不会把快脚本排在后面**；
所有 worker 忙时新请求立即得到 503 `RUNTIME_OVERLOADED`，而不是无限排队。

---

## 8. 配置

`application.properties`（前缀 `sandbox.rule`）：

```properties
sandbox.rule.worker-threads=16
sandbox.rule.timeout-millis=200
sandbox.rule.memory-bytes=65536
sandbox.rule.output-bytes=8192
sandbox.rule.max-recursion-depth=64
sandbox.rule.max-steps=2000000
```

---

## 9. 测试覆盖

- `ScriptExecutionTest`：判定逻辑、函数/循环/集合、可复现性、默认确定性种子、log 输出。
- `SandboxPolicyTest`：文件/进程/网络/反射/通用拒绝，原因码两两可区分，拒绝发生在执行前且旧版本不受影响，白名单内建可用。
- `ResourceLimitTest`：死循环超时、忙循环、内存、输出、递归、步数超限；失败后运行时干净可复用；规则不存在。
- `VersionManagementTest`：单调版本、显式版本冲突、失败发布不暴露半成品、回滚、热替换即时可见、**在途旧版本正常结束**、替换期间请求不丢失/不重复判定。
- `ConcurrencyIsolationTest`：慢脚本不阻塞快脚本、坏脚本不影响健康脚本、超时/超限隔离、多脚本混合并发无跨脚本故障、池饱和显式过载拒绝。
- `RuleApiIntegrationTest`：HTTP 端到端发布/执行/回滚，以及 403/400/404 原因码。

运行：`mvn test`。
