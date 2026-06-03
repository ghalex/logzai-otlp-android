# Publishing

`logzai-otlp-android` is published to **Maven Central** as
`com.logzai:logzai-otlp-android:<version>`, the same way the Python (PyPI) and
JavaScript (npm) LogzAI clients are distributed. Consumers resolve it from
`mavenCentral()` — no extra repository or credentials on their side.

Releases are cut by CI on a git tag (`.github/workflows/publish.yml`). You should not
need to publish from a laptop.

## One-time setup

1. **Verify the `com.logzai` namespace** on the Central Portal
   (<https://central.sonatype.com>). Sonatype will give you a DNS `TXT` challenge to add
   to the `logzai.com` zone. (If you'd rather not use the domain, switch `GROUP` in
   `gradle.properties` to `io.github.ghalex`, which is verified via a GitHub repo
   instead — but `com.logzai` is on-brand with the py/js packages.)

2. **Generate a Central Portal user token**: Central Portal → Account → *Generate User
   Token*. The returned username/password become the `MAVEN_CENTRAL_*` secrets below.

3. **Create a GPG signing key** (Central requires signed artifacts):
   ```bash
   gpg --gen-key                                   # create a key
   gpg --armor --export-secret-keys <KEY_ID>       # the SIGNING_KEY secret (full ASCII block)
   gpg --keyserver keyserver.ubuntu.com --send-keys <KEY_ID>   # publish the public key
   ```

4. **Add repository secrets** (GitHub → Settings → Secrets and variables → Actions):

   | Secret | Value |
   |---|---|
   | `MAVEN_CENTRAL_USERNAME` | Central Portal token username |
   | `MAVEN_CENTRAL_PASSWORD` | Central Portal token password |
   | `SIGNING_KEY` | ASCII-armored private key (whole block) |
   | `SIGNING_KEY_PASSWORD` | passphrase for that key |

5. **Confirm the repo URLs** in `gradle.properties` (`POM_URL`, `POM_SCM_*`) match the
   actual GitHub repo once you create it. They currently point at
   `github.com/ghalex/logzai-otlp-android`.

## Cutting a release

1. Bump `VERSION_NAME` in `gradle.properties` (or rely on the tag — see below).
2. Tag and push:
   ```bash
   git tag v0.1.0
   git push origin v0.1.0
   ```
3. The workflow derives the version from the tag (`v0.1.0` -> `0.1.0`), builds, signs,
   uploads, and releases the deployment on the Central Portal. It appears on Maven
   Central a short while later.

Use semantic versioning and never re-publish a version that's already released.

## Local testing

To try the artifact in another project without going through Central:

```bash
./gradlew publishToMavenLocal      # publishes unsigned to ~/.m2
```

Then add `mavenLocal()` to that project's repositories and depend on
`com.logzai:logzai-otlp-android:0.1.0`. (Signing is skipped locally because no key is
present; CI signs because the secrets are set.)
