# DevBeer — Compatibility, Memory and Performance

Every number here was **measured**, not estimated. The compatibility table comes from running
the JetBrains Plugin Verifier against each IDE build; the performance table from
`PerformanceBenchmarkTest`.

---

## 1. Which IDE versions work

Supported range: **231 – 243.\*** — IntelliJ IDEA **2023.1 through 2024.3**, and the
equivalent builds of every other JetBrains IDE.

### Measured results across 2021–2024

| IDE | Build | Verifier result | What breaks |
|---|---|---|---|
| 2021.3 | IC-213 | ✗ 20 problems | `ActionUpdateThread`, `ContentFactory`, declarative inlays |
| 2022.1 | IC-221 | ✗ 20 problems | same |
| 2022.2 | IC-222 | ✗ 2 problems | declarative inlay hints only |
| 2022.3 | IC-223 | ✗ 2 problems | declarative inlay hints only |
| **2023.1** | IC-231 | **✓ Compatible** | — |
| **2023.2** | IC-232 | **✓ Compatible** | — |
| **2023.3** | IC-233 | **✓ Compatible** | — |
| **2024.1** | IC-241 | **✓ Compatible** | — |
| **2024.2** | IC-242 | **✓ Compatible** | 1 deprecation |
| **2024.3** | IC-243 | **✓ Compatible** | 2 deprecations |

### Per-feature availability

The honest answer is that **the feature set does not vary across the supported range**. On
2023.1 and on 2024.3 you get exactly the same plugin.

| Feature | 2023.1 – 2024.3 | Needs Java module |
|---|---|---|
| Property & YAML Compare | ✓ | no |
| Folder Compare | ✓ | no |
| JSON Formatter | ✓ | no |
| String Toolbox (74 transforms) | ✓ | no |
| **AI Assistant** — Ask, Explain, Suggest, Repository Search, Analyze Error | ✓ | no |
| AI Assistant — **Code Flow** | ✓ | **yes** |
| Spring Boot Property Hints | ✓ | **yes** |

The only axis that changes what you get is **whether the IDE has Java support**, not how new
it is. In WebStorm, PyCharm, GoLand and RubyMine everything works except Code Flow and the
Spring hints; repository search falls back to the platform word index, which is slightly less
precise but fully functional.

### Why 2021 is not supported — and what it would take

A common assumption is that the AI layer is the modern part and needs a recent IDE, so older
IDEs could get "basic" features. **The measurement says the opposite.**

The AI layer uses nothing newer than the rest of the plugin. On 2022.2 the *only* failure in
the entire plugin is the declarative inlay-hints package — used exclusively by the Spring
Boot Property Hints, which is the plugin's **oldest** feature, shipped in 1.0.0.

| Target | Cost |
|---|---|
| **2023.1** | none — this is the current floor |
| 2022.2 / 2022.3 | drop or rewrite the Spring inlay hints against the pre-declarative API. Nothing else breaks, AI included |
| 2022.1 | the above, plus removing every `ActionUpdateThread` override — which then costs correct EDT/BGT action classification on modern IDEs |
| 2021.3 | the above, plus replacing `ContentFactory.getInstance()`, plus abandoning the IntelliJ Platform Gradle Plugin 2.x, whose own minimum target is 2022.3 |

A tiered build — old IDEs get basic features, new IDEs get everything — is possible but would
mean two artifacts and two Marketplace listings, since one plugin has a single `since-build`.
Given that 2021.3 is over four years old, the current single-artifact floor of 2023.1 is the
better trade.

> The floor was **2024.1** in 2.1.0 and **2023.3** in 2.2.0. Both were set from reasoning
> about when APIs appeared rather than from measurement, and both were too conservative. 2.3.0
> lowers it to the measured floor of 2023.1 with no code change.

---

## 2. Memory

The design rule is that **nothing scales with repository size**. Every buffer has a ceiling
fixed in configuration, not derived from how much code you have.

| What | Ceiling | Measured |
|---|---|---|
| Context sent per request | 10 files / 50,000 chars (configurable) | **97 KB** at the ceiling |
| Response cache | 50 entries | **≈3 MB** worst case |
| Context cache | 20 entries | ≈2 MB worst case |
| Candidate files read before ranking | 60 | bounded by the same 97 KB |
| In-flight LLM requests | 1 | — |

**Idle cost is effectively zero.** The settings object is a small application service; the
project service and the tool window are created lazily on first use. No index, no database, no
background thread, no network connection is created until you invoke a feature.

Caches are dropped wholesale whenever the VFS modification count changes, so they cannot
accumulate across an editing session.

### Measured

```
BENCH context footprint: 50000 chars ≈ 97 KB
BENCH cache ceiling:     50 replies × 32 KB ≈ 3 MB worst case
BENCH payload:           10-file project = 4000 chars, 100k-file project = 4000 chars
```

That last line is the §8 promise, measured: **a project with 100,000 files produces exactly
the same payload as one with 10.**

---

## 3. Performance

Measured on the CPU-bound path — everything that runs after IntelliJ's index returns results.
Median of 5 runs after 2 warmups.

| Operation | Scale | Median |
|---|---|---|
| Rank + select candidates | 100,000 | **46 ms** |
| Exclusion check | per file | **3.7 µs** → ~0.2 ms for a real 60-file pass |
| Sanitise context | 30 KB | **17 ms** |
| Parse stack trace | 1,000 frames | **32 ms** |
| Build full prompt | at budget | **1 ms** |

Total CPU cost of assembling a request on a very large project: **well under 100 ms**. The
remaining wall-clock time is IntelliJ's index lookup and the model itself.

### Against the §37 targets

| Target | Result |
|---|---|
| Startup overhead negligible when AI unused | ✓ nothing constructed until invoked |
| Context search < 500 ms | ✓ CPU path is ~50 ms; index lookup dominates |
| Never block the EDT | ✓ all search, I/O and LLM calls in `Task.Backgroundable` |
| LLM fully asynchronous | ✓ with cancellation polled inside the provider |
| No full repository scan | ✓ `processCandidateFilesForText` is index-backed |
| No unbounded caches | ✓ LRU capped at 50 / 20 |
| Network only on user action | ✓ disabled by default |

### What is *not* measured

The index lookup itself (`PsiSearchHelper.processCandidateFilesForText`) is IntelliJ's own
code and its cost depends on index warmth. On a cold index — right after opening a project —
the first query waits for indexing like any other search. That is why the work runs in a
cancellable background task rather than blocking.

No profiling has been done inside a running IDE on a large real-world project. The figures
above are JVM benchmarks of the plugin's own code.

---

## 4. Reproducing

```bash
./gradlew test --tests '*PerformanceBenchmarkTest'   # prints every BENCH line
./gradlew verifyPlugin                              # verifies the declared range
```

To re-measure the compatibility table, temporarily set `pluginSinceBuild = 213` and add the
older IDEs to `pluginVerification.ides` in `build.gradle.kts`.
