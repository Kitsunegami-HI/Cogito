# libs

[Baritone](https://github.com/cabaletta/baritone) by cabaletta, included unmodified, one jar per Minecraft version:

| Jar | Minecraft |
| --- | --- |
| `baritone-api-neoforge-1.18.0.jar` | 26.1.2 (NeoForge) |
| `baritone-api-neoforge-1.11.3.jar` | 1.21.1 (NeoForge) |

Cogito compiles against it and uses it at runtime when it is installed; it stays a separate mod jar.
Each version's `gradle.properties` names its jar (`baritone_jar`). Both were taken from the project's GitHub releases,
and the 1.11.3 jar matches the SHA-1 in that release's `checksums.txt`.

Baritone is licensed under the GNU Lesser General Public License v3.0, not under Cogito's MIT license.
The license text is in [baritone-LICENSE.txt](baritone-LICENSE.txt); it builds on the GNU General Public
License v3.0, published at https://www.gnu.org/licenses/gpl-3.0.txt. The source is at
https://github.com/cabaletta/baritone.
