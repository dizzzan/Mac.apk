# Opt-in guest code-page W^X checkpoint

`MACAPK_JIT_FAULT_WX=1` supports guest anonymous code that requests persistent
read/write/execute permissions on Apple Silicon. The host receives RW or RX
permissions. Instruction-fetch faults switch registered pages to RX and retry;
write faults switch them to RW and retry. Registers and the faulting PC are
preserved. No instruction or app-specific control flow is skipped.

This mode is disabled by default. It replaces global JIT write-window
serialization while enabled, since persistent RWX callers do not necessarily
close a write window with a later RX call.

The signal reader uses a bounded registry of 4096 host pages (64 MiB on the
16 KiB host page size). Successful explicit protection changes, munmap and
MAP_FIXED replacement remove registrations. Registry exhaustion fails with
ENOMEM. Generated canonical syscall wrappers still require
`MACAPK_DYNAMIC_SVC_BRK=1` to route their Linux calls through the bridge.

Validation on Apple Silicon:

- `FaultWxTest.cpp` executes generated code, changes it, and executes the new
  code through mmap and mprotect. Mach VM queries verify actual RW/RX
  permissions. Two threads repeat changes on independent pages.
- Its `disabled` argument, with the environment flag off, verifies the original
  host failure for anonymous RWX mappings.
- `FaultWxPagesTest.cpp` checks bounds, overlaps, removals and capacity under
  AddressSanitizer and UndefinedBehaviorSanitizer.
- `MprotectFailureTest.cpp` verifies the separate failed-write-window lock fix
  with this mode disabled.

Build and stage the current shim before testing. Load it from runtime/build/bin
with that directory in DYLD_LIBRARY_PATH, so dependencies cannot resolve an older
copy. The generated-code executable needs the local runtime's unsigned
executable-memory entitlement.

Clapper still fails during native initialization. This checkpoint removes an
execute-permission failure in generated code; it does not establish that Clapper
works. Simultaneous execution and writing of the same host page can repeatedly
fault and needs broader validation. This is an investigation checkpoint, not a
change proposed for default enablement or upstream submission.
