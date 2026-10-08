# 沙箱化规则脚本运行时

在 Spring Boot 服务内提供一套可安全执行外部规则脚本（JavaScript / GraalJS）的运行时。
外部提交的脚本在受控沙箱中对请求或事件做判定；脚本自身的越权、死循环、资源滥用都被
在执行前或执行中拦截，不会拖垮共享运行时、宿主进程或其他脚本。

## 1. 总览

```
HTTP / 直接调用
      |
      v
DefaultRuleRuntime  -- 原子读取“当前已提交版本”（一次性固定到本次评估结束）
      |
      +-- AtomicVersionRegistry   发布/历史/回滚（无锁 CAS 整记录替换，读不到半成品）
      +-- InFlightTracker         在途版本计数（旧版本保留到最后一个旧调用结束）
      +-- BoundedSandboxExecutor  固定大小、小队列的隔离 worker 池（背压/拒绝）
      +-- GraalSandboxEngine      每次评估一个全新 Context（共享 Engine 代码缓存）
                    |
                    v
        权限全关 + 确定性 prelude + 语句/递归/输出/内存/时间 配额
```

脚本契约：脚本要么是一个函数 `(input, ctx) => decision`，要么定义
`function decide(input, ctx)`，返回结构化判定：

```json
{ "allowed": true, "reason": "…", "attributes": { "k": "v" } }
```

## 2. 沙箱边界（执行前拒绝，原因可区分）

每次评估都在一个全新 Graal `Context` 中执行，下列能力在**任何脚本文本运行之前**
就通过 Context 构建标志被关闭：

| 能力 | 关闭方式 | `DenyReason` |
| --- | --- | --- |
| 文件系统 | `allowIO(false)` + host-class 拦截 `java.io.File*`、`java.nio.file.*` 等 | `FILE_SYSTEM` |
| 进程执行 | `allowCreateProcess(false)` + 拦截 `Runtime`/`ProcessBuilder`/`Process*` | `PROCESS` |
| 网络 | `allowIO(false)` + 拦截 `java.net.*`、`javax.net.*`、Socket Channel | `NETWORK` |
| 反射/类加载 | `allowHostClassLoading(false)` + 拦截 `java.lang.Class`、`ClassLoader`、`java.lang.reflect.*`、`java.lang.invoke.*` | `REFLECTION` |
| 新建线程 | `allowCreateThread(false)` + 拦截 `Thread`/`ThreadGroup`/`java.util.concurrent.*` | `THREAD` |
| 本地方法/FFI | `allowNativeAccess(false)` + 拦截 `Unsafe`、`java.lang.foreign.*`、JNA 等 | `NATIVE` |
| 环境变量 | `allowEnvironmentAccess(NONE)` | `ENVIRONMENT` |
| 跨语言互操作 | `allowPolyglotAccess(NONE)` | `POLYGLOT` |
| 任意宿主访问 | `allowHostAccess(NONE)`、`allowAllAccess(false)`，class lookup 谓词一律拒绝 | `HOST_ACCESS` |

脚本执行 `Java.type('java.net.Socket')` 等访问时，host-class 查询谓词记录被请求的
类名并拒绝；运行时把 GraalJS 的通用 TypeError 结合该记录映射成
`kind=DENIED` 且带具体 `denyReason`（`FILE_SYSTEM`/`PROCESS`/`NETWORK`/`REFLECTION`/…）
的结构化错误，调用方可以区分不同越权类型。

## 3. 每次执行的配额项

所有配额都在每次评估级别生效，互不影响；超限时当前评估被中断并**整体丢弃**
（Context 强制关闭），运行时回滚到干净状态，后续评估与宿主进程不受影响。

