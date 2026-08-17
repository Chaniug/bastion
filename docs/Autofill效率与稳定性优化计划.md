# Autofill 运行效率/内存/稳定性优化计划（不改变填充逻辑）

> 分支：`dev`
> 前提：不改变现有填充逻辑与认证流程，保证当前可用性。聚焦热路径效率、内存、稳定性。
> 基于 autofill 模块代码审计，按"影响大小 × 改动风险"排序。

---

## P1【高影响/低风险】onFillRequest 热路径 SHA-256 + 日志 metadata 分配
**文件**：`BastionAutofillServiceNg.kt`（buildFieldSignatureKey ~1407 行；20+ 处 mapOf 日志 metadata）
**问题**：每次 onFillRequest 都 `MessageDigest.getInstance("SHA-256")`（含 Provider 查找）+ 字节数组转十六进制；20+ 处每次构建 `mapOf(5-15 项)` 做日志 metadata，`targetRolePreview` 还对每目标字符串拼接。
**优化**：
- `MessageDigest` 改 companion 缓存（ThreadLocal 复用，避免每次 Provider 查找）
- 日志 metadata 改懒构造（非 DEBUG 级别跳过 targetRolePreview 拼接）
- 热路径 mapOf 改为按日志级别门控

## P2【高影响/低风险】getActiveEntries 查 SELECT * 含大字段 + 缺复合索引
**文件**：`PasswordEntryDao.kt`（getActiveEntries ~402 行）、`BastionApplication`/DB schema
**问题**：autofill 每次 `.first()` 整库拉 216+ 条含密文 password/notes/passkey_bindings 大字段到内存，再在内存里匹配。查询 `WHERE isDeleted=0 AND isArchived=0 ORDER BY isFavorite,sortOrder,updatedAt` 无联合索引。
**优化**：
- 新增 `getAutofillCandidates()` 投影查询，只取匹配所需列（id/title/website/username/appPackageName/appName/loginType 等，排除 password/notes/passkey_bindings 大字段）——匹配阶段不需要这些大字段，选中条目后按 id 单查解密
- 加复合索引 `(isDeleted, isArchived, isFavorite, sortOrder)` 覆盖常用查询路径
- 风险：需确认匹配/排序不依赖被排除字段

## P3【高影响/中风险】passwordMemoryByPackage 缓存无淘汰 + 持有框架对象
**文件**：`BastionAutofillServiceNg.kt:84`
**问题**：`mutableMapOf<String, List<ParsedItem>>` 只在 onDestroy/onDisconnected 清空；ParsedItem 持有 AutofillId（框架对象，可能持 native 句柄）。长期运行的 autofill 进程切换 App 时不断累积。
**优化**：改 `LruCache(8)` 或加 TTL（5 分钟未访问移除），onDisconnected 已 clear 保留

## P4【高影响/低风险】matcher 对每条目重复 URL 解析 + 集合分配
**文件**：`BitwardenLikeAutofillMatcherNg.kt:117-124,308`
**问题**：对 216+ 条目每条都 `extractNormalizedPackages`/`extractWebsiteTokens`/`extractNormalizedHosts`（内部 `URL()` 解析），同一次 match 全量重算。scoreEntry 内每次新建 linkedSetOf。
**优化**：entry 的 packages/hosts/roots 解析结果缓存（旁路 WeakHashMap 或 @Ignore 懒字段），避免每次 match 重算

## P5【中影响/低风险】AutofillConfigCache.preload 串行 12 次 .first()
**文件**：`AutofillConfigCache.kt:84-101`
**问题**：`runBlocking { withTimeout(200) { 12 次 .first() 串行 } }`，低端机/IO 抖动常超时 → fallback 默认值可能与用户设置不符（影响匹配）。autofill 是独立进程，service.onCreate 承担初始化。
**优化**：12 个 .first() 改 `async{}.awaitAll()` 并行，或合并到单次 settingsFlow.first() 读取后散开赋值（SettingsManager 已聚合）

## P6【中影响/低风险】解析器正则每次 service 实例化
**文件**：`EnhancedAutofillStructureParserV2.kt:221-225`、`BastionAutofillServiceNg.kt:121`
**问题**：5 个 `.toRegex()` 是实例字段，每次 service 重建 new parser 重编译正则。
**优化**：改 companion `val` 静态化（PACKAGE_NAME_REGEX 已是 companion，照做）

## P7【中影响/中风险】并发 onFillRequest 竞态：recentFillSuggestions 无锁
**文件**：`BastionAutofillServiceNg.kt:135,989`
**问题**：`@Volatile recentFillSuggestions` 先读后写无锁，两个并发 onFillRequest 可能互相覆盖。
**优化**：stabilizeMatchedPasswords 加 `synchronized(recentFillSuggestions)` 或复用 passwordMemoryByPackage 的锁

## P8【低影响/低风险】12 个独立 scope.launch collect 协程
**文件**：`BastionAutofillServiceNg.kt:155-219`
**问题**：12 个常驻协程各自 collect 一个 flow，跨进程 DataStore 订阅。
**优化**：合并到单 `combine{}` 或 settingsFlow 一次 collect 后散开赋值，减少协程数与订阅开销。scope 在 onDestroy 已 cancel，无泄漏。

---

## 实施建议
- **第一批（P1/P4/P6）**：纯热路径 GC 优化，零行为变化，最低风险，先做
- **第二批（P2/P5）**：IO/冷启动优化，需测试匹配一致性
- **第三批（P3/P7/P8）**：稳定性/内存，改动小但需回归
- 每批独立提交 + CI 验收，便于回滚定位

## 验收
- dev 提交 → Android CI debug 全绿（基线 0 失败）
- 真机实测：填充响应 elapsedMs 不升高、填充行为不变、无内存增长
- 日志：onFillRequest 耗时与之前持平或下降
