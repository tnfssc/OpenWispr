---
name: Build warnings in upstream code are left alone
description: Only warnings in OpenWispr's own code get fixed
---
The keyboard build prints ~800 native warnings (ggml, protobuf, sentencepiece) and ~150 Kotlin/Java
warnings, nearly all in unmodified FUTO code. The user decided to fix only warnings in code we wrote,
to keep upstream files close to upstream for syncing. As of the 70a5d390c sync our code builds without
warnings; check by comparing warned lines against `git diff futo/master` before fixing anything.