| 配额 | 配置键（默认） | 执行点 / 机制 | 超限 `ExecutionErrorKind` |
| --- | --- | --- | --- |
| 执行时间 | `…timeout-millis` (2000) | 看门狗到点对该评估的 Context 执行 `close(true)`，可即时打断不主动让出的解释器死循环 | `TIMEOUT` |
| 内存 | `…memory-bytes` (32 MiB) | 单一全局监督线程周期性 GC 后测量**驻留堆增长**；超限强制关闭 Context | `MEMORY_LIMIT` |
| 输出规模 | `…output-bytes` (64 KiB) | 粘滞溢出的有界输出缓冲；脚本返回前检查溢出标志 | `OUTPUT_LIMIT` |
| 递归深度 | `…recursion-depth` (500) | 自定义 Truffle Instrument 按 RootTag 统计每线程调用深度（可配置，先于自然栈溢出） | `RECURSION_LIMIT` |
| 语句预算 | `…statement-limit` (5,000,000) | Graal `ResourceLimits.statementLimit`，对紧凑死循环作为时间之外的第二道闸 | `STATEMENT_LIMIT` |

其它错误类别：`COMPILE_ERROR`（语法错误，发布阶段即拒绝）、`SCRIPT_ERROR`
（脚本主动抛出未捕获异常）、`DENIED`（越权）、`REJECTED`（池满背压）、`INTERNAL`。

### 关于内存配额为何按“驻留堆”计量

GraalJS 在仅解释（无 JIT）模式下，纯 CPU 循环也会产生每秒数十 MB 的**短生命周期**
临时分配，JVM 会立即回收（实测 GC 后几乎零驻留）。若按“累计分配字节”计费，会误杀
无害的计算型脚本。因此内存配额采用 **GC 后存活堆的增量**：它精确捕捉脚本真正持有的
数组/对象/字符串（`new Array(N).fill(…)` 这类会真实驻留数十至数百 MB），而不计入
即抛即收的解释器临时对象。监督只由一个守护线程统一触发 GC，避免并发脚本各自 GC
互相抢占。时间与语句预算仍然保证纯 CPU 死循环会被快速中断。

## 4. 版本语义：发布单元的原子性、热替换与回滚

* **同一规则标识任一时刻只有一个已提交版本。** 每个规则持有不可变记录（当前版本 +
  追加式历史），发布时先在共享状态之外完成解析/编译，再对整条记录做一次 CAS 替换。
  读取方要么看到旧的完整记录，要么看到新的完整记录，**看不到半成品或字段级混合**。
* **版本号单调递增。** 发布小于等于当前版本的版本号会被拒绝（`VersionConflictException`），
  当前版本不受影响。
* **编译失败不替换当前好版本。** 编译发生在原子交换之前。
* **热替换期间请求不丢、不重复判定。** 评估在开始时一次性读取并**固定**当前版本快照，
  之后在整个评估过程中都使用该快照；替换中途到达的请求看到的要么是旧版本要么是新版本，
  绝不会对同一请求判定两次，也不会判定到一个“新旧混合”的版本。
* **在途旧版本能正常结束。** `InFlightTracker` 记录每个版本的在途评估数；被替换的旧
  版本仍保留在有界历史中，已在执行的旧调用跑到结束，结束后才释放。
* **回滚。** `rollback(ruleId, version)` 将规则重新指向某个历史版本的内容。为保持版本
  号线性可审计，回滚会以一个**新的、更大的版本号**提交该历史内容（快照不可变，历史可追溯）。
  回滚同样是原子整记录替换。

## 5. 并发隔离

* 每个评估跑在独立的有界 worker 线程上；一条脚本卡在死循环只占用它自己的 worker，
  其他 worker 上的快脚本照常服务（“慢脚本不阻塞快脚本”）。
* 每个评估使用全新 `Context`：全局变量、随机数/时钟状态、输出缓冲、递归计数器都不跨
  评估泄漏（共享的只有带来代码缓存的 `Engine`，不含可变的脚本全局）。
* 单条脚本的异常/超时/资源超限被隔离为该评估的错误，不会拖垮其他脚本或共享运行时。
* worker 池 + 小有界队列提供背压：全部 worker 与队列占满时新请求得到 `REJECTED`
  （HTTP 503），而不是无界排队耗尽内存。

## 6. 可复现性

