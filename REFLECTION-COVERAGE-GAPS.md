# TamiFlex reflection-coverage gaps on modern JDKs

TamiFlex's Play-Out Agent instruments the **classic `java.lang.reflect` + `java.lang.Class` API**
only. Its full transformation set (from `PlayOutAgent/.../transformation/`) covers:

- `Class.forName`, `Class.newInstance`, `Class.get[Declared]{Method,Methods,Field,Fields}`, `Class.getModifiers`
- `Constructor.newInstance` (+ `getModifiers`, `toString`, `toGenericString`)
- `Method.invoke` (+ `getName`, `getDeclaringClass`, `getModifiers`, `toString`, `toGenericString`)
- `Field.get`, `Field.set` (+ `getName`, `getDeclaringClass`, `getModifiers`, `toString`, `toGenericString`)
- `Array.newInstance`, `Array` multi-`newInstance`

That surface has not changed since Java 5. But since then the platform (and the frameworks that
run on it) added several **alternative dynamic-dispatch / object-creation mechanisms** that achieve
the same effect as reflection yet make **no classic-reflection API call**, so they never appear in a
`refl.log`. This is a **soundness gap for anything that consumes the log** (e.g. Doop): a benchmark
that dispatches through these will have real dynamic edges/allocations that the log does not record.

## Omissions (what a modern-JDK run can do that TamiFlex does not report)

| # | Mechanism | Since | How it's used in the wild | Why TamiFlex misses it | Add feasibility |
|---|-----------|------:|---------------------------|------------------------|-----------------|
| 1 | **`java.lang.invoke.MethodHandle`** — `Lookup.findVirtual/findStatic/findConstructor/findSpecial/findGetter/findSetter`, `unreflect*`, `MethodHandle.invoke/invokeExact` | 7 | The parallel invocation API; used directly by frameworks and increasingly by the JDK | Logs `Method.invoke`, not `MethodHandle.invoke`; `find*` resolve targets it never sees | **High** — instrument the `Lookup.find*`/`unreflect*` calls; the target (class, name, MethodType) is in the args |
| 2 | **`invokedynamic` + `LambdaMetafactory.metafactory`** (lambdas, method refs); **`StringConcatFactory`** (string `+`) | 8 / 9 | Every lambda/method-ref; spins a **hidden class** at runtime | No reflection-API call — it's a JVM bootstrap, not a method call TamiFlex rewrites | **Low** — call-rewriting can't see `invokedynamic`; needs bootstrap/`ClassFileTransformer`-level capture (Doop already models lambdas separately) |
| 3 | **`VarHandle`** — `Lookup.findVarHandle/findStaticVarHandle`, `arrayElementVarHandle` | 9 | Reflective field/array access; replaced `Unsafe` in concurrency code | Not a `Field.get/set` call | **High** — same shape as (1) |
| 4 | **`Lookup.defineHiddenClass[WithClassData]`** | 15 | Runtime class definition — ByteBuddy/Mockito/proxy generators, lambda impls | No classic API; the class has no stable name | **Medium** — instrument the call; can log the *defining* class + that a hidden class was created |
| 5 | **`sun.misc.Unsafe.allocateInstance`** / `ReflectionFactory.newConstructorForSerialization` | (6)/(9) | Allocate instances **without running a constructor** — Kryo, Jackson, Java serialization | Not `Constructor.newInstance` | **Deferred** — `Unsafe.allocateInstance` is a **native** method (no bytecode body), so TamiFlex's callee-rewriting can't hook it; would need native-method prefixing |
| 6 | **`java.lang.reflect.Proxy.newProxyInstance`** | 1.3 | Dynamic proxies (Spring, JDK RMI, mocking); `$Proxy` class + `InvocationHandler.invoke` | Classic TamiFlex blind spot — proxy gen + reflective dispatch not recorded | **High** — instrument `newProxyInstance`; log the proxied interfaces |
| 7 | **`Class.forName(Module, String)`** overload; module `ServiceLoader` provider lookup | 9 | Module-aware class loading | Transformation likely matches only the pre-module `forName` descriptors | **High** — add the extra descriptor to the existing `ClassForName` transformation |
| 8 | **Records / sealed metadata** — `Class.getRecordComponents`, `RecordComponent.getAccessor`, `Class.getPermittedSubclasses` | 16/17 | Record (de)serialization (Jackson) uses the canonical ctor via these | New metadata APIs not in the set | **High** — metadata transformations mirroring `Class.getDeclaredFields` |

