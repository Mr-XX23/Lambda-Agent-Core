# Security policy

## Reporting

Do not report vulnerabilities in public issues. Contact the repository maintainers
privately with reproduction steps, affected version, impact, and a proposed mitigation.

## Security boundaries

Tool capabilities are deny-by-policy when an application supplies a restrictive
permission policy. Prebuilt tools enforce path/host/command allowlists, approval,
timeouts, and output limits. These are not a substitute for OS isolation: untrusted
tools should run in a container or separate service with restricted filesystem,
network, CPU, memory, and syscall access.

CI runs the unit and integration test suite on every change. Tests covering path
traversal, authorization, optimistic locking, malformed provider data, and bounded
tool output are release blockers.
