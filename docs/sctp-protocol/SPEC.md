# SCTP Protocol Specification

**Semantic Compression Token Protocol**

Version: 1.0.0

---

## Overview

SCTP (Semantic Compression Token Protocol) is a standardized protocol for AI code generation that reduces token consumption by 80-95% without sacrificing generation quality.

**Core Principle**: Only tell the model "what the boundaries are", not "what the current state is".

---

## Protocol Structure

### Three Message Types

#### 1. INSTRUCTION
```
Intent: {natural language description}
Targets: {comma-separated function names}
Scope: {comma-separated file:function boundaries}
```

#### 2. BOUNDARY
```
FUNC: {function signature}
INPUT: {compressed input contract}
OUTPUT: {compressed output contract}
EFFECTS: {compressed side effects}
DEPS: {compressed dependencies}
CONSTRAINTS: {compressed constraints}
```

#### 3. RESPONSE
```
SKELETON: {generated code}
AFFECTED: {comma-separated file paths}
CONFIDENCE: {0.0-1.0}
```

---

## Compression Mechanisms

1. **Semantic Boundary Extraction**: Only behavior boundaries, not implementation
2. **Dependency Graph Compression**: No full import statements
3. **Constraint-Only Transmission**: What the function MUST and MUST NOT do
4. **Incremental Updates**: Only diff of semantic boundaries
5. **Local Zero-Token Engine**: For complexity < 0.7 tasks
6. **SCTP-Lite Wire Format**: JSON with field deduplication

---

## Token Estimation

```
estimated_tokens = length(SBDL_text) / 4
```

**Target**: 80-95% compression ratio

---

## Version History

| Version | Date | Changes |
|---------|------|---------|
| 1.0.0 | 2026-05-07 | Initial release |

---

## License

SCTP Protocol is released under the Apache 2.0 License.