# Bot identity contract 1.0.0

Dependency-free Java 8 UUID classification, HMAC v2 codec, historical username rules, signed admission bindings and persistence guards.

AquaCore and MicetPvP depend on gg.mineral:bot-identity-contract:1.0.0; MineralBot uses the Gradle project dependency. Each plugin bundles it.

Build and install locally with ./gradlew :bot-identity-contract:test :bot-identity-contract:publishToMavenLocal. All builds must use the same Maven local repository.

Authentication controls connection admission. UUID classification independently controls persistence before authentication, after logout and across restarts. Historical names are operator-review evidence only.
