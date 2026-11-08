# Phase B: Storage Module and Room/KSP Compatibility

## Scope

Phase B adds an Android library module named `:storage` and verifies that its Android plugin, Room dependencies, and KSP plugin resolve and build in the project's CI toolchain. It does not add entities, DAOs, a database declaration, migrations, converters, repository implementations, or persistence/recovery behavior.

The `:core` module remains a Kotlin/JVM module. `:storage` depends on its contracts through a Gradle project dependency; no Android or Room dependency is introduced into Core.

## Selected versions

| Component | Version | Reason |
| --- | --- | --- |
| Android Gradle Plugin | 9.3.1 | Existing project version; Android library plugin uses the same version as the application plugin. |
| Gradle | 9.5.0 | Existing CI version and AGP 9.3 minimum/default. |
| Kotlin | 2.4.21 | Existing Core/Compose toolchain. Android modules use AGP's built-in Kotlin support; no Kotlin Android plugin is applied to `:storage`. |
| JDK | 17 | Existing CI toolchain and AGP 9.3 minimum/default. |
| Room | 3.0.3 | Current stable Room 3 release; Room 3 requires KSP and Kotlin code generation. |
| KSP | 2.3.13 | Current KSP release. KSP uses independent 2.3.x versioning; AGP 9 compatibility is supported by KSP 2.3.1+, and 2.3.13 includes later fixes and improvements. |

Room 3 uses the `androidx.room3` group/artifact names and package, not Room 2's `androidx.room` namespace. The runtime and compiler are added only to `:storage`; the compiler is on KSP's configuration.

## Compatibility verification

GitHub Actions resolves `:storage`'s `debugRuntimeClasspath`, `kspDebug`, and `kspRelease` configurations. It then runs `:storage:kspDebugKotlin` and `:storage:kspReleaseKotlin` separately, each with its own log and execution check. Both checks reject `NO-SOURCE`, `SKIPPED`, `UP-TO-DATE`, and `FROM-CACHE`, and require the task's normal execution line. Finally, CI builds both `:storage:assembleDebug` and `:storage:assembleRelease`. This is configured to check dependency availability, plugin setup, KSP execution, and Android library compilation on the same JDK 17 / Gradle 9.5.0 runner as the app.

Local Gradle execution was explicitly excluded for this phase, and GitHub Actions has not yet run with these changes. The source documentation establishes the supported baseline, but successful compatibility has not been proven. That requires a GitHub Actions run in which both KSP tasks pass their independent execution checks and both Debug and Release assemblies succeed. No compatibility claim is made until that run succeeds.

## Architecture boundary and deferred work

The accepted storage design remains unchanged: Core contracts define conversation, message, invocation, approval, and recovery behavior; Room entities and DAOs will map those models in `:storage` later. Sequence allocation, approval consumption, result persistence/reconciliation, migrations, and recovery transactions are all deferred. This phase creates no database schema and cannot persist user data.

## References

- [Room 3 release notes](https://developer.android.com/jetpack/androidx/releases/room3)
- [Room setup guide](https://developer.android.com/training/data-storage/room)
- [KSP 2.3.13 release](https://github.com/google/ksp/releases/tag/2.3.13)
- [KSP 2.3.12 release notes and AGP minimum](https://github.com/google/ksp/releases/tag/2.3.12)
- [AGP 9 built-in Kotlin and plugin configuration](https://developer.android.com/build/releases/agp-9-0-0-release-notes)
- [AGP 9.3 compatibility requirements](https://developer.android.com/build/releases/past-releases/agp-9-3-0-release-notes)
