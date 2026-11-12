# Minecraft preservation recovery

Repository: `https://github.com/yymm1976/SteadyChunks`

1. Clone the recorded snapshot branch.
2. Download all assets from the release URL recorded in `REMOTE-PRESERVATION.json`.
3. Verify each downloaded asset against its declared SHA-256.
4. For split assets, concatenate numbered parts before extracting.
5. Extract workspace assets into a temporary directory, not over the existing source tree.
6. Compare restored files with `PRESERVATION-MANIFEST.json`.

The original source directory is intentionally not modified or deleted by this snapshot.
