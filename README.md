# Personal Discord patch feed (Morphe)

A private Morphe patch bundle for Discord: **Hide blocked or ignored messages** (Conduit, from
lildanlid1/Cordly) plus **Hide gift button**, **Hide quest promo banner** and **Pin app to built-in
bundle** (jrddupont/discord-patches). Vetted for **Discord 351.11 and 354.12 Stable** by actually
patching 354.12 — all five patches apply with zero failures.

GPL-3.0, like both upstream repos; keep their attribution in `scripts/build.py` (bundle manifest).

## Verify / build locally

```sh
python3 scripts/build.py          # -> patches/build/libs/patches-1.26.0.mpp (downloads pinned toolchains once)
python3 scripts/morphe_cli.py     # prints the Morphe CLI jar path
java -jar work/tools/morphe-cli/morphe-desktop-*-all.jar patch \
  -p patches/build/libs/patches-1.26.0.mpp \
  --exclusive \
  -e "Conduit settings" -e "Hide blocked or ignored messages" \
  -e "Hide quest promo banner" -e "Hide gift button" -e "Pin app to built-in bundle" \
  -o out/discord-patched.apk your-discord.apkm
```

The bundle contains exactly these 5 patches (Conduit settings is the hidden dependency of
Hide blocked or ignored messages). To add more Conduit features later, copy the matching
`bundledPlugin(...)` line back from lildanlid1/Cordly into DiscordPatches.kt.

## Push your feed

1. Create an empty GitHub repo (e.g. `yourname/my-discord-patches`), then:

   ```sh
   git remote add origin git@github.com:yourname/my-discord-patches.git
   git push -u origin main --tags
   ```

2. Find and replace `YOURNAME` → your GitHub username and `my-discord-patches` → your repo name in:
   - `scripts/build.py` (bundle manifest: Name / Source / Author)
   - `scripts/release_notes.py` (`REPOS` allow-list)
   - `CHANGELOG.md` (compare link)
   - this README

3. Tag the release so `patches-list.json` / `patches-bundle.json` exist on a tag and main:

   ```sh
   git tag v1.26.0 && git push origin v1.26.0
```

4. Re-run the GitHub Actions workflow (or push again) so a `v1.26.0` release with
   `patches-1.26.0.mpp` exists — `patches-bundle.json` points Manager at that release asset.
```   ```

   Morphe Manager fetches `patches-list.json` from the repo's default branch, so main is enough;
   the tag is for your own bookkeeping. (Cordly's release.yml workflow is not included — this
   bundle is released by hand.)

## Subscribe on your phone

[![Add to Morphe](.github/assets/add-to-morphe.svg)](https://morphe.software/add-source?github=pfn/android-discord-patches)


In Morphe Manager: **Settings → Patch sources → add source**, enter
`https://github.com/yourname/my-discord-patches` — or open this deep link on the phone:

```
https://morphe.software/add-source?github=yourname/my-discord-patches
```

Nothing is submitted to the community index; a public-but-unadvertised repo stays private in
practice. Manager re-checks the source in the background and shows a re-patch banner when you
push a new release.

## Updating for a future Discord release

- The two Hermes patches (gift button, quest banner) are self-locating: they find their targets in
  `assets/index.android.bundle` at patch time and fail loudly with the exact reason if Discord
  changed the code shape. Often they need no change at all.
- Conduit's version pin lives in one place: the `discord` compatibility block in
  `patches/src/main/kotlin/app/venus/patches/DiscordPatches.kt` — add the new
  `AppTarget(version = "X.Y - Stable", versionCode = XY00...)`.
- The loader pin (`pinPackagedDiscordBundle354`) and the Conduit smali bridges fingerprint by
  structure and fail loudly instead of guessing. When a Discord release breaks them, the CLI run
  names the failing fingerprint; re-derive it from that class in the new APK.
- Always re-verify with the CLI command above before trusting a new release.
