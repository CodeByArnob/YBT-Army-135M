<p align="center">
  <img src="assets/banner.svg" alt="YBT Army" width="100%">
</p>

<p align="center">
  <strong>Private AI. Runs locally. Works offline.</strong><br>
  A lightweight 134.5M-parameter language model with Windows and native Android clients.
</p>

<p align="center">
  <a href="https://github.com/CodeByArnob/YBT-Army-135M/releases/latest"><img src="https://img.shields.io/github/v/release/CodeByArnob/YBT-Army-135M?display_name=tag&style=for-the-badge&color=087F8C" alt="Latest release"></a>
  <a href="https://github.com/CodeByArnob/YBT-Army-135M/actions/workflows/android-apk.yml"><img src="https://img.shields.io/github/actions/workflow/status/CodeByArnob/YBT-Army-135M/android-apk.yml?branch=main&style=for-the-badge&label=Android%20CI" alt="Android CI"></a>
  <a href="LICENSE"><img src="https://img.shields.io/badge/License-MIT-BA8B35?style=for-the-badge" alt="MIT License"></a>
</p>

<p align="center">
  <a href="https://github.com/CodeByArnob/YBT-Army-135M/releases/latest"><strong>Download latest release</strong></a> &nbsp;·&nbsp;
  <a href="https://github.com/CodeByArnob/YBT-Army-135M/issues">Report a bug</a> &nbsp;·&nbsp;
  <a href="https://github.com/CodeByArnob/YBT-Army-135M/discussions">Discussions</a>
</p>

<p align="center">
  <a href="#quick-start">Quick start</a> &nbsp;·&nbsp;
  <a href="#features">Features</a> &nbsp;·&nbsp;
  <a href="#how-it-works">How it works</a> &nbsp;·&nbsp;
  <a href="#model-at-a-glance">Model</a> &nbsp;·&nbsp;
  <a href="#limitations">Limitations</a>
</p>

---

## Demo

<p align="center">
  <img src="assets/chat-demo.svg" alt="YBT Army chat demo" width="760">
</p>

## Features

- **Private and offline.** After the one-time install, the model runs on your own machine. No account, no API key, and your chats never leave your computer.
- **Small.** 134.5M parameters, 274&nbsp;MB on disk in FP16. Peak GPU memory while generating is about 284&nbsp;MB.
- **Fast enough to feel instant.** About 21 tokens per second and a 50&nbsp;ms first token on an RTX 3050. A normal CPU gives similar speed.
- **Clean chat UI.** White and black theme with dark mode that follows your device. Works on desktop and phone, with streaming replies and small, calm animations.
- **Saved chats.** Search, reopen and delete conversations. Everything is stored in one local JSON file.
- **Useful controls.** Regenerate, copy a reply, export a chat to Markdown, and switch reply style between Balanced, Concise, Detailed and Hinglish.
- **Exact answers where it matters.** Arithmetic, greetings and "who are you" questions are answered by built-in rules, so they are never wrong.

## Quick start

### Windows (one click)

Double-click **`run_you_b_tech.bat`**. On the first run it creates a virtual environment and installs the packages, then opens the app at <http://localhost:8501>.

### Manual

```bash
python -m venv .venv
.venv\Scripts\activate          # macOS / Linux: source .venv/bin/activate
pip install -r requirements.txt
streamlit run ybt_app.py
```

Python 3.9 or newer. A GPU is optional.

### Model weights

The weights live in `YBT-Army-135M/model.safetensors` (269&nbsp;MB), which is above GitHub's normal 100&nbsp;MB file limit. This repository tracks `*.safetensors` with **Git LFS** (see `.gitattributes`):

```bash
git lfs install
git clone <this-repo-url>
```

If you download the repository as a ZIP, copy the `YBT-Army-135M/` folder in separately.

## How it works

<p align="center">
  <img src="assets/pipeline.svg" alt="How YBT Army answers a question" width="100%">
</p>

1. Your message is wrapped in a chat template that starts with the YBT Army system prompt.
2. The tokenizer turns it into tokens, and a 30-layer transformer predicts the next token again and again.
3. Tokens are streamed back into the chat as soon as they are produced.

Maths, greetings and identity questions are caught first by small rules in `ybt_app.py`, so they skip the model entirely.

## Model at a glance

<p align="center">
  <img src="assets/YBT-Army-135M-spec.png" alt="YBT-Army-135M specification and performance" width="100%">
</p>

| | |
|---|---|
| Parameters | 134.5M |
| Layers / hidden size | 30 / 576 |
| Attention | 9 heads, 3 key-value heads (grouped-query) |
| Feed-forward | SwiGLU, 1,536 |
| Vocabulary | 49,152 tokens |
| Context length | 8,192 tokens |
| Precision | FP16 weights, 274 MB on disk |