## What "capturable" means here (the resolution-point principle)

TamiFlex captures a mechanism when three things hold: (1) there is a concrete JDK
**method whose body can be rewritten** (retransformable class), (2) the **resolved target
is recoverable from that method's operands** (`this` / args / return), and (3) there is a
real **application caller frame** on the stack (`getInvokingFrame`).

The key design point for the modern additions: capture at the **resolution/creation point**,
not the invocation. `Lookup.findVirtual(refc, name, type)` and `Proxy.newProxyInstance(.., ifaces, ..)`
name their target explicitly in the arguments — logging *there* recovers the target identity a
static analysis needs, exactly as TamiFlex already logs `Class.getMethod` (resolution) rather than
the subsequent `Method.invoke`. The opaque `MethodHandle.invoke` / proxy dispatch site does not need
to be instrumented at all once the lookup is recorded.

**What still escapes (fundamental, not fixable by more transformations):**
- **Raw `invokedynamic`** — a bytecode instruction with a JVM bootstrap, not a method call at the
  site, so there is nothing to rewrite (string-concat, custom indy, …).
- **Combinator-built MethodHandles** — a handle assembled via `filterArguments`/`foldArguments`/
  `insertArguments`, or with a runtime-computed `MethodType`, has **no single resolvable target** at
  any lookup site. This is the same limit every reflection log has (`Class.forName(userInput)` can't
  be pinned either): we capture the *direct/common* cases, not the pathological dynamic tail.

So realistic scope = items **1, 3, 5, 6, 7, 8** (lookup/creation points with a nameable target).
Item **2** is capturable-ish but redundant (Doop models lambdas from `invokedynamic`/BootstrapMethods
directly); item **4** logs only an event (a hidden class has no stable name → no resolvable target).

## Not a gap (worth stating)

**JEP 416 — "Reimplement Core Reflection with Method Handles" (Java 18)** rewired
`Method.invoke`/`Constructor.newInstance` to route through MethodHandles **internally**. The public
`java.lang.reflect` entry points are unchanged, and TamiFlex instruments those entry points — so
Java 18+ did **not** silently break classic-reflection capture. Existing logs remain complete for the
classic API.

## Impact / priority

For feeding a static analysis (Doop), the highest-value additions are the ones that create **new
call edges or allocations** the analysis can't otherwise recover: **(1) MethodHandle**, **(5)
Unsafe.allocateInstance**, **(6) Proxy**, then **(3) VarHandle** and **(7) the forName overload**.
**(2) invokedynamic/lambda** is architecturally hard for TamiFlex's call-rewriting model and is
typically handled elsewhere in the analysis pipeline.

## Implementation status

**Implemented, opt-in behind `-Dtamiflex.modernReflection=true`** (or `modernReflection=true` in
`poa.properties`) — default runs are byte-for-byte unchanged:
- **(1) MethodHandle lookups** — `Lookup.findVirtual/findStatic/findSpecial/findConstructor` and the
  `unreflect`/`unreflectSpecial`/`unreflectConstructor`/`unreflectGetter`/`unreflectSetter` family.
- **(3) VarHandle + field lookups** — `Lookup.findVarHandle/findStaticVarHandle` and
  `findGetter/findSetter/findStaticGetter/findStaticSetter`.
- **(6) `Proxy.newProxyInstance`** — logs each proxied interface.
- **(7) `Class.forName(Module,String)`** overload.

New logger `Kind`s (`MethodHandles.Lookup.find*` / `.unreflect*`, `Proxy.newProxyInstance`) capture at
the **resolution point**. Code: `PlayOutAgent/.../transformation/modern/` (LookupTransformation,
ProxyTransformation, ClassForNameModuleTransformation) + new `ReflLogger` methods. Verified: with the
flag off no modern entries appear and classic capture is unchanged; with it on, `find*`/`unreflect*`/
`Proxy` are logged with correct Soot signatures + call sites.

**Not implemented:**
- **(5) Unsafe.allocateInstance** — native method, not body-rewritable (see table).
- **(8) record/sealed metadata** — straightforward to add on the same pattern if needed.
- **(2) invokedynamic / lambdas** and **(4) defineHiddenClass** — deferred (redundant / event-only, per above).
