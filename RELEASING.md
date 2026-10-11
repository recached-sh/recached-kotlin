# Releasing

Pushing a `v*` tag runs [`.github/workflows/release.yml`](.github/workflows/release.yml),
which publishes `dev.recached:recached-android` and
`dev.recached:recached-android-ffi` to Maven Central, then creates a GitHub
Release from the CHANGELOG entry.

A version on Maven Central can never be replaced or deleted. The workflow
uploads nothing until the tag checks and the full CI suite, emulator tests
included, have passed.

## One-time setup

### 1. Central Portal account and namespace

1. Sign in at <https://central.sonatype.com>.
2. **Namespaces → Add Namespace →** `dev.recached`. The portal shows a
   verification key.
3. Prove you own `recached.dev`: add a DNS **TXT** record on the apex
   (`@`) whose value is that key, at the domain's DNS host (GoDaddy, going
   by its `domaincontrol.com` name servers). Then press **Verify** in the
   portal. DNS can take a few minutes to propagate. You can delete the record
   once the namespace shows *Verified*.

### 2. Publishing token

**Account → Generate User Token.** It is a username and password pair, not
your login. Store both:

```sh
gh secret set MAVEN_CENTRAL_USERNAME --repo recached-sh/recached-kotlin
gh secret set MAVEN_CENTRAL_PASSWORD --repo recached-sh/recached-kotlin
```

### 3. Signing key

Maven Central requires every file to carry a PGP signature, and it checks each
one against a public key server.

```sh
gpg --quick-gen-key 'Recached Releases <you@example.com>' ed25519 sign 0
gpg --list-secret-keys --keyid-format long          # note the key id
gpg --keyserver keys.openpgp.org --send-keys <KEY_ID>
gpg --keyserver keyserver.ubuntu.com --send-keys <KEY_ID>

gpg --armor --export-secret-keys <KEY_ID> | gh secret set SIGNING_KEY --repo recached-sh/recached-kotlin
gh secret set SIGNING_KEY_PASSWORD --repo recached-sh/recached-kotlin   # the key's passphrase, if it has one
```

keys.openpgp.org only publishes a key after you confirm the email address it
sends. Keep a backup of the secret key and its revocation certificate somewhere
outside GitHub; a secret cannot be read back.

## Each release

1. **Release the core first.** The SDK ships the native libraries of a
   recached release, so that release must exist, with its
   `recached-mobile-android-<version>.zip` attached.
2. **Pin it** in `engine.properties`:

   ```properties
   ref=v0.4.0
   version=0.4.0
   url=https://github.com/recached-sh/recached/releases/download/v0.4.0/recached-mobile-android-0.4.0.zip
   sha256=<contents of the release's recached-mobile-android-0.4.0.zip.sha256>
   ```

3. **Set the version** in `gradle.properties` (`VERSION_NAME`). In
   `CHANGELOG.md`, replace `(unreleased)` on its heading with the date, and
   update the install instructions in the README.
4. **Merge to `main`**, and wait for CI there to pass with the pinned release.
5. **Tag** the merge commit, with the `v` prefix and the same version:

   ```sh
   git tag v0.1.0 && git push origin v0.1.0
   ```

The workflow refuses the tag if it does not match `VERSION_NAME`, is not on
`main`, has no dated CHANGELOG entry, or if `engine.properties` pins no
release or names a `ref` other than that release's tag.

Once the workflow is green, the artifacts still take a while to resolve
through `mavenCentral()` and to show up in search.

## If it fails

- **Before *Publish to Maven Central*:** nothing was uploaded. Fix the cause,
  then move the tag to the fixed commit (`git tag -f v0.1.0 <commit>`,
  `git push -f origin v0.1.0`) or bump the version.
- **During or after the upload:** check **Deployments** in the Central
  Portal. A deployment that failed validation is not published; drop it there
  and re-run the job. A deployment that published is final: fix forward with a
  new version.