Speed and accuracy were measured on an NVIDIA GeForce RTX 3050 (8 GB). Accuracy is a small 20-question check of my own (16 / 20 correct, scored on the first line of each answer). It is **not** a public benchmark, so treat it as a rough guide.

## Settings

Open **Preferences** in the sidebar:

| Setting | What it does |
|---|---|
| Dark mode | Follows your device on first visit, then remembers your choice for the session |
| Style | Balanced, Concise, Detailed or Hinglish replies |
| Creativity | Lower is more precise, higher is more varied |
| Max reply length | Upper limit on tokens per reply |
| Memory | How many earlier messages the model sees |

Chats are saved to `chats/chats.json`. Set the `YBT_CHATS_DIR` environment variable to store them somewhere else.

## Android app

The native Android app is built with Kotlin, Jetpack Compose and a pinned llama.cpp CPU runtime. It downloads the quantized model separately, verifies its SHA-256 checksum, and keeps inference and chat history on-device. No cloud inference server or paid API is used.

<p align="center">
  <a href="https://github.com/CodeByArnob/YBT-Army-135M/releases/tag/android-v0.1.0"><strong>Download Android v0.1.0</strong></a>
</p>

| Download | Purpose |
|---|---|
| `YBT-Army-Android-debug.apk` | Install the Android application |
| `YBT-Army-135M-Q4_K_M.gguf` | Separate AI model file; the app downloads this automatically on first launch |
| `*.sha256` | SHA-256 integrity checksums |

- [Android source, setup and architecture](android/README.md)
- [Android build and release workflow](.github/workflows/android-apk.yml)
- [All public releases](https://github.com/CodeByArnob/YBT-Army-135M/releases)

**Important model limitation:** YBT-Army-135M is a very small 134.5M-parameter model. It is suitable for experimentation and lightweight local chat, but it is not comparable to modern large instruction-tuned assistants and may produce generic, incomplete or incorrect answers. Do not rely on it for medical, legal, financial or other high-stakes decisions.

The APK is currently debug-signed for sideload testing. A Play Store production build requires a private signing keystore configured as GitHub Actions secrets.

## Project structure

```text
.
├── YBT-Army-135M/        model weights, tokenizer and chat template
├── assets/               README graphics (animated SVG + spec sheet)
├── .streamlit/           UI theme
├── ybt_app.py            the chat app
├── ybt_brand.py          name, system prompt and chat template
├── requirements.txt
├── run_you_b_tech.bat    one-click launcher for Windows
└── NOTICE.md             third-party licences
```

## Limitations

Be realistic about a 135M-parameter model.

- It can state wrong facts with confidence. Double-check anything important.
- Maths in free text is weak. Simple arithmetic is handled by rules, but word problems often fail.
- Replies in Hindi or Hinglish are rougher than in English.
- Long, multi-step reasoning and long documents are beyond its size.

## Roadmap

- A **larger model with more parameters** for more accurate answers.
- Better Hindi and Hinglish.
- A bigger, fairer evaluation set.


## Contributing

**Everyone is welcome to collaborate on this repository.** Fork it, make a change and open a pull request, or open an issue or Discussion with an idea. See **[CONTRIBUTING.md](CONTRIBUTING.md)** for the quick workflow and ideas for where to help.

## Troubleshooting

### 1. Python is not recognized

Make sure Python 3.9 or newer is installed and added to your system PATH. Restart your terminal after installation.

### 2. Dependency installation fails

Upgrade pip and reinstall the project dependencies:

```bash
python -m pip install --upgrade pip
pip install -r requirements.txt
```

### 3. Model weights are missing

Make sure Git LFS is installed and download the model files:

```bash
git lfs install
git lfs pull
```

### 4. The application does not start

Make sure you are running the command from the repository root directory:

```bash
streamlit run ybt_app.py
```

### 5. The application is not accessible in your browser

Check whether Streamlit started successfully and whether port `8501` is available.

### Reporting a problem

If these steps do not resolve your issue, open a [GitHub Issue](https://github.com/YouBTech01/YBT-Army-135M/issues) and include:

- Your operating system.
- Your Python version.
- The command you ran.
- The relevant error message.

Please remove personal information, private data and access tokens before sharing logs.

## Licence and credits

The YBT Army source code is released under the **[MIT licence](LICENSE)**. The model is derived from open-source weights that keep their own licence terms. Please keep **[`NOTICE.md`](NOTICE.md)** when you share or publish this project.

YBT Army is built and maintained by the YBT Army team.