相同输入、同一脚本版本、相同随机种子下，输出逐条一致。每次评估注入确定性 prelude：

* `Math.random()` 替换为以 `seed` 初始化的确定性 PRNG（mulberry32），并冻结；
* `Date` / `Date.now()` / `performance.now()` 固定为由种子导出的同一时刻；
* 每次评估全新 Context，不含上一次运行残留的全局状态。

种子通过评估请求的 `seed` 字段传入（HTTP 与直接 API 均可）。

## 7. HTTP API

| 方法 & 路径 | 说明 |
| --- | --- |
| `POST /api/rules/{id}/publish` | 原子发布新版本（body：`version`、`source`，可选各项配额覆盖） |
| `GET  /api/rules/{id}` | 当前已提交版本元数据（含在途计数） |
| `GET  /api/rules/{id}/history` | 已提交版本轨迹 |
| `POST /api/rules/{id}/evaluate` | 对当前版本执行一次（body：`input`、`context`、`seed`） |
| `POST /api/rules/{id}/rollback` | 原子回滚到历史版本内容（body：`version`） |

错误响应统一为：

```json
{ "error": "script_execution", "kind": "DENIED",
  "denyReason": "NETWORK", "message": "access to host class java/net/Socket (NETWORK) …" }
```

状态码：版本冲突/未知规则 -> 409 / 404；编译错误 -> 400；沙箱中止（超时/超限/越权等）
-> 422；过载拒绝 -> 503。

### 快速示例

```bash
curl -s localhost:$PORT/api/rules/fraud/publish -H 'Content-Type: application/json' -d '{
  "version": 1,
  "source": "function decide(input){ return {allowed: input.amount < 1000, reason: \"cap\"}; }"
}'

curl -s localhost:$PORT/api/rules/fraud/evaluate -H 'Content-Type: application/json' -d '{
  "input": { "amount": 1500 }, "seed": 42
}'
```

## 8. 配置

所有默认配额与池大小都可用 `application.properties` 覆盖（前缀 `rule.sandbox`），
发布时也可对单个版本逐项覆盖配额。键见 `RuntimeProperties` 与
`src/main/resources/application.properties`。

## 9. 测试

自动化测试（JUnit 5）覆盖：

* 越权访问：文件/进程/网络/反射/线程/本地方法，且 `denyReason` 可区分（`SandboxCapabilityTest`）；
* 资源超限：时间、内存（驻留堆）、输出、递归、语句，以及超限后运行时干净可用（`ResourceQuotaTest`）；
* 热替换/回滚：单调版本、编译失败不替换、回滚内容恢复、替换中请求不丢不重、版本固定（`HotSwapRollbackTest`、`RegistryPinningTest`）；
* 并发隔离：慢脚本不阻塞快脚本、失败脚本不拖垮他人、全局不跨评估泄漏（`ConcurrencyIsolationTest`）；
* 可复现性：同种子结果一致、异种子不同（`RuntimeSmokeTest`）；
* HTTP 端到端错误契约（`RuleApiIntegrationTest`）。

```bash
mvn test
```

## 10. 边界与取舍

* 沙箱基于 GraalJS 能力关闭 + host 边界拒绝，属于“能力隔离”，不依赖
  `SecurityManager`（JDK 17 已移除）。脚本不能接触文件/进程/网络/反射/线程/本地方法；
  若未来需要向脚本开放受控 API，应通过显式 allow-list 的宿主函数注入，而不是放宽全局开关。
* 运行于 GraalJS 仅解释模式（无 Graal JIT），面向轻量判定逻辑；重型计算受时间/语句
  预算约束。部署到完整 GraalVM 可自动获得编译加速，接口与配额语义不变。
* 内存为单进程驻留堆预算，适合在容器内配合 JVM/容器内存上限一起使用；多租户强隔离
  （每租户独立堆）需要进程级隔离，当前版本以“同进程内能力隔离 + 每评估配额 + 独立 Context”
  满足单服务多脚本互不拖垮的目标。
