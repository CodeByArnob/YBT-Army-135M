# Windows build and model runtime tests

The Windows build workflow validates the real local model before packaging the app.

## What the workflow does

1. Checks out the selected release source and downloads Git LFS model weights.
2. Verifies that `YBT-Army-135M/model.safetensors` is the real weight file, not an LFS pointer.
3. Loads the tokenizer and model with Transformers.
4. Runs a short greedy generation to verify inference.
5. Packages the Streamlit app, model files, and runtime dependencies into a Windows x64 onedir bundle.
6. Uploads `YBT-Army-Windows-x64.zip` as a workflow artifact and attaches it to the selected GitHub Release.

## Run locally

From the repository root, with Git LFS installed and model weights downloaded:

```powershell
git lfs install
git lfs pull
python -m pip install -r requirements.txt
python -m pip install pytest pyinstaller
python -m pytest -v tests/test_model_runtime.py
```

The Windows workflow creates the desktop bundle. The resulting ZIP contains the executable and its supporting files; extract the complete folder before running `YBT-Army.exe`. Keep the model folder beside the bundled application as included in the ZIP.

## Run the workflow

- For an existing release, open **Actions → YBT Army Windows Build → Run workflow** and enter its tag (for example, `v0.1.0`).
- Pushing a new `v*` tag also starts the workflow automatically.

The first build can take a while because PyTorch, Transformers, and the model weights are large. The generated package is a Windows x64 build; it is not an Android APK.
